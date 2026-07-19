package com.quant.stock.task;

import com.quant.stock.backtest.FillTimingHelper;
import com.quant.stock.backtest.PositionState;
import com.quant.stock.backtest.BatchStockBackTestService;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarStorageService;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.pool.TradePoolService;
import com.quant.stock.risk.LimitBoardHelper;
import com.quant.stock.risk.LiveAccountRiskState;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.risk.RiskControlService;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.MaCrossStrategy;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.trade.TradeGatewayService;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.PositionAmountUtil;
import com.quant.stock.util.RedisLockUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实盘策略任务实现：行情扫描+策略、订单同步、收盘清算。
 * 分钟扫描目标为 {@link TradePoolService} 活跃候选池，不扫全市场。
 * 调度由 {@link DynamicScheduleService} 按 MySQL {@code sys_schedule_job} 动态触发。
 */
@Slf4j
@Component
public class StrategyTask {

    private static final int LIMIT_DOWN_FORCE_DAYS = 3;
    private static final int PENDING_BUY_EXPIRE_DAYS = 5;

    private final QuantProperties quantProperties;
    private final MarketDataService marketDataService;
    private final MaCrossStrategy maCrossStrategy;
    private final RiskControlService riskControlService;
    private final OpenFilterService openFilterService;
    private final LiveAccountRiskState accountRiskState;
    private final TradeGatewayService tradeGatewayService;
    private final TradeCostModel tradeCostModel;
    private final PositionAmountUtil positionAmountUtil;
    private final RedisLockUtil redisLockUtil;
    private final BatchStockBackTestService batchStockBackTestService;

    @Autowired(required = false)
    private BarStorageService barStorageService;

    @Autowired(required = false)
    private TradePoolService tradePoolService;

    private volatile BigDecimal simCash = new BigDecimal("100000");
    private final Map<String, LiveBook> books = new ConcurrentHashMap<String, LiveBook>();

    public StrategyTask(QuantProperties quantProperties,
                        MarketDataService marketDataService,
                        MaCrossStrategy maCrossStrategy,
                        RiskControlService riskControlService,
                        OpenFilterService openFilterService,
                        LiveAccountRiskState accountRiskState,
                        TradeGatewayService tradeGatewayService,
                        TradeCostModel tradeCostModel,
                        PositionAmountUtil positionAmountUtil,
                        RedisLockUtil redisLockUtil,
                        BatchStockBackTestService batchStockBackTestService) {
        this.quantProperties = quantProperties;
        this.marketDataService = marketDataService;
        this.maCrossStrategy = maCrossStrategy;
        this.riskControlService = riskControlService;
        this.openFilterService = openFilterService;
        this.accountRiskState = accountRiskState;
        this.tradeGatewayService = tradeGatewayService;
        this.tradeCostModel = tradeCostModel;
        this.positionAmountUtil = positionAmountUtil;
        this.redisLockUtil = redisLockUtil;
        this.batchStockBackTestService = batchStockBackTestService;
    }

    @PostConstruct
    public void init() {
        accountRiskState.reset(simCash);
        log.info("StrategyTask 已就绪（调度由 DynamicScheduleService / sys_schedule_job 控制）");
    }

