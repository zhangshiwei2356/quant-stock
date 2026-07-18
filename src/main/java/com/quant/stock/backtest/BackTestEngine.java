package com.quant.stock.backtest;

import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.AccountRiskState;
import com.quant.stock.risk.LimitBoardHelper;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.MaCrossStrategy;
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
    private static final int LIMIT_DOWN_FORCE_DAYS = 3;
    /** 买单挂单最长等待日历日，超时取消 */
    private static final int PENDING_BUY_EXPIRE_DAYS = 5;

    private final QuantProperties props;
    private final PositionAmountUtil positionAmountUtil;
    private final MaCrossStrategy maCrossStrategy;
    private final TradeCostModel tradeCostModel;
    private final OpenFilterService openFilterService;

    public BackTestResult run(String stockCode, List<BarDTO> closedBars, BigDecimal initCapital) {
        return run(stockCode, closedBars, initCapital, props.getFeeRate(), props.getSlipPoint(), maCrossStrategy);
    }

    public BackTestResult run(String stockCode, List<BarDTO> closedBars, BigDecimal initCapital,
                              BigDecimal feeRate, BigDecimal slipPoint, BaseStrategy strategy) {
        if (closedBars == null || closedBars.size() < 65 || initCapital == null) {
            return BackTestResult.empty(stockCode, initCapital == null ? BigDecimal.ZERO : initCapital);
        }
        // 佣金率：入参优先；实际滑点由 TradeCostModel 分级（quant.slip-*），slipPoint 仅做合法性校验
        final BigDecimal commissionRate = feeRate != null ? feeRate : props.getFeeRate();
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
            BigDecimal posScale = accountRiskState.positionScale(equity);

            // ---- Step1: 撮合挂单（先卖后买） ----
            boolean fillWindow = props.isNextBarOpenFill()
                    && FillTimingHelper.canFillPendingOnBar(closedBars, i);

            if (fillWindow && pendingSell && pos.hasPosition()
                    && isPendingEffective(pendingSellSignalDay, tradeDay)) {
                boolean force = limitDownFailDays >= LIMIT_DOWN_FORCE_DAYS;
                boolean limitDown = openFilterService.isLimitDownAt(closedBars, i);
                if (limitDown && !force) {
                    if (lastLimitDownFailDay == null || !lastLimitDownFailDay.equals(tradeDay)) {
                        limitDownFailDays++;
                        lastLimitDownFailDay = tradeDay;
                        Map<String, Object> rd = new LinkedHashMap<String, Object>();
                        rd.put("跌停失败天数", limitDownFailDays);
                        rd.put("阈值", LIMIT_DOWN_FORCE_DAYS);
                        analysis.reject(stockCode, bar.getBarBegin(), "跌停未能卖出",
                                "相对昨收判定跌停，本日挂单暂缓；连续" + LIMIT_DOWN_FORCE_DAYS + "日失败后强平", rd);
                    }
                } else {
                    int vol = pos.getShares();
                    BigDecimal fillBase = open;
                    if (force && limitDown) {
                        BigDecimal prevClose = openFilterService.prevTradingDayClose(closedBars, i);
                        fillBase = approxLimitDownPrice(prevClose, bar)
                                .multiply(new BigDecimal("0.99"))
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                    String sellWhy = pendingSellReason == null ? "挂单卖出" : pendingSellReason;
                    if (force && limitDown) {
                        sellWhy = sellWhy + "（跌停连续失败达" + LIMIT_DOWN_FORCE_DAYS + "日，按跌停价×0.99强平）";
                    }
                    SellOutcome so = executeSell(stockCode, bar, closedBars, i, cash, pos, fillBase, vol, true,
                            commissionRate, trades, sellMarks);
                    cash = so.cash;
                    closedRound++;
                    if (so.win) {
                        winTrades++;
                    }
                    accountRiskState.onClosedRound(so.win, tradeDay);
                    logLastTrade(analysis, trades, sellWhy + "；次日有效开盘撮合");
                    pyramidStage = 0;
                    targetFullVol = 0;
                    pendingSell = false;
                    pendingSellReason = null;
                    pendingSellSignalDay = null;
                    limitDownFailDays = 0;
                    lastLimitDownFailDay = null;
                    equity = markEquity(cash, pos, close);
                    posScale = accountRiskState.positionScale(equity);
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
                    if (vol >= 100) {
                        BigDecimal deal = tradeCostModel.buyPrice(open, closedBars, i, vol);
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
                            bd.put("实际成交股数", vol);
                            bd.put("成交价(含滑点冲击)", deal);
                            bd.put("成交额", amount);
                            bd.put("佣金", fee);
                            bd.put("金字塔加仓", isPyramid);
                            bd.put("止损价", pos.getStopPrice());
                            bd.put("数量公式", "挂单股×仓位系数后取整手；满仓目标=资金×单只上限30%×ATR调节×仓位系数");
                            analysis.fillBuy(stockCode, bar.getBarBegin(),
                                    (isPyramid ? "金字塔加仓成交" : "金叉首开成交") + "；次日有效开盘撮合", bd);
                            if (isPyramid) {
                                pyramidStage++;
                            } else {
                                pyramidStage = Math.max(pyramidStage, 1);
                            }
                            pendingBuyVol = null;
                            pendingBuyPyramid = false;
                            pendingBuySignalDay = null;
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
            posScale = accountRiskState.positionScale(equity);

            // ---- Step2: 仅老仓止损（分档 T+1） ----
            if (pos.hasPosition() && props.isStopLossEnabled() && pos.canSellStops(tradeDay)) {
                pos.updateHighest(high);
                int sellable = (pos.sellableShares(tradeDay) / 100) * 100;
                if (sellable >= 100
                        && pos.getStopPrice().compareTo(BigDecimal.ZERO) > 0
                        && low.compareTo(pos.getStopPrice()) <= 0) {
                    BigDecimal fillBase = pos.getStopPrice().max(low);
                    boolean fullExit = sellable >= pos.getShares();
                    SellOutcome so = executeSell(stockCode, bar, closedBars, i, cash, pos, fillBase, sellable,
                            fullExit, commissionRate, trades, sellMarks);
                    cash = so.cash;
                    Map<String, Object> stopData = lastTradeData(trades);
                    stopData.put("止损价", fillBase);
                    stopData.put("可卖老仓股数", sellable);
                    stopData.put("全仓退出", fullExit);
                    analysis.stop(stockCode, bar.getBarBegin(),
                            fullExit ? "止损/移动止盈触及，老仓清仓" : "止损/移动止盈触及，部分卖出老仓", stopData);
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
            posScale = accountRiskState.positionScale(equity);
            if (pos.hasPosition() && accountRiskState.isHalted()) {
                if (!pendingSell) {
                    pendingSell = true;
                    pendingSellReason = "回撤熔断";
                    pendingSellSignalDay = tradeDay;
                    Map<String, Object> rd = new LinkedHashMap<String, Object>();
                    rd.put("权益", equity.setScale(2, RoundingMode.HALF_UP));
                    rd.put("仓位系数", posScale);
                    analysis.risk(stockCode, bar.getBarBegin(), "账户回撤熔断，挂清仓卖单且禁新开", rd);
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
                    int first = props.isPyramidEnabled()
                            ? positionAmountUtil.pyramidSlice(targetFullVol, 0) : targetFullVol;
                    if (first >= 100) {
                        pendingBuyVol = first;
                        pendingBuyPyramid = false;
                        pendingBuySignalDay = tradeDay;
                        Map<String, Object> sd = DecisionAnalysisLog.indSnapshot(ind, i, close, cash, equity, posScale, atr);
                        sd.put("满仓目标股数", targetFullVol);
                        sd.put("挂单股数", first);
                        sd.put("金字塔开启", props.isPyramidEnabled());
                        sd.put("数量公式", props.isPyramidEnabled()
                                ? "满仓目标=可用资金×单只上限30%×ATR调节(0.2~1.5)×仓位系数，整手；首批=满仓目标×50%"
                                : "满仓目标=可用资金×单只上限30%×ATR调节(0.2~1.5)×仓位系数，整手；一次满仓");
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
                    pd.put("综合成本", pos.getAvgCost());
                    pd.put("数量公式", pyramidStage == 1
                            ? "第2批=满仓目标×30%，成交后占档"
                            : "第3批=满仓目标×20%，成交后占档");
                    analysis.signalPyramid(stockCode, bar.getBarBegin(),
                            "浮盈达标且均线多头，挂金字塔加仓买单", pd);
                    // 档位在成交后递增
                }
            }

            if (pos.hasPosition() && sellSignal && !stoppedOutToday && !pendingSell) {
                pendingSell = true;
                pendingSellReason = "死叉";
                pendingSellSignalDay = tradeDay;
                Map<String, Object> sd = DecisionAnalysisLog.indSnapshot(ind, i, close, cash, equity, posScale, atrAt(ind, i));
                sd.put("持仓股数", pos.getShares());
                analysis.signalSell(stockCode, bar.getBarBegin(), "死叉，挂次日有效开盘全仓卖出", sd);
            }

            // 非 nextBar 兼容：当根收盘撮合
            if (!props.isNextBarOpenFill()) {
                if (pendingBuyVol != null && pendingBuyVol >= 100) {
                    int vol = pendingBuyVol;
                    boolean isPyramid = pendingBuyPyramid;
                    pendingBuyVol = null;
                    pendingBuyPyramid = false;
                    pendingBuySignalDay = null;
                    BigDecimal deal = tradeCostModel.buyPrice(close, closedBars, i, vol);
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
                    }
                }
                if (pendingSell && pos.hasPosition()) {
                    int vol = pos.getShares();
                    String sellWhy = pendingSellReason == null ? "挂单卖出" : pendingSellReason;
                    SellOutcome so = executeSell(stockCode, bar, closedBars, i, cash, pos, close, vol, true,
                            commissionRate, trades, sellMarks);
                    cash = so.cash;
                    closedRound++;
                    if (so.win) {
                        winTrades++;
                    }
                    accountRiskState.onClosedRound(so.win, tradeDay);
                    logLastTrade(analysis, trades, sellWhy + "；当根收盘撮合");
                    pyramidStage = 0;
                    targetFullVol = 0;
                    pendingSell = false;
                    pendingSellSignalDay = null;
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

    private SellOutcome executeSell(String stockCode, BarDTO bar, List<BarDTO> bars, int index,
                                    BigDecimal cash, PositionState pos, BigDecimal fillBase, int vol,
                                    boolean clearAll, BigDecimal commissionRate,
                                    List<BackTradeRecord> trades, List<BackTestResult.MarkPoint> sellMarks) {
        BigDecimal avg = pos.getAvgCost();
        BigDecimal deal = tradeCostModel.sellPrice(fillBase, bars, index, vol);
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.sellFee(amount, commissionRate);
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
