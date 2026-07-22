package com.quant.stock.task;

import com.quant.stock.backtest.FillTimingHelper;
import com.quant.stock.backtest.PositionState;
import com.quant.stock.backtest.BatchStockBackTestService;
import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.CoreMarketBarService;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.pool.TradePoolService;
import com.quant.stock.risk.LimitBoardHelper;
import com.quant.stock.risk.LiveAccountRiskState;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.risk.RiskControlLogService;
import com.quant.stock.risk.RiskControlService;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.MaCrossStrategy;
import com.quant.stock.trade.LiveLedgerService;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.trade.TradeGatewayService;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.PositionAmountUtil;
import com.quant.stock.util.RedisLockUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final TradingCalendar tradingCalendar;

    @Autowired(required = false)
    private CoreMarketBarService coreMarketBarService;

    @Autowired(required = false)
    private TradePoolService tradePoolService;

    private final ObjectProvider<LiveLedgerService> liveLedgerProvider;
    private final ObjectProvider<RiskControlLogService> riskLogProvider;

    private volatile BigDecimal simCash = new BigDecimal("100000");
    /** 模拟账户初始资金（用于收益率；恢复现金不改此值） */
    private final BigDecimal simInitCash = new BigDecimal("100000");
    private final Map<String, LiveBook> books = new ConcurrentHashMap<String, LiveBook>();
    /** sdk：已报未成的本地待入账（FILLED 后由 syncOrders 落账） */
    private final Map<String, PendingFill> pendingFills = new ConcurrentHashMap<String, PendingFill>();
    private volatile BigDecimal reservedCash = BigDecimal.ZERO;
    private final Map<String, Integer> reservedSellVol = new ConcurrentHashMap<String, Integer>();

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
                        BatchStockBackTestService batchStockBackTestService,
                        TradingCalendar tradingCalendar,
                        ObjectProvider<LiveLedgerService> liveLedgerProvider,
                        ObjectProvider<RiskControlLogService> riskLogProvider) {
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
        this.tradingCalendar = tradingCalendar;
        this.liveLedgerProvider = liveLedgerProvider;
        this.riskLogProvider = riskLogProvider;
    }

    @PostConstruct
    public void init() {
        restoreLedger();
        accountRiskState.reset(simCash);
        log.info("StrategyTask 已就绪（调度由 DynamicScheduleService / sys_schedule_job 控制），模拟现金={}", simCash);
    }

    private void restoreLedger() {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null) {
            return;
        }
        BigDecimal cash = ledger.loadCashOrNull();
        if (cash != null && cash.compareTo(BigDecimal.ZERO) > 0) {
            simCash = cash;
        }
        Map<String, PositionState> loaded = ledger.loadPositions();
        for (Map.Entry<String, PositionState> e : loaded.entrySet()) {
            PositionState src = e.getValue();
            LiveBook book = books.computeIfAbsent(e.getKey(), k -> new LiveBook());
            book.pos.restoreLots(src.snapshotLots(), src.getStopPrice(), src.getHighestSinceEntry());
            tradeGatewayService.restorePositionQty(e.getKey(), book.pos.getShares());
            if (book.pos.hasPosition()) {
                book.pyramidStage = 1;
            }
        }
        log.info("已从库恢复模拟账本: 现金={}, 持仓只数={}", simCash, loaded.size());
    }

    private void persistBook(String code, LiveBook book, OrderDTO order, LocalDate tradeDay, BigDecimal fee) {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null) {
            return;
        }
        try {
            ledger.persistTradeState(simCash, order, tradeDay, fee,
                    code, book == null ? null : book.pos);
        } catch (Exception e) {
            log.warn("账本落库失败 code={} order={}: {}", code,
                    order == null ? null : order.getOrderId(), e.getMessage());
        }
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
                boolean wasHalted = accountRiskState.isHalted();
                accountRiskState.onEquity(tradeDay, equity);
                if (!wasHalted && accountRiskState.isHalted()) {
                    RiskControlLogService riskLog = riskLogProvider.getIfAvailable();
                    if (riskLog != null) {
                        riskLog.record(tradeDay, code, "DRAWDOWN_HALT",
                                accountRiskState.drawdown(equity), "熔断禁开并挂清仓");
                    }
                }
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
        // 先卖后买，避免同日新买批次被立刻卖掉（T+1）
        if (book.pendingSell && book.pos.hasPosition() && book.pendingSellSignalDay != null
                && tradeDay.isAfter(book.pendingSellSignalDay)
                && FillTimingHelper.canFillPendingOnBar(bars, i)) {
            int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
            if (sellable < 100) {
                // 无可卖旧仓，挂单保留至次日
            } else {
                boolean limitDown = openFilterService.isLimitDownAt(bars, i);
                if (limitDown) {
                    if (book.lastLimitDownFailDay == null || !book.lastLimitDownFailDay.equals(tradeDay)) {
                        book.limitDownFailDays++;
                        book.lastLimitDownFailDay = tradeDay;
                    }
                    if (book.limitDownFailDays < LIMIT_DOWN_FORCE_DAYS) {
                        // 跌停暂缓
                    } else {
                        BigDecimal prev = openFilterService.prevTradingDayClose(bars, i);
                        BigDecimal force = LimitBoardHelper.limitDownPrice(prev, code,
                                openFilterService.isSt(code));
                        if (force == null) {
                            force = open;
                        }
                        force = force.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
                        boolean full = sellable >= book.pos.getShares();
                        executeLiveSell(code, book, bars, i, force, sellable, full, tradeDay);
                    }
                } else {
                    boolean full = sellable >= book.pos.getShares();
                    executeLiveSell(code, book, bars, i, open, sellable, full, tradeDay);
                }
            }
        }
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
    }

    private void fillPendingSameBar(String code, LiveBook book, List<BarDTO> bars, int i,
                                    LocalDate tradeDay, BigDecimal close) {
        if (book.pendingSell && book.pos.hasPosition()) {
            int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
            if (sellable >= 100) {
                boolean full = sellable >= book.pos.getShares();
                executeLiveSell(code, book, bars, i, close, sellable, full, tradeDay);
            }
        }
        if (book.pendingBuyVol != null && book.pendingBuyVol >= 100) {
            executeLiveBuy(code, book, bars, i, close, tradeDay);
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
        BigDecimal freeCash = availableCash();
        Map<String, Integer> gatewayPos = tradeGatewayService.queryPositions();
        if (!riskControlService.checkBuy(code, deal, vol, freeCash, posMv, gatewayPos, bars, i)) {
            return;
        }
        if (amount.add(fee).compareTo(freeCash) > 0) {
            return;
        }
        OrderDTO order = tradeGatewayService.placeOrder(code, OrderDTO.Side.BUY, deal, vol);
        if (order == null || order.getStatus() == OrderDTO.Status.REJECTED) {
            return;
        }
        BigDecimal atr = atrAt(IndicatorSignalUtil.precompute(bars), i);
        PendingFill pending = PendingFill.buy(code, vol, deal, amount, fee, tradeDay, pyramid, atr, equity);
        if (order.getStatus() == OrderDTO.Status.FILLED) {
            applyBuyFill(book, order, pending);
        } else if (order.getStatus() == OrderDTO.Status.SUBMITTED) {
            reserveBuy(pending);
            pendingFills.put(order.getOrderId(), pending);
            persistOrderOnly(order, tradeDay, fee);
            log.info("策略买入已报(待同步成交): {} {}@{} x{}", code, order.getOrderId(), deal, vol);
        }
    }

    private void executeLiveSell(String code, LiveBook book, List<BarDTO> bars, int i,
                                 BigDecimal base, int vol, boolean clearAll, LocalDate tradeDay) {
        vol = (vol / 100) * 100;
        if (vol < 100 || !book.pos.hasPosition()) {
            return;
        }
        int freeShares = book.pos.getShares() - reservedSellVol.getOrDefault(code, 0);
        Map<String, Integer> sellablePos = new java.util.HashMap<String, Integer>();
        sellablePos.put(code, freeShares);
        if (!riskControlService.checkSell(code, vol, sellablePos, bars, i)) {
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
        PendingFill pending = PendingFill.sell(code, vol, deal, amount, fee, tradeDay, clearAll, avg, pnl);
        if (order.getStatus() == OrderDTO.Status.FILLED) {
            applySellFill(book, order, pending);
        } else if (order.getStatus() == OrderDTO.Status.SUBMITTED) {
            reserveSell(pending);
            pendingFills.put(order.getOrderId(), pending);
            persistOrderOnly(order, tradeDay, fee);
            log.info("策略卖出已报(待同步成交): {} {}@{} x{}", code, order.getOrderId(), deal, vol);
        }
    }

    public void syncOrders() {
        List<OrderDTO> filled = tradeGatewayService.syncOrderStatus();
        for (OrderDTO order : filled) {
            if (order == null || order.getOrderId() == null) {
                continue;
            }
            PendingFill pending = pendingFills.remove(order.getOrderId());
            if (pending == null) {
                log.warn("同步成交无本地待入账上下文 orderId={}", order.getOrderId());
                continue;
            }
            LiveBook book = books.computeIfAbsent(pending.code, k -> new LiveBook());
            int remain = pending.remainingVol;
            if (remain > 0) {
                if (pending.side == OrderDTO.Side.BUY) {
                    applyBuyFillSlice(book, order, pending, remain);
                } else {
                    applySellFillSlice(book, order, pending, remain);
                }
            }
        }
    }

    /**
     * 撤销 sdk 已报委托：释放预留资金/可卖量，委托置 CANCELLED。
     * 仅在网关撤单成功后才释放预留，避免失败时丢上下文。
     */
    public OrderDTO cancelOrder(String orderId) {
        if (orderId == null || orderId.trim().isEmpty()) {
            return null;
        }
        String id = orderId.trim();
        OrderDTO order = tradeGatewayService.cancelOrder(id);
        if (order == null) {
            log.warn("策略撤单失败（不存在或不可撤） orderId={}", id);
            return null;
        }
        PendingFill pending = pendingFills.remove(id);
        if (pending != null) {
            if (pending.side == OrderDTO.Side.BUY) {
                releaseBuyReserveQty(pending, pending.remainingVol);
            } else {
                releaseSellReserveQty(pending, pending.remainingVol);
            }
            pending.remainingVol = 0;
        }
        persistOrderOnly(order, pending == null ? LocalDate.now() : pending.tradeDay, null);
        log.info("策略撤单: {}", id);
        return order;
    }

    /**
     * 本地部成：网关改仓 + 策略按比例入账；余量仍挂在 pending。
     */
    public OrderDTO applyPartialFill(String orderId, int fillQty) {
        if (orderId == null || fillQty < 100) {
            return null;
        }
        String id = orderId.trim();
        PendingFill pending = pendingFills.get(id);
        OrderDTO order = tradeGatewayService.applyPartialFill(id, fillQty);
        if (order == null) {
            return null;
        }
        if (pending == null) {
            log.warn("部成无本地待入账上下文（可能重启后丢失） orderId={} filled={}",
                    id, order.getFilledVolume());
            return order;
        }
        int filledNow = order.getFilledVolume() == null ? 0 : order.getFilledVolume();
        int already = pending.vol - pending.remainingVol;
        int delta = filledNow - already;
        if (delta < 100) {
            return order;
        }
        delta = Math.min(delta, pending.remainingVol);
        LiveBook book = books.computeIfAbsent(pending.code, k -> new LiveBook());
        if (pending.side == OrderDTO.Side.BUY) {
            applyBuyFillSlice(book, order, pending, delta);
        } else {
            applySellFillSlice(book, order, pending, delta);
        }
        if (pending.remainingVol <= 0 || order.getStatus() == OrderDTO.Status.FILLED) {
            pendingFills.remove(id);
        }
        return order;
    }

    private BigDecimal availableCash() {
        BigDecimal r = reservedCash == null ? BigDecimal.ZERO : reservedCash;
        return simCash.subtract(r);
    }

    private void reserveBuy(PendingFill p) {
        reservedCash = reservedCash.add(p.amount).add(p.fee);
    }

    private void releaseBuyReserveQty(PendingFill p, int qty) {
        if (qty <= 0 || p.vol <= 0) {
            return;
        }
        BigDecimal sliceAmt = p.deal.multiply(BigDecimal.valueOf(qty));
        BigDecimal sliceFee = p.fee.multiply(BigDecimal.valueOf(qty))
                .divide(BigDecimal.valueOf(p.vol), 2, RoundingMode.HALF_UP);
        reservedCash = reservedCash.subtract(sliceAmt).subtract(sliceFee);
        if (reservedCash.compareTo(BigDecimal.ZERO) < 0) {
            reservedCash = BigDecimal.ZERO;
        }
    }

    private void reserveSell(PendingFill p) {
        reservedSellVol.merge(p.code, p.vol, new java.util.function.BiFunction<Integer, Integer, Integer>() {
            @Override
            public Integer apply(Integer a, Integer b) {
                return a + b;
            }
        });
    }

    private void releaseSellReserveQty(PendingFill p, int qty) {
        if (qty <= 0) {
            return;
        }
        Integer cur = reservedSellVol.get(p.code);
        if (cur == null) {
            return;
        }
        int next = cur - qty;
        if (next <= 0) {
            reservedSellVol.remove(p.code);
        } else {
            reservedSellVol.put(p.code, next);
        }
    }

    private void applyBuyFill(LiveBook book, OrderDTO order, PendingFill p) {
        applyBuyFillSlice(book, order, p, p.remainingVol > 0 ? p.remainingVol : p.vol);
    }

    private void applyBuyFillSlice(LiveBook book, OrderDTO order, PendingFill p, int qty) {
        qty = Math.min(qty, p.remainingVol > 0 ? p.remainingVol : p.vol);
        if (qty < 100) {
            return;
        }
        releaseBuyReserveQty(p, qty);
        BigDecimal sliceAmt = p.deal.multiply(BigDecimal.valueOf(qty));
        BigDecimal sliceFee = p.fee.multiply(BigDecimal.valueOf(qty))
                .divide(BigDecimal.valueOf(p.vol), 2, RoundingMode.HALF_UP);
        simCash = simCash.subtract(sliceAmt).subtract(sliceFee);
        book.pos.addBuy(qty, p.deal, sliceFee, p.tradeDay);
        book.pos.raiseStopByCost(p.atr, p.equity,
                quantProperties.getAtrStopMultiplier(), quantProperties.getHardStopCapitalPct());
        if (p.pyramid && p.remainingVol == p.vol) {
            book.pyramidStage++;
        } else if (!p.pyramid) {
            book.pyramidStage = Math.max(book.pyramidStage, 1);
        }
        p.remainingVol -= qty;
        persistBook(p.code, book, order, p.tradeDay, sliceFee);
        log.info("策略买入: {} {}@{} x{} fee={}", p.code, order.getOrderId(), p.deal, qty, sliceFee);
    }

    private void applySellFill(LiveBook book, OrderDTO order, PendingFill p) {
        applySellFillSlice(book, order, p, p.remainingVol > 0 ? p.remainingVol : p.vol);
    }

    private void applySellFillSlice(LiveBook book, OrderDTO order, PendingFill p, int qty) {
        qty = Math.min(qty, p.remainingVol > 0 ? p.remainingVol : p.vol);
        if (qty < 100) {
            return;
        }
        releaseSellReserveQty(p, qty);
        BigDecimal sliceAmt = p.deal.multiply(BigDecimal.valueOf(qty));
        BigDecimal sliceFee = p.fee.multiply(BigDecimal.valueOf(qty))
                .divide(BigDecimal.valueOf(p.vol), 2, RoundingMode.HALF_UP);
        BigDecimal slicePnl = p.deal.subtract(p.avg).multiply(BigDecimal.valueOf(qty)).subtract(sliceFee);
        simCash = simCash.add(sliceAmt).subtract(sliceFee);
        boolean clear = p.clearAll && qty >= book.pos.getShares();
        if (clear || qty >= book.pos.getShares()) {
            book.pos.clear();
            book.pyramidStage = 0;
            book.targetFullVol = 0;
            book.pendingSell = false;
            book.pendingSellSignalDay = null;
            book.limitDownFailDays = 0;
            book.lastLimitDownFailDay = null;
            accountRiskState.onClosedRound(slicePnl.compareTo(BigDecimal.ZERO) > 0, p.tradeDay);
        } else {
            book.pos.removeShares(qty);
            book.pendingSell = true;
            if (book.pendingSellSignalDay == null) {
                book.pendingSellSignalDay = p.tradeDay;
            }
        }
        p.remainingVol -= qty;
        persistBook(p.code, book, order, p.tradeDay, sliceFee);
        log.info("策略卖出: {} {}@{} x{} fee={} pnl={}",
                p.code, order.getOrderId(), p.deal, qty, sliceFee, slicePnl);
    }

    private void persistOrderOnly(OrderDTO order, LocalDate tradeDay, BigDecimal fee) {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null || order == null) {
            return;
        }
        try {
            ledger.upsertOrder(order, tradeDay, fee);
        } catch (Exception e) {
            log.warn("委托落库失败 order={}: {}", order.getOrderId(), e.getMessage());
        }
    }

    /**
     * 收盘清算与 K 线落库：权益日结 + 分钟写入 market_minute + 聚合成日线 market_daily。
     * 更大周期由查询时内存聚合，不再写 legacy stock_bar_*。
     * <p>
     * TODO(api): 真实行情拉取（与 market-collect 同源）；当前 fetch 为 db/mock 回退。
     */
    public void settleAfterClose() {
        LocalDate tradeDay = tradingCalendar.lastTradingDayOnOrBefore(LocalDate.now());
        BigDecimal closeEquity = markEquity(null);
        BigDecimal posMv = calcPositionMv();
        BigDecimal prev = accountRiskState.getPrevCloseEquity();
        BigDecimal dailyPnl = prev == null ? BigDecimal.ZERO : closeEquity.subtract(prev);
        BigDecimal dailyPnlRate = prev != null && prev.compareTo(BigDecimal.ZERO) > 0
                ? dailyPnl.divide(prev, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        accountRiskState.onDayClose(closeEquity);
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger != null) {
            ledger.saveCash(simCash);
            ledger.upsertDailyCashflow(
                    tradeDay,
                    simCash,
                    posMv,
                    closeEquity,
                    accountRiskState.getPeakEquity(),
                    dailyPnl,
                    dailyPnlRate,
                    accountRiskState.drawdown(closeEquity),
                    accountRiskState.getConsecutiveLosses());
        }
        log.info("收盘清算开始 tradeDay={}, 模拟现金={}, 权益={}, 持仓={}",
                tradeDay, simCash, closeEquity, tradeGatewayService.queryPositions());
        if (coreMarketBarService == null) {
            log.info("未启用核心行情表(quant.db-enabled=false)，跳过分钟/日线落库");
            return;
        }
        // TODO(api): 接入真实行情后再做可靠增量拉取
        for (String code : resolveSettleCodes()) {
            try {
                marketDataService.fetchAndPersistMinute(code);
                coreMarketBarService.upsertDailyFromMinutes(code, tradeDay);
            } catch (Exception e) {
                log.warn("收盘落库失败 code={}: {}", code, e.getMessage());
            }
        }
        log.info("收盘清算/日线聚合完成 tradeDay={}", tradeDay);
    }

    /**
     * 盘后扫描：覆盖唯一目标池（与 pool-rebuild 共用锁，避免并发覆盖）。
     */
    public void afterMarketBatchScan() {
        log.info("盘后入池扫描触发（覆盖唯一目标池）");
        if (tradePoolService == null) {
            log.warn("TradePoolService 不可用，回退批量扫描 stock-codes");
            batchStockBackTestService.scanAll();
            return;
        }
        // 与 ScheduleJobHandlers.poolRebuild 同锁键
        if (!redisLockUtil.tryLock("job:pool-rebuild", 600)) {
            throw new IllegalStateException("目标池重建忙，请稍后重试");
        }
        try {
            tradePoolService.rebuildFromUniverse();
        } finally {
            redisLockUtil.unlock("job:pool-rebuild");
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

    public BigDecimal getSimInitCash() {
        return simInitCash;
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
            row.put("pendingBuyVol", book != null ? book.pendingBuyVol : null);
            row.put("pendingSell", book != null && book.pendingSell);
            LocalDate today = LocalDate.now();
            int sellable = book != null && book.pos != null ? book.pos.sellableShares(today) : 0;
            row.put("sellableShares", sellable);
            List<Map<String, Object>> lots = new ArrayList<Map<String, Object>>();
            if (book != null && book.pos != null) {
                for (PositionState.LotView lv : book.pos.snapshotLots()) {
                    Map<String, Object> lot = new LinkedHashMap<String, Object>();
                    lot.put("openDate", lv.openDate == null ? null : lv.openDate.toString());
                    lot.put("shares", lv.shares);
                    lot.put("cost", lv.cost);
                    lot.put("sellable", lv.openDate != null && lv.openDate.isBefore(today));
                    lots.add(lot);
                }
            }
            row.put("lots", lots);
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

    /** sdk 已报未成：同步成交后入账所需上下文 */
    private static final class PendingFill {
        final OrderDTO.Side side;
        final String code;
        final int vol;
        final BigDecimal deal;
        final BigDecimal amount;
        final BigDecimal fee;
        final LocalDate tradeDay;
        final boolean pyramid;
        final BigDecimal atr;
        final BigDecimal equity;
        final boolean clearAll;
        final BigDecimal avg;
        final BigDecimal pnl;
        /** 尚未入账的剩余量 */
        int remainingVol;

        private PendingFill(OrderDTO.Side side, String code, int vol, BigDecimal deal, BigDecimal amount,
                            BigDecimal fee, LocalDate tradeDay, boolean pyramid, BigDecimal atr,
                            BigDecimal equity, boolean clearAll, BigDecimal avg, BigDecimal pnl) {
            this.side = side;
            this.code = code;
            this.vol = vol;
            this.deal = deal;
            this.amount = amount;
            this.fee = fee;
            this.tradeDay = tradeDay;
            this.pyramid = pyramid;
            this.atr = atr == null ? BigDecimal.ZERO : atr;
            this.equity = equity == null ? BigDecimal.ZERO : equity;
            this.clearAll = clearAll;
            this.avg = avg == null ? BigDecimal.ZERO : avg;
            this.pnl = pnl == null ? BigDecimal.ZERO : pnl;
            this.remainingVol = vol;
        }

        static PendingFill buy(String code, int vol, BigDecimal deal, BigDecimal amount, BigDecimal fee,
                               LocalDate tradeDay, boolean pyramid, BigDecimal atr, BigDecimal equity) {
            return new PendingFill(OrderDTO.Side.BUY, code, vol, deal, amount, fee, tradeDay,
                    pyramid, atr, equity, false, null, null);
        }

        static PendingFill sell(String code, int vol, BigDecimal deal, BigDecimal amount, BigDecimal fee,
                                LocalDate tradeDay, boolean clearAll, BigDecimal avg, BigDecimal pnl) {
            return new PendingFill(OrderDTO.Side.SELL, code, vol, deal, amount, fee, tradeDay,
                    false, null, null, clearAll, avg, pnl);
        }
    }
}