    public void scanAndTrade() {
        if (!redisLockUtil.tryLock("strategy-scan", 50)) {
            return;
        }
        try {
            List<String> targets = resolveLiveScanCodes();
            if (targets.isEmpty()) {
                log.warn("目标池为空，跳过分钟扫描（请先执行盘后扫描写入目标池）");
                return;
            }
            for (String code : targets) {
                List<BarDTO> bars = marketDataService.loadMinuteBars(code);
                if (bars == null || bars.size() < 65) {
                    continue;
                }
                int i = bars.size() - 1;
                BarDTO bar = bars.get(i);
                LocalDate tradeDay = bar.getBarBegin().toLocalDate();
                BigDecimal open = bar.getOpen();
                BigDecimal high = bar.getHigh();
                BigDecimal low = bar.getLow();
                BigDecimal close = bar.getClose();
                LiveBook book = books.computeIfAbsent(code, k -> new LiveBook());
                IndicatorSignalUtil.IndicatorBundle ind = IndicatorSignalUtil.precompute(bars);
                if (book.lastTradeDay == null || !book.lastTradeDay.equals(tradeDay)) {
                    book.lastTradeDay = tradeDay;
                    book.pos.clearAddedToday();
                }

                // Step1: 撮合挂单
                fillPending(code, book, bars, i, tradeDay, open);

                // Step2: 老仓止损
                if (book.pos.hasPosition() && quantProperties.isStopLossEnabled()
                        && book.pos.canSellStops(tradeDay)) {
                    book.pos.updateHighest(high);
                    int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
                    if (sellable >= 100
                            && book.pos.getStopPrice().compareTo(BigDecimal.ZERO) > 0
                            && low.compareTo(book.pos.getStopPrice()) <= 0) {
                        BigDecimal fillBase = book.pos.getStopPrice().max(low);
                        boolean full = sellable >= book.pos.getShares();
                        executeLiveSell(code, book, bars, i, fillBase, sellable, full, tradeDay);
                    }
                } else if (book.pos.hasPosition()) {
                    book.pos.updateHighest(high);
                }

                BigDecimal equity = markEquity(close);
                accountRiskState.onEquity(tradeDay, equity);
                BigDecimal posScale = accountRiskState.positionScale(equity);
                if (book.pos.hasPosition() && accountRiskState.isHalted() && !book.pendingSell) {
                    book.pendingSell = true;
                    book.pendingSellSignalDay = tradeDay;
                }

                // Step4: 信号挂单
                boolean buySignal = maCrossStrategy.isBuySignalAt(ind, i);
                boolean sellSignal = ind.isMaCrossDown(i);
                boolean stoppedOutToday = false;

                if (!book.pos.hasPosition() && buySignal && !book.pendingSell && book.pendingBuyVol == null
                        && accountRiskState.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0
                        && openFilterService.canOpen(code, bars, i)) {
                    BigDecimal atr = atrAt(ind, i);
                    if (atr.compareTo(quantProperties.getAtrMinThreshold()) > 0) {
                        book.targetFullVol = positionAmountUtil.calcBuyVolume(simCash, close, atr, posScale);
                        int first = quantProperties.isPyramidEnabled()
                                ? positionAmountUtil.pyramidSlice(book.targetFullVol, 0) : book.targetFullVol;
                        if (first >= 100) {
                            book.pendingBuyVol = first;
                            book.pendingBuyPyramid = false;
                            book.pendingBuySignalDay = tradeDay;
                        }
                    }
                } else if (book.pos.hasPosition() && quantProperties.isPyramidEnabled()
                        && book.pyramidStage >= 1 && book.pyramidStage < 3
                        && book.pendingBuyVol == null
                        && !book.pos.isAddedToday()
                        && close.compareTo(book.pos.getAvgCost()
                        .multiply(BigDecimal.ONE.add(quantProperties.getPyramidAddPct()))) >= 0
                        && ind.ma5[i] > ind.ma20[i]
                        && accountRiskState.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0) {
                    int slice = positionAmountUtil.pyramidSlice(book.targetFullVol, book.pyramidStage);
                    BigDecimal posMv = calcPositionMv();
                    BigDecimal addMoney = close.multiply(BigDecimal.valueOf(Math.max(slice, 0)));
                    if (slice >= 100 && positionAmountUtil.withinTotalPosition(equity, posMv, addMoney)) {
                        book.pendingBuyVol = slice;
                        book.pendingBuyPyramid = true;
                        book.pendingBuySignalDay = tradeDay;
                    }
                }

                if (book.pos.hasPosition() && sellSignal && !stoppedOutToday && !book.pendingSell) {
                    book.pendingSell = true;
                    book.pendingSellSignalDay = tradeDay;
                }

                // 非 nextBar：当根撮合（配置关闭时）
                if (!quantProperties.isNextBarOpenFill()) {
                    fillPendingSameBar(code, book, bars, i, tradeDay, close);
                }

                if (book.pos.hasPosition() && quantProperties.isTrailingStopEnabled()) {
                    book.pos.raiseTrailingStop(atrAt(ind, i), quantProperties.getTrailingAtrMultiplier());
                }
            }
        } finally {
            redisLockUtil.unlock("strategy-scan");
        }
    }

