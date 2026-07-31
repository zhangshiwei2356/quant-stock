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
import com.quant.stock.risk.AccountRiskState;
import com.quant.stock.risk.ExitPriority;
import com.quant.stock.risk.AlertSeverity;
import com.quant.stock.risk.LimitBoardHelper;
import com.quant.stock.risk.LimitDownForcePolicy;
import com.quant.stock.risk.LimitPriceProtect;
import com.quant.stock.risk.LiveAccountRiskState;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.risk.RiskAlertService;
import com.quant.stock.risk.RiskControlService;
import com.quant.stock.admin.DataReconcileGateService;
import com.quant.stock.risk.SignalDriftMonitor;
import com.quant.stock.risk.StopFillPrice;
import com.quant.stock.risk.StrategyRetirementService;
import com.quant.stock.risk.StressScenarioService;
import com.quant.stock.risk.StructuralBreakMonitor;
import com.quant.stock.risk.TurnoverGuardService;
import com.quant.stock.risk.IcDecayMonitor;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.StrategyRegistry;
import com.quant.stock.trade.CapacityThrottle;
import com.quant.stock.trade.FillVolumeScale;
import com.quant.stock.trade.LiveLedgerService;
import com.quant.stock.trade.ParticipationCap;
import com.quant.stock.trade.SimCashRestore;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.trade.TradeGatewayService;
import com.quant.stock.trade.dto.OrderDTO;
import com.quant.stock.util.PositionAmountUtil;
import com.quant.stock.util.RedisLockUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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

    private static final int PENDING_BUY_EXPIRE_DAYS = 5;

    private final QuantProperties quantProperties;
    private final MarketDataService marketDataService;
    private final StrategyRegistry strategyRegistry;
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
    private final StrategyRetirementService strategyRetirementService;
    private final RiskAlertService riskAlertService;
    private final StressScenarioService stressScenarioService;
    private final SignalDriftMonitor signalDriftMonitor;
    private final StructuralBreakMonitor structuralBreakMonitor;
    private final DataReconcileGateService dataReconcileGateService;
    private final TurnoverGuardService turnoverGuardService;
    private final IcDecayMonitor icDecayMonitor;

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
                        StrategyRegistry strategyRegistry,
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
                        StrategyRetirementService strategyRetirementService,
                        RiskAlertService riskAlertService,
                        StressScenarioService stressScenarioService,
                        SignalDriftMonitor signalDriftMonitor,
                        StructuralBreakMonitor structuralBreakMonitor,
                        DataReconcileGateService dataReconcileGateService,
                        TurnoverGuardService turnoverGuardService,
                        IcDecayMonitor icDecayMonitor) {
        this.quantProperties = quantProperties;
        this.marketDataService = marketDataService;
        this.strategyRegistry = strategyRegistry;
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
        this.strategyRetirementService = strategyRetirementService;
        this.riskAlertService = riskAlertService;
        this.stressScenarioService = stressScenarioService;
        this.signalDriftMonitor = signalDriftMonitor;
        this.structuralBreakMonitor = structuralBreakMonitor;
        this.dataReconcileGateService = dataReconcileGateService;
        this.turnoverGuardService = turnoverGuardService;
        this.icDecayMonitor = icDecayMonitor;
    }

    /** 应用启动：恢复账本、风控与运行时状态。 */
    @PostConstruct
    public void init() {
        restoreLedger();
        BigDecimal markEq = markEquity(null);
        if (markEq.compareTo(BigDecimal.ZERO) <= 0) {
            markEq = simCash;
        }
        accountRiskState.reset(markEq);
        restoreRuntimeState();
        log.info("StrategyTask 已就绪（调度由 DynamicScheduleService / sys_schedule_job 控制），模拟现金={}, 权益≈{}",
                simCash, markEq);
    }

    private void restoreLedger() {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null) {
            return;
        }
        simCash = SimCashRestore.apply(ledger.loadCashOrNull(), simCash);
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
        restoreOpenOrders(ledger);
        log.info("已从库恢复模拟账本: 现金={}, 持仓只数={}, 未完结委托={}",
                simCash, loaded.size(), pendingFills.size());
    }

    private void restoreOpenOrders(LiveLedgerService ledger) {
        List<LiveLedgerService.OpenOrderRow> open = ledger.loadOpenOrders();
        for (LiveLedgerService.OpenOrderRow row : open) {
            if (row == null || row.order == null || row.order.getOrderId() == null) {
                continue;
            }
            OrderDTO order = row.order;
            tradeGatewayService.restoreOpenOrder(order);
            int vol = order.getVolume() == null ? 0 : order.getVolume();
            int filled = order.getFilledVolume() == null ? 0 : order.getFilledVolume();
            int remain = Math.max(0, vol - filled);
            remain = (remain / 100) * 100;
            if (remain < 100) {
                continue;
            }
            BigDecimal deal = order.getPrice() == null ? BigDecimal.ZERO : order.getPrice();
            BigDecimal amount = deal.multiply(BigDecimal.valueOf(remain));
            LocalDate day = row.signalDate == null ? LocalDate.now() : row.signalDate;
            if (order.getSide() == OrderDTO.Side.BUY) {
                BigDecimal fee = tradeCostModel.buyFee(amount);
                PendingFill pending = PendingFill.buy(order.getStockCode(), remain, deal, amount, fee, day,
                        false, quantProperties.getBaseAtr(), markEquity(null));
                pendingFills.put(order.getOrderId(), pending);
                reserveBuy(pending);
            } else {
                LiveBook book = books.computeIfAbsent(order.getStockCode(), k -> new LiveBook());
                BigDecimal avg = book.pos.getAvgCost();
                BigDecimal fee = tradeCostModel.sellFee(amount, null, day);
                BigDecimal pnl = deal.subtract(avg).multiply(BigDecimal.valueOf(remain)).subtract(fee);
                PendingFill pending = PendingFill.sell(order.getStockCode(), remain, deal, amount, fee, day,
                        remain >= book.pos.getShares(), avg, pnl);
                pendingFills.put(order.getOrderId(), pending);
                reserveSell(pending);
            }
        }
    }

    private void restoreRuntimeState() {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null) {
            return;
        }
        String riskJson = ledger.loadConfigOrNull(LiveLedgerService.KEY_RISK_STATE);
        if (riskJson != null && !riskJson.trim().isEmpty()) {
            try {
                JSONObject obj = JSONUtil.parseObj(riskJson);
                Map<String, String> m = new LinkedHashMap<String, String>();
                for (String k : obj.keySet()) {
                    m.put(k, obj.getStr(k, ""));
                }
                accountRiskState.importState(m);
            } catch (Exception e) {
                log.warn("恢复风控状态失败: {}", e.getMessage());
            }
        }
        String retJson = ledger.loadConfigOrNull(LiveLedgerService.KEY_RETIREMENT);
        if (retJson != null && !retJson.trim().isEmpty()) {
            try {
                JSONObject obj = JSONUtil.parseObj(retJson);
                Map<String, String> m = new LinkedHashMap<String, String>();
                for (String k : obj.keySet()) {
                    m.put(k, obj.getStr(k, ""));
                }
                strategyRetirementService.importState(m);
            } catch (Exception e) {
                log.warn("恢复退役状态失败: {}", e.getMessage());
            }
        }
        String booksJson = ledger.loadConfigOrNull(LiveLedgerService.KEY_BOOKS_META);
        if (booksJson != null && !booksJson.trim().isEmpty()) {
            try {
                JSONObject root = JSONUtil.parseObj(booksJson);
                for (String code : root.keySet()) {
                    JSONObject b = root.getJSONObject(code);
                    if (b == null) {
                        continue;
                    }
                    LiveBook book = books.computeIfAbsent(code, k -> new LiveBook());
                    book.pyramidStage = b.getInt("pyramidStage", book.pyramidStage);
                    book.targetFullVol = b.getInt("targetFullVol", 0);
                    book.pendingSell = b.getBool("pendingSell", false);
                    book.pendingSellReason = b.getStr("pendingSellReason", null);
                    String psd = b.getStr("pendingSellSignalDay", "");
                    book.pendingSellSignalDay = psd == null || psd.isEmpty() ? null : LocalDate.parse(psd);
                    int pbv = b.getInt("pendingBuyVol", 0);
                    book.pendingBuyVol = pbv >= 100 ? pbv : null;
                    book.pendingBuyPyramid = b.getBool("pendingBuyPyramid", false);
                    String pbd = b.getStr("pendingBuySignalDay", "");
                    book.pendingBuySignalDay = pbd == null || pbd.isEmpty() ? null : LocalDate.parse(pbd);
                    book.limitDownFailDays = b.getInt("limitDownFailDays", 0);
                }
            } catch (Exception e) {
                log.warn("恢复挂单/金字塔元数据失败: {}", e.getMessage());
            }
        }
    }

    /** 账户页手动退役/恢复等变更后落库。 */
    public void persistPaperState() {
        persistRuntimeState();
    }

    private void persistRuntimeState() {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null) {
            return;
        }
        try {
            ledger.saveConfig(LiveLedgerService.KEY_RISK_STATE,
                    JSONUtil.toJsonStr(accountRiskState.exportState()), "模拟账户风控快照");
            ledger.saveConfig(LiveLedgerService.KEY_RETIREMENT,
                    JSONUtil.toJsonStr(strategyRetirementService.exportState()), "策略退役快照");
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("_activeStrategy", strategyRegistry.active().name());
            for (Map.Entry<String, LiveBook> e : books.entrySet()) {
                LiveBook book = e.getValue();
                if (book == null) {
                    continue;
                }
                Map<String, Object> b = new LinkedHashMap<String, Object>();
                b.put("pyramidStage", book.pyramidStage);
                b.put("targetFullVol", book.targetFullVol);
                b.put("pendingSell", book.pendingSell);
                b.put("pendingSellReason", book.pendingSellReason);
                b.put("pendingSellSignalDay", book.pendingSellSignalDay == null ? ""
                        : book.pendingSellSignalDay.toString());
                b.put("pendingBuyVol", book.pendingBuyVol == null ? 0 : book.pendingBuyVol);
                b.put("pendingBuyPyramid", book.pendingBuyPyramid);
                b.put("pendingBuySignalDay", book.pendingBuySignalDay == null ? ""
                        : book.pendingBuySignalDay.toString());
                b.put("limitDownFailDays", book.limitDownFailDays);
                meta.put(e.getKey(), b);
            }
            ledger.saveConfig(LiveLedgerService.KEY_BOOKS_META, JSONUtil.toJsonStr(meta), "模拟挂单与金字塔元数据");
        } catch (Exception e) {
            log.warn("落库运行时状态失败: {}", e.getMessage());
        }
    }

    private void persistBook(String code, LiveBook book, OrderDTO order,
                             LocalDate signalDate, LocalDate executionDate, BigDecimal fee) {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null) {
            return;
        }
        try {
            ledger.persistTradeState(simCash, order, signalDate, executionDate, fee,
                    code, book == null ? null : book.pos);
        } catch (Exception e) {
            log.warn("账本落库失败 code={} order={}: {}", code,
                    order == null ? null : order.getOrderId(), e.getMessage());
        }
    }

    /**
     * 实盘分钟扫描并撮合：目标池内标的逐根处理挂买/挂卖/止损与信号。
     *
     * @return false 表示锁忙未执行
     */
    public boolean scanAndTrade() {
        if (!redisLockUtil.tryLock("strategy-scan", 120)) {
            log.warn("scan-and-trade 锁忙，跳过本轮");
            return false;
        }
        try {
            List<String> targets = resolveLiveScanCodes();
            if (targets.isEmpty()) {
                log.warn("目标池为空，跳过分钟扫描（请先执行盘后扫描写入目标池）");
                return true;
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
                    book.stoppedOutToday = false;
                }

                // Step1: 撮合挂单
                fillPending(code, book, bars, i, tradeDay, open);

                // Step2: 老仓止损
                if (book.pos.hasPosition() && quantProperties.isStopLossEnabled()
                        && book.pos.canSellStops(tradeDay)) {
                    book.pos.updateHighest(high);
                    int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
                    if (sellable >= 100) {
                        StopFillPrice.Result stopFill = StopFillPrice.resolve(open, low, book.pos.getStopPrice());
                        if (stopFill.triggered()) {
                            boolean full = sellable >= book.pos.getShares();
                            executeLiveSell(code, book, bars, i, stopFill.fillBase, sellable, full, tradeDay);
                            if (full || !book.pos.hasPosition()) {
                                book.stoppedOutToday = true;
                            } else {
                                BigDecimal atr = atrAt(ind, i);
                                book.pos.raiseStopByCost(atr, markEquity(close),
                                        quantProperties.getAtrStopMultiplier(),
                                        quantProperties.getHardStopCapitalPct());
                            }
                        }
                    }
                } else if (book.pos.hasPosition()) {
                    book.pos.updateHighest(high);
                }

                BigDecimal equity = markEquity(close);
                boolean wasHalted = accountRiskState.isHalted();
                accountRiskState.onEquity(tradeDay, equity);
                if (!wasHalted && accountRiskState.isHalted()) {
                    String rule = AccountRiskState.HALT_DURATION.equals(accountRiskState.getHaltReason())
                            ? "DRAWDOWN_DURATION_HALT" : "DRAWDOWN_HALT";
                    String action = AccountRiskState.HALT_DURATION.equals(accountRiskState.getHaltReason())
                            ? "持续期熔断禁开并挂清仓 underwaterDays=" + accountRiskState.getUnderwaterTradingDays()
                            : "深度熔断禁开并挂清仓";
                    riskAlertService.emit(tradeDay, code, rule, AlertSeverity.CRITICAL,
                            accountRiskState.drawdown(equity), action);
                    strategyRetirementService.onAccountHalt(accountRiskState, tradeDay);
                    if (strategyRetirementService.isRetired()) {
                        riskAlertService.emit(tradeDay, code, "STRATEGY_RETIRED", AlertSeverity.CRITICAL,
                                BigDecimal.valueOf(accountRiskState.getUnderwaterTradingDays()),
                                "持续期熔断触发策略退役");
                    }
                }
                stressScenarioService.evaluateOnBar(code, bars, i, tradeDay,
                        book.limitDownFailDays, accountRiskState.isHalted());
                BigDecimal icSample = signalDriftMonitor.evaluateRollingIc(bars, i, tradeDay);
                if (icSample != null) {
                    icDecayMonitor.onIcSample(tradeDay, icSample);
                }
                structuralBreakMonitor.evaluate(bars, i, tradeDay);
                BigDecimal posScale = accountRiskState.positionScale(equity)
                        .multiply(stressScenarioService.positionScaleMultiplier())
                        .multiply(structuralBreakMonitor.positionScaleMultiplier())
                        .multiply(turnoverGuardService.evaluateAndScale(tradeDay, equity))
                        .multiply(icDecayMonitor.positionScaleMultiplier());
                BigDecimal posMvNow = calcPositionMv();
                BigDecimal singleMv = book.pos.hasPosition()
                        ? close.multiply(BigDecimal.valueOf(book.pos.getShares())) : BigDecimal.ZERO;
                riskAlertService.checkSoftBudget(tradeDay, equity, posMvNow, code, singleMv);
                ExitPriority curExit = ExitPriority.fromReasonLabel(book.pendingSellReason);
                if (book.pos.hasPosition() && accountRiskState.isHalted()
                        && ExitPriority.ACCOUNT_HALT.canRegisterOrPreempt(
                        book.stoppedOutToday, book.pendingSell, curExit)) {
                    book.pendingSell = true;
                    book.pendingSellReason = ExitPriority.ACCOUNT_HALT.getLabel();
                    book.pendingSellSignalDay = tradeDay;
                    curExit = ExitPriority.ACCOUNT_HALT;
                }

                if (book.pos.hasPosition() && quantProperties.getMaxHoldTradingDays() > 0
                        && ExitPriority.TIME_STOP.canRegisterOrPreempt(
                        book.stoppedOutToday, book.pendingSell, curExit)) {
                    int held = tradingCalendar.tradingDaysAfter(book.pos.getEarliestOpenDate(), tradeDay);
                    if (held >= quantProperties.getMaxHoldTradingDays()) {
                        book.pendingSell = true;
                        book.pendingSellReason = ExitPriority.TIME_STOP.getLabel();
                        book.pendingSellSignalDay = tradeDay;
                    }
                }

                // Step4: 信号挂单（当前激活策略）
                BaseStrategy strategy = strategyRegistry.active();
                boolean buySignal = strategy.isBuySignalAt(ind, i);
                boolean sellSignal = strategy.isSellSignalAt(ind, i);

                if (!book.pos.hasPosition() && buySignal && !book.pendingSell && book.pendingBuyVol == null
                        && strategyRetirementService.allowNewOpen()
                        && accountRiskState.allowNewOpen(tradeDay, equity)
                        && !dataReconcileGateService.blockNewOpen()
                        && turnoverGuardService.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0
                        && openFilterService.canOpen(code, bars, i)) {
                    BigDecimal atr = atrAt(ind, i);
                    if (atr.compareTo(quantProperties.getAtrMinThreshold()) > 0) {
                        book.targetFullVol = positionAmountUtil.calcBuyVolume(availableCash(), close, atr, posScale);
                        long advBuy = IndicatorSignalUtil.avgVolume(bars, i, 20);
                        long barVol = barVolume(bars, i);
                        book.targetFullVol = capParticipation(book.targetFullVol, advBuy, equity, barVol);
                        int first = quantProperties.isPyramidEnabled()
                                ? positionAmountUtil.pyramidSlice(book.targetFullVol, 0) : book.targetFullVol;
                        first = capParticipation(first, advBuy, equity, barVol);
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
                        && strategyRetirementService.allowNewOpen()
                        && accountRiskState.allowNewOpen(tradeDay, equity)
                        && !dataReconcileGateService.blockNewOpen()
                        && turnoverGuardService.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0) {
                    int slice = positionAmountUtil.pyramidSlice(book.targetFullVol, book.pyramidStage);
                    long advAdd = IndicatorSignalUtil.avgVolume(bars, i, 20);
                    slice = capParticipation(slice, advAdd, equity, barVolume(bars, i));
                    BigDecimal posMv = calcPositionMv();
                    BigDecimal addMoney = close.multiply(BigDecimal.valueOf(Math.max(slice, 0)));
                    if (slice >= 100 && positionAmountUtil.withinTotalPosition(equity, posMv, addMoney)) {
                        book.pendingBuyVol = slice;
                        book.pendingBuyPyramid = true;
                        book.pendingBuySignalDay = tradeDay;
                    }
                }

                if (book.pos.hasPosition() && sellSignal
                        && ExitPriority.DEATH_CROSS.canRegisterPending(book.stoppedOutToday, book.pendingSell)) {
                    book.pendingSell = true;
                    book.pendingSellReason = ExitPriority.DEATH_CROSS.getLabel();
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
            persistRuntimeState();
            return true;
        } finally {
            redisLockUtil.unlock("strategy-scan");
        }
    }

    private void fillPending(String code, LiveBook book, List<BarDTO> bars, int i,
                             LocalDate tradeDay, BigDecimal open) {
        // 先卖后买，避免同日新买批次被立刻卖掉（T+1）
        if (book.pendingSell && book.pos.hasPosition() && book.pendingSellSignalDay != null
                && tradeDay.isAfter(book.pendingSellSignalDay)
                && FillTimingHelper.canFillPendingOnBar(bars, i)
                && !openFilterService.isSuspended(bars.get(i))) {
            int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
            if (sellable < 100) {
                // 无可卖旧仓，挂单保留至次日
            } else {
                int vol = sellable;
                ExitPriority exitPri = ExitPriority.fromReasonLabel(book.pendingSellReason);
                if (exitPri == null || !exitPri.bypassParticipationCap()) {
                    long adv = IndicatorSignalUtil.avgVolume(bars, i, 20);
                    vol = capParticipation(vol, adv, markEquity(open), barVolume(bars, i));
                }
                if (vol >= 100) {
                    boolean limitDown = openFilterService.isLimitDownAt(bars, i);
                    if (LimitDownForcePolicy.deferForLimitDown(limitDown, book.limitDownFailDays)) {
                        if (book.lastLimitDownFailDay == null || !book.lastLimitDownFailDay.equals(tradeDay)) {
                            book.limitDownFailDays++;
                            book.lastLimitDownFailDay = tradeDay;
                        }
                    } else if (LimitDownForcePolicy.shouldSellNow(limitDown, book.limitDownFailDays)) {
                        BigDecimal fillBase = open;
                        if (limitDown) {
                            BigDecimal prev = openFilterService.prevTradingDayClose(bars, i);
                            BigDecimal force = LimitBoardHelper.limitDownPrice(prev, code,
                                    openFilterService.isSt(code, tradeDay));
                            if (force == null) {
                                force = open;
                            }
                            fillBase = force.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
                        }
                        boolean full = vol >= book.pos.getShares();
                        executeLiveSell(code, book, bars, i, fillBase, vol, full, tradeDay);
                    }
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
        if (book.pendingSell && book.pos.hasPosition()
                && !openFilterService.isSuspended(bars.get(i))) {
            int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
            if (sellable >= 100) {
                int vol = sellable;
                ExitPriority exitPri = ExitPriority.fromReasonLabel(book.pendingSellReason);
                if (exitPri == null || !exitPri.bypassParticipationCap()) {
                    long adv = IndicatorSignalUtil.avgVolume(bars, i, 20);
                    vol = capParticipation(vol, adv, markEquity(close), barVolume(bars, i));
                }
                if (vol >= 100) {
                    boolean full = vol >= book.pos.getShares();
                    executeLiveSell(code, book, bars, i, close, vol, full, tradeDay);
                }
            }
        }
        if (book.pendingBuyVol != null && book.pendingBuyVol >= 100) {
            executeLiveBuy(code, book, bars, i, close, tradeDay);
        }
    }

    private void executeLiveBuy(String code, LiveBook book, List<BarDTO> bars, int i,
                                BigDecimal base, LocalDate tradeDay) {
        int rawVol = book.pendingBuyVol == null ? 0 : book.pendingBuyVol;
        boolean pyramid = book.pendingBuyPyramid;
        LocalDate signalDay = book.pendingBuySignalDay;
        if (rawVol < 100) {
            book.pendingBuyVol = null;
            book.pendingBuyPyramid = false;
            book.pendingBuySignalDay = null;
            return;
        }
        BigDecimal equity = markEquity(bars.get(i).getClose());
        BigDecimal posScale = accountRiskState.positionScale(equity)
                .multiply(stressScenarioService.positionScaleMultiplier())
                .multiply(structuralBreakMonitor.positionScaleMultiplier())
                .multiply(turnoverGuardService.positionScaleMultiplier(tradeDay, equity))
                .multiply(icDecayMonitor.positionScaleMultiplier());
        int vol = FillVolumeScale.scaleToLot(rawVol, posScale);
        if (vol < 100) {
            // 仓位系数缩放后不足1手：取消挂单（对齐单股）
            book.pendingBuyVol = null;
            book.pendingBuyPyramid = false;
            book.pendingBuySignalDay = null;
            return;
        }
        BigDecimal deal = tradeCostModel.buyPrice(base, bars, i, vol);
        if (quantProperties.isLimitPriceProtectEnabled()) {
            deal = LimitPriceProtect.clampBuy(deal, openFilterService.prevTradingDayClose(bars, i),
                    code, openFilterService.isSt(code, tradeDay));
        }
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.buyFee(amount);
        BigDecimal posMv = calcPositionMv();
        BigDecimal freeCash = availableCash();
        Map<String, Integer> gatewayPos = tradeGatewayService.queryPositions();
        if (!riskControlService.checkBuy(code, deal, vol, freeCash, posMv, gatewayPos, bars, i)) {
            // 保留挂买，下一根再试（勿静默丢信号）
            return;
        }
        if (amount.add(fee).compareTo(freeCash) > 0) {
            return;
        }
        OrderDTO order = tradeGatewayService.placeOrder(code, OrderDTO.Side.BUY, deal, vol);
        if (order == null || order.getStatus() == OrderDTO.Status.REJECTED) {
            return;
        }
        // 下单成功后再清挂买意图
        book.pendingBuyVol = null;
        book.pendingBuyPyramid = false;
        book.pendingBuySignalDay = null;
        BigDecimal atr = atrAt(IndicatorSignalUtil.precompute(bars), i);
        LocalDate fillDay = signalDay == null ? tradeDay : signalDay;
        PendingFill pending = PendingFill.buy(code, vol, deal, amount, fee, fillDay, pyramid, atr, equity);
        if (order.getStatus() == OrderDTO.Status.FILLED) {
            applyBuyFill(book, order, pending, tradeDay);
        } else if (order.getStatus() == OrderDTO.Status.SUBMITTED) {
            reserveBuy(pending);
            pendingFills.put(order.getOrderId(), pending);
            persistOrderOnly(order, signalDay == null ? tradeDay : signalDay, null, fee);
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
        if (quantProperties.isLimitPriceProtectEnabled()) {
            deal = LimitPriceProtect.clampSell(deal, openFilterService.prevTradingDayClose(bars, i),
                    code, openFilterService.isSt(code, tradeDay));
        }
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.sellFee(amount, null, tradeDay);
        BigDecimal pnl = deal.subtract(avg).multiply(BigDecimal.valueOf(vol)).subtract(fee);
        OrderDTO order = tradeGatewayService.placeOrder(code, OrderDTO.Side.SELL, deal, vol);
        if (order == null || order.getStatus() == OrderDTO.Status.REJECTED) {
            return;
        }
        PendingFill pending = PendingFill.sell(code, vol, deal, amount, fee, tradeDay, clearAll, avg, pnl);
        if (order.getStatus() == OrderDTO.Status.FILLED) {
            applySellFill(book, order, pending, tradeDay);
        } else if (order.getStatus() == OrderDTO.Status.SUBMITTED) {
            reserveSell(pending);
            pendingFills.put(order.getOrderId(), pending);
            persistOrderOnly(order, tradeDay, null, fee);
            log.info("策略卖出已报(待同步成交): {} {}@{} x{}", code, order.getOrderId(), deal, vol);
        }
    }

    /**
     * sdk 模式：推进网关 SUBMITTED→FILLED 并将待入账写入策略账本。
     *
     * @return false 表示锁忙未执行
     */
    public boolean syncOrders() {
        if (!redisLockUtil.tryLock("strategy-scan", 60)) {
            log.warn("sync-orders 锁忙，跳过本轮");
            return false;
        }
        try {
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
                    LocalDate execDay = LocalDate.now();
                    if (pending.side == OrderDTO.Side.BUY) {
                        applyBuyFillSlice(book, order, pending, remain, execDay);
                    } else {
                        applySellFillSlice(book, order, pending, remain, execDay);
                    }
                }
            }
            persistRuntimeState();
            return true;
        } finally {
            redisLockUtil.unlock("strategy-scan");
        }
    }

    /**
     * 撤销 sdk 已报委托：释放预留资金/可卖量，委托置 CANCELLED。
     * 仅在网关撤单成功后才释放预留，避免失败时丢上下文。
     */
    public OrderDTO cancelOrder(String orderId) {
        if (!redisLockUtil.tryLock("strategy-scan", 60)) {
            log.warn("cancelOrder 锁忙 orderId={}", orderId);
            return null;
        }
        try {
            return cancelOrderUnlocked(orderId);
        } finally {
            redisLockUtil.unlock("strategy-scan");
        }
    }

    private OrderDTO cancelOrderUnlocked(String orderId) {
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
        persistOrderOnly(order, pending == null ? LocalDate.now() : pending.tradeDay, null, null);
        persistRuntimeState();
        log.info("策略撤单: {}", id);
        return order;
    }

    /**
     * 改价=撤补重置队尾（P0-95）：先撤旧单释放预留，再按新价报新单（新 orderId，不保队列优先级）。
     */
    public OrderDTO replaceOrder(String orderId, BigDecimal newPrice, Integer newVolume) {
        if (orderId == null || orderId.trim().isEmpty() || newPrice == null) {
            return null;
        }
        if (!redisLockUtil.tryLock("strategy-scan", 60)) {
            log.warn("replaceOrder 锁忙 orderId={}", orderId);
            return null;
        }
        try {
            return replaceOrderUnlocked(orderId, newPrice, newVolume);
        } finally {
            redisLockUtil.unlock("strategy-scan");
        }
    }

    private OrderDTO replaceOrderUnlocked(String orderId, BigDecimal newPrice, Integer newVolume) {
        String id = orderId.trim();
        PendingFill oldPending = pendingFills.get(id);
        OrderDTO cancelled = cancelOrderUnlocked(id);
        if (cancelled == null) {
            return null;
        }
        int remain = cancelled.getVolume() == null ? 0 : cancelled.getVolume();
        int filled = cancelled.getFilledVolume() == null ? 0 : cancelled.getFilledVolume();
        int leftover = Math.max(0, remain - filled);
        int vol = newVolume == null || newVolume <= 0 ? leftover : newVolume;
        vol = (vol / 100) * 100;
        if (vol < 100) {
            return null;
        }
        String code = cancelled.getStockCode();
        OrderDTO.Side side = cancelled.getSide();
        LocalDate tradeDay = oldPending == null ? LocalDate.now() : oldPending.tradeDay;
        BigDecimal amount = newPrice.multiply(BigDecimal.valueOf(vol));
        OrderDTO neu = tradeGatewayService.placeOrder(code, side, newPrice, vol,
                "RPL-" + System.currentTimeMillis());
        if (neu == null || neu.getStatus() == OrderDTO.Status.REJECTED) {
            return neu;
        }
        LiveBook book = books.computeIfAbsent(code, k -> new LiveBook());
        if (side == OrderDTO.Side.BUY) {
            BigDecimal fee = tradeCostModel.buyFee(amount);
            PendingFill pending = PendingFill.buy(code, vol, newPrice, amount, fee, tradeDay,
                    oldPending != null && oldPending.pyramid,
                    oldPending == null ? BigDecimal.ZERO : oldPending.atr,
                    oldPending == null ? BigDecimal.ZERO : oldPending.equity);
            if (neu.getStatus() == OrderDTO.Status.FILLED) {
                applyBuyFill(book, neu, pending, tradeDay);
            } else if (neu.getStatus() == OrderDTO.Status.SUBMITTED) {
                reserveBuy(pending);
                pendingFills.put(neu.getOrderId(), pending);
                persistOrderOnly(neu, tradeDay, null, fee);
            }
        } else {
            BigDecimal fee = tradeCostModel.sellFee(amount, null, tradeDay);
            BigDecimal avg = oldPending == null ? book.pos.getAvgCost() : oldPending.avg;
            if (avg == null) {
                avg = BigDecimal.ZERO;
            }
            BigDecimal pnl = newPrice.subtract(avg).multiply(BigDecimal.valueOf(vol)).subtract(fee);
            PendingFill pending = PendingFill.sell(code, vol, newPrice, amount, fee, tradeDay,
                    oldPending != null && oldPending.clearAll, avg, pnl);
            if (neu.getStatus() == OrderDTO.Status.FILLED) {
                applySellFill(book, neu, pending, tradeDay);
            } else if (neu.getStatus() == OrderDTO.Status.SUBMITTED) {
                reserveSell(pending);
                pendingFills.put(neu.getOrderId(), pending);
                persistOrderOnly(neu, tradeDay, null, fee);
            }
        }
        log.info("策略改价撤补(队尾重置): {} → {} @{} x{}", id, neu.getOrderId(), newPrice, vol);
        persistRuntimeState();
        return neu;
    }

    /**
     * 本地部成：网关改仓 + 策略按比例入账；余量仍挂在 pending。
     */
    public OrderDTO applyPartialFill(String orderId, int fillQty) {
        if (orderId == null || fillQty < 100) {
            return null;
        }
        if (!redisLockUtil.tryLock("strategy-scan", 60)) {
            log.warn("partial-fill 锁忙 orderId={}", orderId);
            return null;
        }
        try {
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
            LocalDate execDay = LocalDate.now();
            if (pending.side == OrderDTO.Side.BUY) {
                applyBuyFillSlice(book, order, pending, delta, execDay);
            } else {
                applySellFillSlice(book, order, pending, delta, execDay);
            }
            if (pending.remainingVol <= 0 || order.getStatus() == OrderDTO.Status.FILLED) {
                pendingFills.remove(id);
            }
            persistRuntimeState();
            return order;
        } finally {
            redisLockUtil.unlock("strategy-scan");
        }
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

    private void applyBuyFill(LiveBook book, OrderDTO order, PendingFill p, LocalDate executionDate) {
        applyBuyFillSlice(book, order, p, p.remainingVol > 0 ? p.remainingVol : p.vol, executionDate);
    }

    private void applyBuyFillSlice(LiveBook book, OrderDTO order, PendingFill p, int qty,
                                   LocalDate executionDate) {
        qty = Math.min(qty, p.remainingVol > 0 ? p.remainingVol : p.vol);
        if (qty < 100) {
            return;
        }
        LocalDate exec = executionDate == null ? LocalDate.now() : executionDate;
        releaseBuyReserveQty(p, qty);
        BigDecimal sliceAmt = p.deal.multiply(BigDecimal.valueOf(qty));
        BigDecimal sliceFee = p.fee.multiply(BigDecimal.valueOf(qty))
                .divide(BigDecimal.valueOf(p.vol), 2, RoundingMode.HALF_UP);
        simCash = simCash.subtract(sliceAmt).subtract(sliceFee);
        book.pos.addBuy(qty, p.deal, sliceFee, exec);
        book.pos.raiseStopByCost(p.atr, p.equity,
                quantProperties.getAtrStopMultiplier(), quantProperties.getHardStopCapitalPct());
        if (p.pyramid && p.remainingVol == p.vol) {
            book.pyramidStage++;
        } else if (!p.pyramid) {
            book.pyramidStage = Math.max(book.pyramidStage, 1);
        }
        p.remainingVol -= qty;
        turnoverGuardService.recordTrade(exec, sliceAmt);
        persistBook(p.code, book, order, p.tradeDay, exec, sliceFee);
        log.info("策略买入: {} {}@{} x{} fee={} signal={} exec={}",
                p.code, order.getOrderId(), p.deal, qty, sliceFee, p.tradeDay, exec);
    }

    private void applySellFill(LiveBook book, OrderDTO order, PendingFill p, LocalDate executionDate) {
        applySellFillSlice(book, order, p, p.remainingVol > 0 ? p.remainingVol : p.vol, executionDate);
    }

    private void applySellFillSlice(LiveBook book, OrderDTO order, PendingFill p, int qty,
                                    LocalDate executionDate) {
        qty = Math.min(qty, p.remainingVol > 0 ? p.remainingVol : p.vol);
        if (qty < 100) {
            return;
        }
        LocalDate exec = executionDate == null ? LocalDate.now() : executionDate;
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
            book.pendingSellReason = null;
            book.pendingSellSignalDay = null;
            book.limitDownFailDays = 0;
            book.lastLimitDownFailDay = null;
            book.stoppedOutToday = true;
            boolean win = slicePnl.compareTo(BigDecimal.ZERO) > 0;
            accountRiskState.onClosedRound(win, exec);
            signalDriftMonitor.onClosedRound(win, exec);
        } else {
            book.pos.removeShares(qty);
            book.pos.raiseStopByCost(p.atr != null && p.atr.compareTo(BigDecimal.ZERO) > 0
                            ? p.atr : quantProperties.getBaseAtr(),
                    p.equity != null && p.equity.compareTo(BigDecimal.ZERO) > 0
                            ? p.equity : markEquity(null),
                    quantProperties.getAtrStopMultiplier(), quantProperties.getHardStopCapitalPct());
            book.pendingSell = true;
            if (book.pendingSellSignalDay == null) {
                book.pendingSellSignalDay = p.tradeDay;
            }
        }
        p.remainingVol -= qty;
        turnoverGuardService.recordTrade(exec, sliceAmt);
        persistBook(p.code, book, order, p.tradeDay, exec, sliceFee);
        log.info("策略卖出: {} {}@{} x{} fee={} pnl={} signal={} exec={}",
                p.code, order.getOrderId(), p.deal, qty, sliceFee, slicePnl, p.tradeDay, exec);
    }

    private void persistOrderOnly(OrderDTO order, LocalDate signalDate, LocalDate executionDate,
                                  BigDecimal fee) {
        LiveLedgerService ledger = liveLedgerProvider.getIfAvailable();
        if (ledger == null || order == null) {
            return;
        }
        try {
            ledger.upsertOrder(order, signalDate, executionDate, fee);
        } catch (Exception e) {
            log.warn("委托落库失败 order={}: {}", order.getOrderId(), e.getMessage());
        }
    }

    /**
     * 收盘清算与 K 线落库：权益日结 + 1 分钟写入 {@code market_1min}。
     * 更大周期由查询时内存聚合，不再写日线/5 分钟旧表。
     * <p>
     * TODO(api): 真实行情拉取（与 market-collect 同源）；当前 fetch 为 db/mock 回退。
     *
     * @return false 表示锁忙未执行
     */
    public boolean settleAfterClose() {
        if (!redisLockUtil.tryLock("strategy-scan", 120)) {
            log.warn("settle-after-close 锁忙，跳过本轮");
            return false;
        }
        try {
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
            persistRuntimeState();
            log.info("收盘清算开始 tradeDay={}, 模拟现金={}, 权益={}, 持仓={}",
                    tradeDay, simCash, closeEquity, tradeGatewayService.queryPositions());
            if (coreMarketBarService == null) {
                log.info("未启用核心行情表(quant.db-enabled=false)，跳过 1 分钟落库");
                return true;
            }
            // TODO(api): 接入真实行情后再做可靠增量拉取
            for (String code : resolveSettleCodes()) {
                try {
                    marketDataService.fetchAndPersistMinute(code);
                } catch (Exception e) {
                    log.warn("收盘落库失败 code={}: {}", code, e.getMessage());
                }
            }
            log.info("收盘清算/1分钟落库完成 tradeDay={}", tradeDay);
            return true;
        } finally {
            redisLockUtil.unlock("strategy-scan");
        }
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

    /** 模拟账户初始资金（收益率分母；恢复现金不修改此值）。 */
    public BigDecimal getSimInitCash() {
        return simInitCash;
    }

    /** 现金 + 持仓市值 */
    public BigDecimal getMarkEquity() {
        return markEquity(null);
    }

    /** 仅持仓部分市值（不含现金）。 */
    public BigDecimal getPositionMarketValue() {
        return calcPositionMv();
    }

    /**
     * 当前持仓明细：数量优先策略批次账本；网关仅作对照，分歧打标。
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
            // 批次为 T+1 真相源；无账本时回退网关
            int vol = bookVol > 0 ? bookVol : gwVol;
            if (vol <= 0) {
                continue;
            }
            boolean desync = bookVol > 0 && gwVol > 0 && bookVol != gwVol;
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
            row.put("ledgerDesync", desync);
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

    private int capParticipation(int vol, long adv20, BigDecimal equity, long barVol) {
        BigDecimal eff = CapacityThrottle.effectiveMaxParticipation(
                quantProperties.getMaxParticipationAdv(), equity, quantProperties.getCapacityAumBase());
        int capped = ParticipationCap.capVolume(vol, adv20, eff);
        return CapacityThrottle.povCapVolume(capped, barVol, quantProperties.getPovMaxBarVolumePct());
    }

    private static long barVolume(List<BarDTO> bars, int i) {
        if (bars == null || i < 0 || i >= bars.size() || bars.get(i).getVolume() == null) {
            return 0L;
        }
        return bars.get(i).getVolume().longValue();
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

    private BigDecimal atrAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (ind == null || i < 0 || Double.isNaN(ind.atr14[i])) {
            return quantProperties.getBaseAtr();
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
        String pendingSellReason;
        LocalDate pendingSellSignalDay;
        int pyramidStage;
        int targetFullVol;
        int limitDownFailDays;
        LocalDate lastLimitDownFailDay;
        boolean stoppedOutToday;
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
