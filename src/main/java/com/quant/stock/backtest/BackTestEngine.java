package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.AccountRiskState;
import com.quant.stock.risk.AtrRiskReport;
import com.quant.stock.risk.ExitPriority;
import com.quant.stock.risk.LimitBoardHelper;
import com.quant.stock.risk.LimitDownForcePolicy;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.risk.LimitPriceProtect;
import com.quant.stock.risk.StopFillPrice;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.MaCrossStrategy;
import com.quant.stock.risk.StressScenarioService;
import com.quant.stock.risk.StructuralBreakMonitor;
import com.quant.stock.trade.CapacityThrottle;
import com.quant.stock.trade.PartialFillSim;
import com.quant.stock.trade.ParticipationCap;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.util.PositionAmountUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单只回测引擎（五步时序）：
 * <ol>
 *   <li>撮合昨日挂单（日K=开盘；分钟K≥09:45）</li>
 *   <li>仅老仓止损/移动止盈（T+1 分档：今仓不可止损卖）</li>
 *   <li>账户风控快照</li>
 *   <li>收盘信号挂单（金叉/金字塔加仓/死叉）</li>
 *   <li>盘后更新最高价与移动止盈线</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class BackTestEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 买单挂单最长等待日历日，超时取消 */
    private static final int PENDING_BUY_EXPIRE_DAYS = 5;

    private final QuantProperties props;
    private final PositionAmountUtil positionAmountUtil;
    private final MaCrossStrategy maCrossStrategy;
    private final TradeCostModel tradeCostModel;
    private final OpenFilterService openFilterService;
    private final TradingCalendar tradingCalendar;

    public BackTestResult run(String stockCode, List<BarDTO> closedBars, BigDecimal initCapital) {
        return run(stockCode, closedBars, initCapital, props.getFeeRate(), props.getSlipPoint(), maCrossStrategy);
    }

    public BackTestResult run(String stockCode, List<BarDTO> closedBars, BigDecimal initCapital,
                              BigDecimal feeRate, BigDecimal slipPoint, BaseStrategy strategy) {
        String strategyId = strategy == null ? "MaCrossStrategy" : strategy.getClass().getSimpleName();
        final BigDecimal commissionRate = feeRate != null ? feeRate : props.getFeeRate();
        String fingerprint = ConfigFingerprint.of(props, strategyId, commissionRate);
        if (closedBars == null || closedBars.size() < 65 || initCapital == null) {
            BackTestResult empty = BackTestResult.empty(stockCode, initCapital == null ? BigDecimal.ZERO : initCapital);
            empty.setConfigFingerprint(fingerprint);
            return empty;
        }
        // 佣金率：入参优先；实际滑点由 TradeCostModel 分级（quant.slip-*），slipPoint 仅做合法性校验
        if (slipPoint != null && slipPoint.signum() < 0) {
            throw new IllegalArgumentException("slipPoint must be >= 0");
        }

        IndicatorSignalUtil.IndicatorBundle ind = IndicatorSignalUtil.precompute(closedBars);
        AccountRiskState accountRiskState = new AccountRiskState(props);
        accountRiskState.reset(initCapital);

        BigDecimal cash = initCapital;
        PositionState pos = new PositionState();
        int pyramidStage = 0;
        int targetFullVol = 0;
        BigDecimal peakEquity = initCapital;
        BigDecimal maxDrawDown = BigDecimal.ZERO;
        int winTrades = 0;
        int closedRound = 0;

        Integer pendingBuyVol = null;
        boolean pendingBuyPyramid = false;
        LocalDate pendingBuySignalDay = null;
        boolean pendingSell = false;
        String pendingSellReason = null;
        LocalDate pendingSellSignalDay = null;
        int limitDownFailDays = 0;
        LocalDate lastLimitDownFailDay = null;
        boolean stoppedOutToday = false;
        LocalDate currentDay = null;

        List<BackTradeRecord> trades = new ArrayList<BackTradeRecord>();
        List<String> equityTimes = new ArrayList<String>();
        List<BigDecimal> equityCurve = new ArrayList<BigDecimal>();
        List<BackTestResult.MarkPoint> buyMarks = new ArrayList<BackTestResult.MarkPoint>();
        List<BackTestResult.MarkPoint> sellMarks = new ArrayList<BackTestResult.MarkPoint>();
        DecisionAnalysisLog analysis = new DecisionAnalysisLog();

        int startIdx = Math.max(65, 30);
        for (int i = startIdx; i < closedBars.size(); i++) {
            BarDTO bar = closedBars.get(i);
            LocalDate tradeDay = bar.getBarBegin().toLocalDate();
            BigDecimal open = bar.getOpen();
            BigDecimal high = bar.getHigh();
            BigDecimal low = bar.getLow();
            BigDecimal close = bar.getClose();

            if (currentDay == null || !currentDay.equals(tradeDay)) {
                currentDay = tradeDay;
                stoppedOutToday = false;
                pos.clearAddedToday();
            }

            // 买单挂单过期
            if (pendingBuyVol != null && pendingBuySignalDay != null
                    && tradeDay.isAfter(pendingBuySignalDay.plusDays(PENDING_BUY_EXPIRE_DAYS))) {
                Map<String, Object> ed = new LinkedHashMap<String, Object>();
                ed.put("信号日", String.valueOf(pendingBuySignalDay));
                ed.put("挂单股数", pendingBuyVol);
                ed.put("最长等待日历日", PENDING_BUY_EXPIRE_DAYS);
                analysis.expire(stockCode, bar.getBarBegin(),
                        "买单超过信号日+" + PENDING_BUY_EXPIRE_DAYS + "日历日未成交，取消挂单", ed);
                pendingBuyVol = null;
                pendingBuyPyramid = false;
                pendingBuySignalDay = null;
            }

            BigDecimal equity = markEquity(cash, pos, close);
            BigDecimal posScale = resolvePosScale(accountRiskState, equity, closedBars, i);

            // ---- Step1: 撮合挂单（先卖后买） ----
            boolean fillWindow = props.isNextBarOpenFill()
                    && FillTimingHelper.canFillPendingOnBar(closedBars, i);

            if (fillWindow && pendingSell && pos.hasPosition()
                    && isPendingEffective(pendingSellSignalDay, tradeDay)) {
                boolean force = LimitDownForcePolicy.forceSell(limitDownFailDays);
                boolean limitDown = openFilterService.isLimitDownAt(closedBars, i);
                if (LimitDownForcePolicy.deferForLimitDown(limitDown, limitDownFailDays)) {
                    if (lastLimitDownFailDay == null || !lastLimitDownFailDay.equals(tradeDay)) {
                        limitDownFailDays++;
                        lastLimitDownFailDay = tradeDay;
                        Map<String, Object> rd = new LinkedHashMap<String, Object>();
                        rd.put("跌停失败天数", limitDownFailDays);
                        rd.put("阈值", LimitDownForcePolicy.FORCE_DAYS);
                        analysis.reject(stockCode, bar.getBarBegin(), "跌停未能卖出",
                                "相对昨收判定跌停，本日挂单暂缓；连续" + LimitDownForcePolicy.FORCE_DAYS + "日失败后强平", rd);
                    }
                } else {
                    int sellable = (pos.sellableShares(tradeDay) / 100) * 100;
                    if (sellable >= 100) {
                        int vol = sellable;
                        ExitPriority exitPri = ExitPriority.fromReasonLabel(pendingSellReason);
                        if (exitPri == null || !exitPri.bypassParticipationCap()) {
                            long adv = IndicatorSignalUtil.avgVolume(closedBars, i, 20);
                            int capped = capVol(vol, adv, equity, closedBars, i);
                            if (capped < vol) {
                                vol = capped;
                            }
                        }
                        if (vol < 100) {
                            Map<String, Object> rd = new LinkedHashMap<String, Object>();
                            rd.put("原因", "参与率硬顶后不足1手");
                            rd.put("maxParticipationAdv", props.getMaxParticipationAdv());
                            analysis.reject(stockCode, bar.getBarBegin(), "卖单暂缓",
                                    "ADV参与率硬顶后不足1手，挂单保留", rd);
                        } else {
                            boolean fullExit = vol >= pos.getShares();
                            BigDecimal fillBase = open;
                            if (force && limitDown) {
                                BigDecimal prevClose = openFilterService.prevTradingDayClose(closedBars, i);
                                fillBase = approxLimitDownPrice(prevClose, bar)
                                        .multiply(new BigDecimal("0.99"))
                                        .setScale(2, RoundingMode.HALF_UP);
                            }
                            String sellWhy = pendingSellReason == null ? "挂单卖出" : pendingSellReason;
                            if (force && limitDown) {
                                sellWhy = sellWhy + "（跌停连续失败达" + LimitDownForcePolicy.FORCE_DAYS
                                        + "日，按跌停价×0.99强平）";
                            }
                            if (!fullExit && vol < sellable) {
                                sellWhy = sellWhy + "（ADV参与率硬顶部分卖出）";
                            } else if (!fullExit) {
                                sellWhy = sellWhy + "（T+1仅卖可卖旧仓）";
                            }
                            SellOutcome so = executeSell(stockCode, bar, closedBars, i, cash, pos, fillBase, vol, fullExit,
                                    commissionRate, trades, sellMarks);
                            cash = so.cash;
                            if (fullExit) {
                                closedRound++;
                                if (so.win) {
                                    winTrades++;
                                }
                                accountRiskState.onClosedRound(so.win, tradeDay);
                                pyramidStage = 0;
                                targetFullVol = 0;
                                pendingSell = false;
                                pendingSellReason = null;
                                pendingSellSignalDay = null;
                                limitDownFailDays = 0;
                                lastLimitDownFailDay = null;
                            }
                            logLastTrade(analysis, trades, sellWhy + "；次日有效开盘撮合");
                            equity = markEquity(cash, pos, close);
                            posScale = resolvePosScale(accountRiskState, equity, closedBars, i);
                        }
                    }
                }
            }

            if (fillWindow && pendingBuyVol != null && pendingBuyVol >= 100
                    && isPendingEffective(pendingBuySignalDay, tradeDay)) {
                int vol = pendingBuyVol;
                boolean isPyramid = pendingBuyPyramid;

                String rejectWhy = null;
                boolean allow = accountRiskState.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0
                        && !accountRiskState.isHalted();
                if (!allow) {
                    rejectWhy = accountRiskState.isHalted() ? "账户熔断禁开"
                            : "单日亏损/连亏禁开或仓位系数为0";
                }
                if (allow && FillTimingHelper.isMinuteSeries(closedBars, i)
                        && FillTimingHelper.isOpenQuietMinute(bar.getBarBegin())) {
                    allow = false;
                    rejectWhy = "开盘静默时段09:30–09:45禁止新开成交";
                }
                if (allow && !isPyramid
                        && !openFilterService.canExecuteOpenFill(stockCode, closedBars, i)) {
                    allow = false;
                    rejectWhy = "开仓过滤未通过（涨跌停/停牌/流动性/市值/静默或未到有效撮合时点）";
                } else if (allow && isPyramid) {
                    if (openFilterService.isLimitUpAt(closedBars, i)
                            || openFilterService.isSuspended(bar)) {
                        allow = false;
                        rejectWhy = "加仓时涨停或停牌";
                    }
                }
                if (allow) {
                    int rawVol = vol;
                    vol = BigDecimal.valueOf(vol).multiply(posScale).intValue();
                    vol = (vol / 100) * 100;
                    int requestVol = vol;
                    vol = PartialFillSim.fillVolume(vol, props.getBacktestFillRatio());
                    if (vol >= 100) {
                        BigDecimal deal = protectBuyDeal(stockCode, tradeCostModel.buyPrice(open, closedBars, i, vol),
                                closedBars, i);
                        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
                        BigDecimal fee = tradeCostModel.buyFee(amount, commissionRate);
                        BigDecimal posMv = close.multiply(BigDecimal.valueOf(pos.getShares()));
                        if (amount.add(fee).compareTo(cash) <= 0
                                && positionAmountUtil.withinTotalPosition(equity, posMv, amount)) {
                            cash = cash.subtract(amount).subtract(fee);
                            pos.addBuy(vol, deal, fee, tradeDay);
                            BigDecimal atr = atrAt(ind, i);
                            pos.raiseStopByCost(atr, equity, props.getAtrStopMultiplier(),
                                    props.getHardStopCapitalPct());
                            pos.updateHighest(high);
                            trades.add(record(stockCode, "BUY", bar, deal, vol, fee, amount));
                            buyMarks.add(mark(bar, deal));
                            Map<String, Object> bd = DecisionAnalysisLog.indSnapshot(ind, i, close, cash, equity, posScale, atr);
                            bd.put("挂单股数", rawVol);
                            bd.put("本bar请求股数", requestVol);
                            bd.put("实际成交股数", vol);
                            bd.put("成交价(含滑点冲击)", deal);
                            bd.put("成交额", amount);
                            bd.put("佣金", fee);
                            bd.put("金字塔加仓", isPyramid);
                            bd.put("止损价", pos.getStopPrice());
                            bd.put("backtestFillRatio", props.getBacktestFillRatio());
                            bd.put("数量公式", "挂单股×仓位系数×部成比例后取整手；满仓目标=资金×单只上限30%×ATR调节×仓位系数");
                            String fillNote = vol < requestVol ? "；部成残量保留挂单" : "";
                            analysis.fillBuy(stockCode, bar.getBarBegin(),
                                    (isPyramid ? "金字塔加仓成交" : "金叉首开成交") + "；次日有效开盘撮合" + fillNote, bd);
                            if (isPyramid) {
                                pyramidStage++;
                            } else {
                                pyramidStage = Math.max(pyramidStage, 1);
                            }
                            int rem = PartialFillSim.remainder(requestVol, vol);
                            if (rem >= 100) {
                                pendingBuyVol = rem;
                                pendingBuyPyramid = isPyramid;
                                // 保留原信号日，避免部成重置过期时钟
                            } else {
                                pendingBuyVol = null;
                                pendingBuyPyramid = false;
                                pendingBuySignalDay = null;
                            }
                        } else {
                            Map<String, Object> rd = new LinkedHashMap<String, Object>();
                            rd.put("拟买股数", vol);
                            rd.put("所需资金", amount.add(fee));
                            rd.put("可用资金", cash);
                            analysis.reject(stockCode, bar.getBarBegin(), "买单取消",
                                    "现金不足或突破总仓80%上限，取消挂单", rd);
                            pendingBuyVol = null;
                            pendingBuyPyramid = false;
                            pendingBuySignalDay = null;
                        }
                    } else if (requestVol >= 100) {
                        analysis.reject(stockCode, bar.getBarBegin(), "买单暂缓",
                                "部成比例后不足1手，挂单保留", null);
                    } else {
                        analysis.reject(stockCode, bar.getBarBegin(), "买单取消",
                                "仓位系数缩放后不足1手", null);
                        pendingBuyVol = null;
                        pendingBuyPyramid = false;
                        pendingBuySignalDay = null;
                    }
                } else if (rejectWhy != null) {
                    analysis.reject(stockCode, bar.getBarBegin(), "买单未成交", rejectWhy, null);
                }
            }

            equity = markEquity(cash, pos, close);
            posScale = resolvePosScale(accountRiskState, equity, closedBars, i);

            // ---- Step2: 仅老仓止损（分档 T+1） ----
            if (pos.hasPosition() && props.isStopLossEnabled() && pos.canSellStops(tradeDay)) {
                pos.updateHighest(high);
                int sellable = (pos.sellableShares(tradeDay) / 100) * 100;
                StopFillPrice.Result stopFill = StopFillPrice.resolve(open, low, pos.getStopPrice());
                if (sellable >= 100 && stopFill.triggered()) {
                    BigDecimal fillBase = stopFill.fillBase;
                    boolean fullExit = sellable >= pos.getShares();
                    SellOutcome so = executeSell(stockCode, bar, closedBars, i, cash, pos, fillBase, sellable,
                            fullExit, commissionRate, trades, sellMarks);
                    cash = so.cash;
                    Map<String, Object> stopData = lastTradeData(trades);
                    stopData.put("止损线", pos.getStopPrice());
                    stopData.put("成交基准价", fillBase);
                    stopData.put("穿价模式", stopFill.mode.name());
                    stopData.put("跳空穿价", stopFill.mode == StopFillPrice.Mode.GAP_THROUGH);
                    stopData.put("可卖老仓股数", sellable);
                    stopData.put("全仓退出", fullExit);
                    String stopWhy = stopFill.mode == StopFillPrice.Mode.GAP_THROUGH
                            ? (fullExit ? "跳空穿价止损，按开盘价老仓清仓" : "跳空穿价止损，部分卖出老仓")
                            : (fullExit ? "止损/移动止盈触及，老仓清仓" : "止损/移动止盈触及，部分卖出老仓");
                    analysis.stop(stockCode, bar.getBarBegin(), stopWhy, stopData);
                    if (fullExit || !pos.hasPosition()) {
                        closedRound++;
                        if (so.win) {
                            winTrades++;
                        }
                        accountRiskState.onClosedRound(so.win, tradeDay);
                        pyramidStage = 0;
                        targetFullVol = 0;
                        pendingSell = false;
                        pendingSellReason = null;
                        pendingBuyVol = null;
                        pendingBuySignalDay = null;
                        pendingSellSignalDay = null;
                        stoppedOutToday = true;
                        limitDownFailDays = 0;
                        lastLimitDownFailDay = null;
                    } else {
                        // 部分止损：保留今仓，按剩余仓位重算止损线
                        pos.raiseStopByCost(atrAt(ind, i), markEquity(cash, pos, close),
                                props.getAtrStopMultiplier(), props.getHardStopCapitalPct());
                    }
                }
            } else if (pos.hasPosition()) {
                pos.updateHighest(high);
            }

            equity = markEquity(cash, pos, close);

            // ---- Step3: 账户风控快照 ----
            accountRiskState.onEquity(tradeDay, equity);
            posScale = resolvePosScale(accountRiskState, equity, closedBars, i);
            ExitPriority curExit = ExitPriority.fromReasonLabel(pendingSellReason);
            if (pos.hasPosition() && accountRiskState.isHalted()) {
                if (ExitPriority.ACCOUNT_HALT.canRegisterOrPreempt(stoppedOutToday, pendingSell, curExit)) {
                    pendingSell = true;
                    pendingSellReason = ExitPriority.ACCOUNT_HALT.getLabel();
                    pendingSellSignalDay = tradeDay;
                    curExit = ExitPriority.ACCOUNT_HALT;
                    Map<String, Object> rd = new LinkedHashMap<String, Object>();
                    rd.put("权益", equity.setScale(2, RoundingMode.HALF_UP));
                    rd.put("仓位系数", posScale);
                    rd.put("退出优先级", ExitPriority.ACCOUNT_HALT.getRank());
                    analysis.risk(stockCode, bar.getBarBegin(), "账户回撤熔断，挂清仓卖单且禁新开", rd);
                }
            }

            // ---- Step3b: 最大持仓日（时间止损）----
            if (pos.hasPosition() && props.getMaxHoldTradingDays() > 0
                    && ExitPriority.TIME_STOP.canRegisterOrPreempt(stoppedOutToday, pendingSell, curExit)) {
                LocalDate openDay = pos.getEarliestOpenDate();
                int held = tradingCalendar.tradingDaysAfter(openDay, tradeDay);
                if (held >= props.getMaxHoldTradingDays()) {
                    pendingSell = true;
                    pendingSellReason = ExitPriority.TIME_STOP.getLabel();
                    pendingSellSignalDay = tradeDay;
                    Map<String, Object> td = new LinkedHashMap<String, Object>();
                    td.put("开仓日", openDay == null ? null : openDay.toString());
                    td.put("持仓交易日", held);
                    td.put("阈值", props.getMaxHoldTradingDays());
                    td.put("退出优先级", ExitPriority.TIME_STOP.getRank());
                    analysis.risk(stockCode, bar.getBarBegin(), "持仓达最大交易日，挂时间止损清仓", td);
                }
            }

            // ---- Step4: 收盘信号 ----
            boolean buySignal = maCrossStrategy.isBuySignalAt(ind, i);
            boolean sellSignal = ind.isMaCrossDown(i);

            if (!pos.hasPosition() && buySignal && !pendingSell && pendingBuyVol == null
                    && accountRiskState.allowNewOpen(tradeDay, equity)
                    && posScale.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal atr = atrAt(ind, i);
                if (atr.compareTo(props.getAtrMinThreshold()) > 0
                        && openFilterService.canOpen(stockCode, closedBars, i)) {
                    targetFullVol = positionAmountUtil.calcBuyVolume(cash, close, atr, posScale);
                    long advBuy = IndicatorSignalUtil.avgVolume(closedBars, i, 20);
                    targetFullVol = capVol(targetFullVol, advBuy, equity, closedBars, i);
                    int first = props.isPyramidEnabled()
                            ? positionAmountUtil.pyramidSlice(targetFullVol, 0) : targetFullVol;
                    first = capVol(first, advBuy, equity, closedBars, i);
                    if (first >= 100) {
                        pendingBuyVol = first;
                        pendingBuyPyramid = false;
                        pendingBuySignalDay = tradeDay;
                        Map<String, Object> sd = DecisionAnalysisLog.indSnapshot(ind, i, close, cash, equity, posScale, atr);
                        sd.put("满仓目标股数", targetFullVol);
                        sd.put("挂单股数", first);
                        sd.put("ADV20", advBuy);
                        sd.put("maxParticipationAdv", props.getMaxParticipationAdv());
                        sd.put("金字塔开启", props.isPyramidEnabled());
                        sd.put("数量公式", props.isPyramidEnabled()
                                ? "满仓目标=可用资金×单只上限30%×ATR调节(0.2~1.5)×仓位系数，整手；再×ADV参与率硬顶；首批=满仓目标×50%"
                                : "满仓目标=可用资金×单只上限30%×ATR调节(0.2~1.5)×仓位系数，整手；再×ADV参与率硬顶");
                        analysis.signalBuy(stockCode, bar.getBarBegin(),
                                "金叉且通过开仓过滤，挂次日有效开盘买单", sd);
                    }
                }
            } else if (pos.hasPosition() && props.isPyramidEnabled()
                    && pyramidStage >= 1 && pyramidStage < 3
                    && pendingBuyVol == null
                    && !pos.isAddedToday()
                    && close.compareTo(pos.getAvgCost().multiply(BigDecimal.ONE.add(props.getPyramidAddPct()))) >= 0
                    && ind.ma5[i] > ind.ma20[i]
                    && accountRiskState.allowNewOpen(tradeDay, equity)
                    && posScale.compareTo(BigDecimal.ZERO) > 0) {
                int slice = positionAmountUtil.pyramidSlice(targetFullVol, pyramidStage);
                long advAdd = IndicatorSignalUtil.avgVolume(closedBars, i, 20);
                slice = capVol(slice, advAdd, equity, closedBars, i);
                BigDecimal posMv = close.multiply(BigDecimal.valueOf(pos.getShares()));
                BigDecimal addMoney = close.multiply(BigDecimal.valueOf(Math.max(slice, 0)));
                if (slice >= 100 && positionAmountUtil.withinTotalPosition(equity, posMv, addMoney)) {
                    pendingBuyVol = slice;
                    pendingBuyPyramid = true;
                    pendingBuySignalDay = tradeDay;
                    BigDecimal atr = atrAt(ind, i);
                    Map<String, Object> pd = DecisionAnalysisLog.indSnapshot(ind, i, close, cash, equity, posScale, atr);
                    pd.put("金字塔档位", pyramidStage);
                    pd.put("满仓目标股数", targetFullVol);
                    pd.put("挂单股数", slice);
                    pd.put("ADV20", advAdd);
                    pd.put("maxParticipationAdv", props.getMaxParticipationAdv());
                    pd.put("综合成本", pos.getAvgCost());
                    pd.put("数量公式", pyramidStage == 1
                            ? "第2批=满仓目标×30%，成交后占档"
                            : "第3批=满仓目标×20%，成交后占档");
                    analysis.signalPyramid(stockCode, bar.getBarBegin(),
                            "浮盈达标且均线多头，挂金字塔加仓买单", pd);
                    // 档位在成交后递增
                }
            }

            if (pos.hasPosition() && sellSignal
                    && ExitPriority.DEATH_CROSS.canRegisterPending(stoppedOutToday, pendingSell)) {
                pendingSell = true;
                pendingSellReason = ExitPriority.DEATH_CROSS.getLabel();
                pendingSellSignalDay = tradeDay;
                Map<String, Object> sd = DecisionAnalysisLog.indSnapshot(ind, i, close, cash, equity, posScale, atrAt(ind, i));
                sd.put("持仓股数", pos.getShares());
                sd.put("退出优先级", ExitPriority.DEATH_CROSS.getRank());
                analysis.signalSell(stockCode, bar.getBarBegin(), "死叉，挂次日有效开盘全仓卖出", sd);
            }

            // 非 nextBar 兼容：当根收盘撮合
            if (!props.isNextBarOpenFill()) {
                if (pendingBuyVol != null && pendingBuyVol >= 100) {
                    int requestVol = pendingBuyVol;
                    boolean isPyramid = pendingBuyPyramid;
                    int vol = PartialFillSim.fillVolume(requestVol, props.getBacktestFillRatio());
                    if (vol >= 100) {
                        BigDecimal deal = protectBuyDeal(stockCode,
                                tradeCostModel.buyPrice(close, closedBars, i, vol), closedBars, i);
                        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
                        BigDecimal fee = tradeCostModel.buyFee(amount, commissionRate);
                        if (amount.add(fee).compareTo(cash) <= 0) {
                            cash = cash.subtract(amount).subtract(fee);
                            pos.addBuy(vol, deal, fee, tradeDay);
                            pos.raiseStopByCost(atrAt(ind, i), equity, props.getAtrStopMultiplier(),
                                    props.getHardStopCapitalPct());
                            trades.add(record(stockCode, "BUY", bar, deal, vol, fee, amount));
                            buyMarks.add(mark(bar, deal));
                            logLastTrade(analysis, trades, isPyramid ? "金字塔加仓成交；当根收盘撮合" : "金叉首开成交；当根收盘撮合");
                            if (isPyramid) {
                                pyramidStage++;
                            } else {
                                pyramidStage = Math.max(pyramidStage, 1);
                            }
                            int rem = PartialFillSim.remainder(requestVol, vol);
                            if (rem >= 100) {
                                pendingBuyVol = rem;
                                pendingBuyPyramid = isPyramid;
                            } else {
                                pendingBuyVol = null;
                                pendingBuyPyramid = false;
                                pendingBuySignalDay = null;
                            }
                        } else {
                            pendingBuyVol = null;
                            pendingBuyPyramid = false;
                            pendingBuySignalDay = null;
                        }
                    }
                }
                if (pendingSell && pos.hasPosition()) {
                    int sellable = (pos.sellableShares(tradeDay) / 100) * 100;
                    if (sellable >= 100) {
                        int vol = sellable;
                        ExitPriority exitPri = ExitPriority.fromReasonLabel(pendingSellReason);
                        if (exitPri == null || !exitPri.bypassParticipationCap()) {
                            long adv = IndicatorSignalUtil.avgVolume(closedBars, i, 20);
                            vol = capVol(vol, adv, equity, closedBars, i);
                        }
                        if (vol >= 100) {
                            boolean fullExit = vol >= pos.getShares();
                            String sellWhy = pendingSellReason == null ? "挂单卖出" : pendingSellReason;
                            if (!fullExit && vol < sellable) {
                                sellWhy = sellWhy + "（ADV参与率硬顶部分卖出）";
                            } else if (!fullExit) {
                                sellWhy = sellWhy + "（T+1仅卖可卖旧仓）";
                            }
                            SellOutcome so = executeSell(stockCode, bar, closedBars, i, cash, pos, close, vol, fullExit,
                                    commissionRate, trades, sellMarks);
                            cash = so.cash;
                            if (fullExit) {
                                closedRound++;
                                if (so.win) {
                                    winTrades++;
                                }
                                accountRiskState.onClosedRound(so.win, tradeDay);
                                pyramidStage = 0;
                                targetFullVol = 0;
                                pendingSell = false;
                                pendingSellReason = null;
                                pendingSellSignalDay = null;
                            }
                            logLastTrade(analysis, trades, sellWhy + "；当根收盘撮合");
                        }
                    }
                }
            }

            equity = markEquity(cash, pos, close);

            // ---- Step5: 盘后更新 trail；日末固化昨收权益 ----
            if (pos.hasPosition() && props.isTrailingStopEnabled()) {
                pos.raiseTrailingStop(atrAt(ind, i), props.getTrailingAtrMultiplier());
            }
            boolean dayEnd = (i == closedBars.size() - 1)
                    || !closedBars.get(i + 1).getBarBegin().toLocalDate().equals(tradeDay);
            if (dayEnd) {
                accountRiskState.onDayClose(equity);
            }

            if (i % 5 == 0 || i == closedBars.size() - 1) {
                equityTimes.add(bar.getBarBegin().format(FMT));
                equityCurve.add(equity.setScale(2, RoundingMode.HALF_UP));
            }
            if (equity.compareTo(peakEquity) > 0) {
                peakEquity = equity;
            }
            if (peakEquity.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dd = peakEquity.subtract(equity).divide(peakEquity, 6, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDrawDown) > 0) {
                    maxDrawDown = dd;
                }
            }
        }

        BarDTO last = closedBars.get(closedBars.size() - 1);
        BigDecimal finalAsset = markEquity(cash, pos, last.getClose());
        BigDecimal totalRate = finalAsset.subtract(initCapital).divide(initCapital, 6, RoundingMode.HALF_UP);
        BigDecimal winRate = closedRound == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(winTrades).divide(BigDecimal.valueOf(closedRound), 4, RoundingMode.HALF_UP);

        return BackTestResult.builder()
                .stockCode(stockCode)
                .initCapital(initCapital)
                .finalAsset(finalAsset.setScale(2, RoundingMode.HALF_UP))
                .totalRate(totalRate)
                .maxDrawDown(maxDrawDown)
                .totalTradeNum(trades.size())
                .winRate(winRate)
                .trades(trades)
                .equityTimes(equityTimes)
                .equityCurve(equityCurve)
                .buyMarks(buyMarks)
                .sellMarks(sellMarks)
                .analysisEvents(analysis.events())
                .analysisSummary(analysis.summary())
                .configFingerprint(fingerprint)
                .atrRisk(AtrRiskReport.from(props, analysis))
                .build();
    }

    private void logLastTrade(DecisionAnalysisLog analysis, List<BackTradeRecord> trades, String reason) {
        if (analysis == null || trades == null || trades.isEmpty()) {
            return;
        }
        BackTradeRecord t = trades.get(trades.size() - 1);
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("side", t.getSide());
        d.put("price", t.getPrice());
        d.put("volume", t.getVolume());
        d.put("amount", t.getAmount());
        d.put("fee", t.getFee());
        if ("BUY".equalsIgnoreCase(t.getSide())) {
            analysis.fillBuy(t.getStockCode(), t.getTradeTime(), reason, d);
        } else {
            analysis.fillSell(t.getStockCode(), t.getTradeTime(), reason, d);
        }
    }

    private Map<String, Object> lastTradeData(List<BackTradeRecord> trades) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        if (trades == null || trades.isEmpty()) {
            return d;
        }
        BackTradeRecord t = trades.get(trades.size() - 1);
        d.put("side", t.getSide());
        d.put("price", t.getPrice());
        d.put("volume", t.getVolume());
        d.put("amount", t.getAmount());
        d.put("fee", t.getFee());
        return d;
    }

    private boolean isPendingEffective(LocalDate signalDay, LocalDate tradeDay) {
        if (tradeDay == null) {
            return false;
        }
        if (signalDay == null) {
            return true;
        }
        return tradeDay.isAfter(signalDay);
    }

    private BigDecimal markEquity(BigDecimal cash, PositionState pos, BigDecimal markPrice) {
        if (!pos.hasPosition() || markPrice == null) {
            return cash;
        }
        return cash.add(markPrice.multiply(BigDecimal.valueOf(pos.getShares())));
    }

    private BigDecimal atrAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (Double.isNaN(ind.atr14[i])) {
            return props.getBaseAtr();
        }
        return BigDecimal.valueOf(ind.atr14[i]);
    }

    private BigDecimal approxLimitDownPrice(BigDecimal prevClose, BarDTO cur) {
        String code = cur != null ? cur.getCode() : null;
        BigDecimal lim = LimitBoardHelper.limitDownPrice(prevClose, code);
        if (lim != null) {
            return lim;
        }
        return cur != null && cur.getOpen() != null ? cur.getOpen() : (cur != null ? cur.getClose() : BigDecimal.ZERO);
    }

    private BigDecimal resolvePosScale(AccountRiskState risk, BigDecimal equity,
                                       List<BarDTO> bars, int index) {
        BigDecimal scale = risk.positionScale(equity);
        if (props.isStressScenarioEnabled()
                && StressScenarioService.isAdvCliff(bars, index, props.getStressAdvCliffRatio())) {
            scale = scale.multiply(new BigDecimal("0.5"));
        }
        if (props.isStructuralBreakEnabled()
                && StructuralBreakMonitor.crossesThreshold(bars, index,
                props.getStructuralBreakWindow(), props.getStructuralBreakThreshold())) {
            scale = scale.multiply(new BigDecimal("0.5"));
        }
        return scale;
    }

    private BigDecimal effAdv(BigDecimal equity) {
        return CapacityThrottle.effectiveMaxParticipation(
                props.getMaxParticipationAdv(), equity, props.getCapacityAumBase());
    }

    private int capVol(int vol, long adv20, BigDecimal equity, List<BarDTO> bars, int index) {
        long barVol = 0L;
        if (bars != null && index >= 0 && index < bars.size() && bars.get(index).getVolume() != null) {
            barVol = bars.get(index).getVolume().longValue();
        }
        int capped = ParticipationCap.capVolume(vol, adv20, effAdv(equity));
        return CapacityThrottle.povCapVolume(capped, barVol, props.getPovMaxBarVolumePct());
    }

    private BigDecimal protectBuyDeal(String code, BigDecimal deal, List<BarDTO> bars, int index) {
        if (!props.isLimitPriceProtectEnabled() || deal == null) {
            return deal;
        }
        BigDecimal prev = openFilterService.prevTradingDayClose(bars, index);
        LocalDate asOf = bars != null && index >= 0 && index < bars.size() && bars.get(index).getBarBegin() != null
                ? bars.get(index).getBarBegin().toLocalDate() : null;
        return LimitPriceProtect.clampBuy(deal, prev, code, openFilterService.isSt(code, asOf));
    }

    private BigDecimal protectSellDeal(String code, BigDecimal deal, List<BarDTO> bars, int index) {
        if (!props.isLimitPriceProtectEnabled() || deal == null) {
            return deal;
        }
        BigDecimal prev = openFilterService.prevTradingDayClose(bars, index);
        LocalDate asOf = bars != null && index >= 0 && index < bars.size() && bars.get(index).getBarBegin() != null
                ? bars.get(index).getBarBegin().toLocalDate() : null;
        return LimitPriceProtect.clampSell(deal, prev, code, openFilterService.isSt(code, asOf));
    }

    private SellOutcome executeSell(String stockCode, BarDTO bar, List<BarDTO> bars, int index,
                                    BigDecimal cash, PositionState pos, BigDecimal fillBase, int vol,
                                    boolean clearAll, BigDecimal commissionRate,
                                    List<BackTradeRecord> trades, List<BackTestResult.MarkPoint> sellMarks) {
        BigDecimal avg = pos.getAvgCost();
        BigDecimal deal = protectSellDeal(stockCode, tradeCostModel.sellPrice(fillBase, bars, index, vol), bars, index);
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        LocalDate tradeDay = bar != null && bar.getBarBegin() != null ? bar.getBarBegin().toLocalDate() : null;
        BigDecimal fee = tradeCostModel.sellFee(amount, commissionRate, tradeDay);
        BigDecimal pnl = deal.subtract(avg).multiply(BigDecimal.valueOf(vol)).subtract(fee);
        BigDecimal newCash = cash.add(amount).subtract(fee);
        trades.add(record(stockCode, "SELL", bar, deal, vol, fee, amount));
        sellMarks.add(mark(bar, deal));
        if (clearAll || vol >= pos.getShares()) {
            pos.clear();
        } else {
            pos.removeShares(vol);
        }
        SellOutcome out = new SellOutcome();
        out.cash = newCash;
        out.win = pnl.compareTo(BigDecimal.ZERO) > 0;
        return out;
    }

    private BackTradeRecord record(String code, String side, BarDTO bar, BigDecimal price,
                                   int vol, BigDecimal fee, BigDecimal amount) {
        return BackTradeRecord.builder()
                .stockCode(code)
                .side(side)
                .tradeTime(bar.getBarBegin())
                .price(price)
                .volume(vol)
                .fee(fee)
                .amount(amount)
                .build();
    }

    private BackTestResult.MarkPoint mark(BarDTO bar, BigDecimal price) {
        return BackTestResult.MarkPoint.builder()
                .time(bar.getBarBegin().format(FMT))
                .price(price)
                .build();
    }

    private static class SellOutcome {
        BigDecimal cash;
        boolean win;
    }
}