    private void fillPending(String code, LiveBook book, List<BarDTO> bars, int i,
                             LocalDate tradeDay, BigDecimal open) {
        if (book.pendingBuyVol != null && book.pendingBuySignalDay != null) {
            if (tradeDay.isAfter(book.pendingBuySignalDay.plusDays(PENDING_BUY_EXPIRE_DAYS))) {
                book.pendingBuyVol = null;
                book.pendingBuyPyramid = false;
                book.pendingBuySignalDay = null;
            } else if (tradeDay.isAfter(book.pendingBuySignalDay)
                    && FillTimingHelper.canFillPendingOnBar(bars, i)
                    && openFilterService.canExecuteOpenFill(code, bars, i)) {
                executeLiveBuy(code, book, bars, i, open, tradeDay);
            }
        }
        if (book.pendingSell && book.pos.hasPosition() && book.pendingSellSignalDay != null
                && tradeDay.isAfter(book.pendingSellSignalDay)
                && FillTimingHelper.canFillPendingOnBar(bars, i)) {
            boolean limitDown = openFilterService.isLimitDownAt(bars, i);
            if (limitDown) {
                if (book.lastLimitDownFailDay == null || !book.lastLimitDownFailDay.equals(tradeDay)) {
                    book.limitDownFailDays++;
                    book.lastLimitDownFailDay = tradeDay;
                }
                if (book.limitDownFailDays < LIMIT_DOWN_FORCE_DAYS) {
                    return;
                }
                BigDecimal prev = openFilterService.prevTradingDayClose(bars, i);
                BigDecimal force = LimitBoardHelper.limitDownPrice(prev, code);
                if (force == null) {
                    force = open;
                }
                force = force.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
                executeLiveSell(code, book, bars, i, force, book.pos.getShares(), true, tradeDay);
            } else {
                executeLiveSell(code, book, bars, i, open, book.pos.getShares(), true, tradeDay);
            }
        }
    }

    private void fillPendingSameBar(String code, LiveBook book, List<BarDTO> bars, int i,
                                    LocalDate tradeDay, BigDecimal close) {
        if (book.pendingBuyVol != null && book.pendingBuyVol >= 100) {
            executeLiveBuy(code, book, bars, i, close, tradeDay);
        }
        if (book.pendingSell && book.pos.hasPosition()) {
            executeLiveSell(code, book, bars, i, close, book.pos.getShares(), true, tradeDay);
        }
    }

    private void executeLiveBuy(String code, LiveBook book, List<BarDTO> bars, int i,
                                BigDecimal base, LocalDate tradeDay) {
        int vol = book.pendingBuyVol == null ? 0 : book.pendingBuyVol;
        boolean pyramid = book.pendingBuyPyramid;
        book.pendingBuyVol = null;
        book.pendingBuyPyramid = false;
        book.pendingBuySignalDay = null;
        if (vol < 100) {
            return;
        }
        BigDecimal deal = tradeCostModel.buyPrice(base, bars, i, vol);
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.buyFee(amount);
        BigDecimal equity = markEquity(bars.get(i).getClose());
        BigDecimal posMv = calcPositionMv();
        Map<String, Integer> gatewayPos = tradeGatewayService.queryPositions();
        if (!riskControlService.checkBuy(code, deal, vol, simCash, posMv, gatewayPos, bars, i)) {
            return;
        }
        if (amount.add(fee).compareTo(simCash) > 0) {
            return;
        }
        OrderDTO order = tradeGatewayService.placeOrder(code, OrderDTO.Side.BUY, deal, vol);
        if (order == null || order.getStatus() == OrderDTO.Status.REJECTED) {
            return;
        }
        // sdk 模式可能 SUBMITTED，仍先扣减模拟资金（与回测一致的演示语义）
        if (order.getStatus() == OrderDTO.Status.FILLED || order.getStatus() == OrderDTO.Status.SUBMITTED) {
            simCash = simCash.subtract(amount).subtract(fee);
            book.pos.addBuy(vol, deal, fee, tradeDay);
            book.pos.raiseStopByCost(atrAt(IndicatorSignalUtil.precompute(bars), i), equity,
                    quantProperties.getAtrStopMultiplier(), quantProperties.getHardStopCapitalPct());
            if (pyramid) {
                book.pyramidStage++;
            } else {
                book.pyramidStage = Math.max(book.pyramidStage, 1);
            }
            log.info("策略买入: {} {}@{} x{} fee={}", code, order.getOrderId(), deal, vol, fee);
        }
    }

    private void executeLiveSell(String code, LiveBook book, List<BarDTO> bars, int i,
                                 BigDecimal base, int vol, boolean clearAll, LocalDate tradeDay) {
        vol = (vol / 100) * 100;
        if (vol < 100 || !book.pos.hasPosition()) {
            return;
        }
        if (!riskControlService.checkSell(code, vol, tradeGatewayService.queryPositions())) {
            return;
        }
        BigDecimal avg = book.pos.getAvgCost();
        BigDecimal deal = tradeCostModel.sellPrice(base, bars, i, vol);
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.sellFee(amount);
        BigDecimal pnl = deal.subtract(avg).multiply(BigDecimal.valueOf(vol)).subtract(fee);
        OrderDTO order = tradeGatewayService.placeOrder(code, OrderDTO.Side.SELL, deal, vol);
        if (order == null || order.getStatus() == OrderDTO.Status.REJECTED) {
            return;
        }
        simCash = simCash.add(amount).subtract(fee);
        if (clearAll || vol >= book.pos.getShares()) {
            book.pos.clear();
            book.pyramidStage = 0;
            book.targetFullVol = 0;
        } else {
            book.pos.removeShares(vol);
        }
        book.pendingSell = false;
        book.pendingSellSignalDay = null;
        book.limitDownFailDays = 0;
        book.lastLimitDownFailDay = null;
        accountRiskState.onClosedRound(pnl.compareTo(BigDecimal.ZERO) > 0, tradeDay);
        log.info("策略卖出: {} {}@{} x{} fee={} pnl={}", code, order.getOrderId(), deal, vol, fee, pnl);
    }

    public void syncOrders() {
        tradeGatewayService.syncOrderStatus();
    }

    /**
     * 收盘清算与 K 线聚合。
     * <p>
     * TODO(api): 真实行情拉取（与 market-collect 同源）；当前 fetch 为 db/mock 回退。
     */
    public void settleAfterClose() {
        BigDecimal closeEquity = markEquity(null);
        accountRiskState.onDayClose(closeEquity);
        log.info("收盘清算开始, 模拟现金={}, 权益={}, 持仓={}",
                simCash, closeEquity, tradeGatewayService.queryPositions());
        if (barStorageService == null) {
            log.info("未启用数据库(quant.db-enabled=false)，跳过K线聚合落库");
            return;
        }
        // TODO(api): 接入真实行情后再做可靠增量拉取与聚合
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = LocalDateTime.of(today.minusDays(5), LocalTime.of(9, 30));
        LocalDateTime dayEnd = LocalDateTime.of(today, LocalTime.of(15, 0));
        for (String code : resolveSettleCodes()) {
            try {
                marketDataService.fetchAndPersist1Min(code);
                barStorageService.aggregateAllPeriods(code, dayStart, dayEnd);
            } catch (Exception e) {
                log.warn("收盘聚合失败 code={}: {}", code, e.getMessage());
            }
        }
        log.info("收盘清算/多周期聚合完成");
    }

    /**
     * 盘后扫描：覆盖唯一目标池（与 pool-rebuild 同类；调度侧启用其一即可）。
     */
    public void afterMarketBatchScan() {
        log.info("盘后入池扫描触发（覆盖唯一目标池）");
        if (tradePoolService != null) {
            tradePoolService.rebuildFromUniverse();
        } else {
            log.warn("TradePoolService 不可用，回退批量扫描 stock-codes");
            batchStockBackTestService.scanAll();
        }
    }

    /** 实盘分钟扫描：仅活跃交易候选池 */
    private List<String> resolveLiveScanCodes() {
        if (tradePoolService != null) {
            return tradePoolService.listActiveCodes();
        }
        return quantProperties.stockCodeList();
    }

    /** 收盘聚合：候选池 ∪ 当前持仓 */
    private List<String> resolveSettleCodes() {
        Set<String> codes = new LinkedHashSet<String>();
        if (tradePoolService != null) {
            codes.addAll(tradePoolService.listActiveCodes());
        }
        Map<String, Integer> pos = tradeGatewayService.queryPositions();
        if (pos != null) {
            codes.addAll(pos.keySet());
        }
        if (codes.isEmpty()) {
            codes.addAll(quantProperties.stockCodeList());
        }
        return new ArrayList<String>(codes);
    }

    /** 模拟现金（实盘扫描账本） */
    public BigDecimal getSimCash() {
        return simCash;
    }

    /** 现金 + 持仓市值 */
    public BigDecimal getMarkEquity() {
        return markEquity(null);
    }

    public BigDecimal getPositionMarketValue() {
        return calcPositionMv();
    }

    /**
     * 当前持仓明细（优先策略账本成本/止损；数量以网关持仓为准）。
     */
    public List<Map<String, Object>> listLivePositionViews() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        Map<String, Integer> gateway = tradeGatewayService.queryPositions();
        Set<String> codes = new LinkedHashSet<String>();
        if (gateway != null) {
            codes.addAll(gateway.keySet());
        }
        codes.addAll(books.keySet());
        for (String code : codes) {
            int gwVol = gateway == null || gateway.get(code) == null ? 0 : gateway.get(code);
            LiveBook book = books.get(code);
            int bookVol = book != null && book.pos != null ? book.pos.getShares() : 0;
            int vol = Math.max(gwVol, bookVol);
            if (vol <= 0) {
                continue;
            }
            BigDecimal last = lastClose(code, null);
            BigDecimal avg = book != null && book.pos != null ? book.pos.getAvgCost() : BigDecimal.ZERO;
            BigDecimal stop = book != null && book.pos != null ? book.pos.getStopPrice() : BigDecimal.ZERO;
            BigDecimal highest = book != null && book.pos != null ? book.pos.getHighestSinceEntry() : BigDecimal.ZERO;
            BigDecimal mv = last.multiply(BigDecimal.valueOf(vol));
            BigDecimal pnl = BigDecimal.ZERO;
            BigDecimal pnlPct = BigDecimal.ZERO;
            if (avg != null && avg.compareTo(BigDecimal.ZERO) > 0) {
                pnl = last.subtract(avg).multiply(BigDecimal.valueOf(vol));
                pnlPct = last.subtract(avg).divide(avg, 6, RoundingMode.HALF_UP);
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("code", code);
            row.put("volume", vol);
            row.put("gatewayVolume", gwVol);
            row.put("bookVolume", bookVol);
            row.put("avgCost", avg);
            row.put("lastPrice", last);
            row.put("marketValue", mv);
            row.put("unrealizedPnl", pnl);
            row.put("unrealizedPnlPct", pnlPct);
            row.put("stopPrice", stop);
            row.put("highestSinceEntry", highest);
            row.put("lastBuyDate", book != null && book.pos != null && book.pos.getLastBuyDate() != null
                    ? book.pos.getLastBuyDate().toString() : null);
            row.put("pyramidStage", book == null ? 0 : book.pyramidStage);
            row.put("pendingBuy", book != null && book.pendingBuyVol != null && book.pendingBuyVol > 0);
            row.put("pendingSell", book != null && book.pendingSell);
            list.add(row);
        }
        return list;
    }

    private BigDecimal markEquity(BigDecimal fallbackPrice) {
        BigDecimal mv = simCash;
        Map<String, Integer> positions = tradeGatewayService.queryPositions();
        for (Map.Entry<String, Integer> e : positions.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            BigDecimal px = lastClose(e.getKey(), fallbackPrice);
            mv = mv.add(px.multiply(BigDecimal.valueOf(e.getValue())));
        }
        return mv;
    }

    private BigDecimal calcPositionMv() {
        BigDecimal mv = BigDecimal.ZERO;
        Map<String, Integer> positions = tradeGatewayService.queryPositions();
        for (Map.Entry<String, Integer> e : positions.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            BigDecimal px = lastClose(e.getKey(), null);
            mv = mv.add(px.multiply(BigDecimal.valueOf(e.getValue())));
        }
        return mv;
    }

    private BigDecimal lastClose(String code, BigDecimal fallback) {
        try {
            List<BarDTO> bars = marketDataService.loadMinuteBars(code);
            if (bars != null && !bars.isEmpty() && bars.get(bars.size() - 1).getClose() != null) {
                return bars.get(bars.size() - 1).getClose();
            }
        } catch (Exception ignored) {
            // fall through
        }
        LiveBook book = books.get(code);
        if (book != null && book.pos.hasPosition() && book.pos.getAvgCost().compareTo(BigDecimal.ZERO) > 0) {
            return book.pos.getAvgCost();
        }
        return fallback == null ? BigDecimal.ZERO : fallback;
    }

    private static BigDecimal atrAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (ind == null || i < 0 || Double.isNaN(ind.atr14[i])) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(ind.atr14[i]);
    }

    private static final class LiveBook {
        final PositionState pos = new PositionState();
        LocalDate lastTradeDay;
        Integer pendingBuyVol;
        boolean pendingBuyPyramid;
        LocalDate pendingBuySignalDay;
        boolean pendingSell;
        LocalDate pendingSellSignalDay;
        int pyramidStage;
        int targetFullVol;
        int limitDownFailDays;
        LocalDate lastLimitDownFailDay;
    }
}
