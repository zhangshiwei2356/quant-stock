package com.quant.stock.backtest;

import com.quant.stock.admin.EffectiveParamsService;
import com.quant.stock.admin.ParamsScope;
import com.quant.stock.backtest.dto.AnalysisEvent;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.backtest.dto.SingleStockBackResult;
import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.portfolio.PortfolioCorrelationMonitor;
import com.quant.stock.risk.AccountRiskState;
import com.quant.stock.risk.AtrRiskReport;
import com.quant.stock.risk.ExitPriority;
import com.quant.stock.risk.LimitBoardHelper;
import com.quant.stock.risk.LimitDownForcePolicy;
import com.quant.stock.risk.OpenFilterService;
import com.quant.stock.risk.LimitPriceProtect;
import com.quant.stock.risk.StopFillPrice;
import com.quant.stock.risk.StressScenarioService;
import com.quant.stock.risk.StructuralBreakMonitor;
import com.quant.stock.session.SessionPortfolioBackTestEngine;
import com.quant.stock.session.SessionStrategy;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.IndicatorSignalUtil;
import com.quant.stock.strategy.StrategyRegistry;
import com.quant.stock.trade.CapacityThrottle;
import com.quant.stock.trade.FillVolumeScale;
import com.quant.stock.trade.PartialFillSim;
import com.quant.stock.trade.ParticipationCap;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.util.PositionAmountUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 多标的共享资金池组合回测：对齐单股引擎核心
 * （次日开盘撮合、TradeCostModel、OpenFilter、金字塔、ATR/trail 止损、账户熔断、T+1 分档）。
 */
@Service
@RequiredArgsConstructor
public class PortfolioBackTestEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PENDING_BUY_EXPIRE_DAYS = 5;

    private final QuantProperties props;
    private final EffectiveParamsService effectiveParamsService;
    private final MarketDataService marketDataService;
    private final PositionAmountUtil positionAmountUtil;
    private final TradeCostModel tradeCostModel;
    private final OpenFilterService openFilterService;
    private final StrategyRegistry strategyRegistry;
    private final TradingCalendar tradingCalendar;
    private final SessionPortfolioBackTestEngine sessionPortfolioBackTestEngine;

    private QuantProperties p() {
        return ParamsScope.current(props);
    }

    /**
     * 组合回测：默认日 K 共享资金池；{@code engine=session} 或 {@link SessionStrategy}
     * （如 {@code overnightGap}）时为 MIN_1 会话共享资金池（统一现金 + 账户风控）。
     */
    public PortfolioResultDTO run(BackTestQueryDTO query) {
        BaseStrategy strategy = strategyRegistry.resolve(
                query != null ? query.getStrategyId() : null);
        java.util.Map<String, String> overrides = query == null ? null
                : com.quant.stock.admin.RunParamOverrides.normalize(query.getParamOverrides());
        QuantProperties effective = effectiveParamsService.resolve(strategy.name(), overrides);
        final String engine = resolveEngine(query, strategy);
        return ParamsScope.call(effective, new java.util.concurrent.Callable<PortfolioResultDTO>() {
            @Override
            public PortfolioResultDTO call() {
                if ("session".equals(engine)) {
                    return runSessionScoped(query, strategy);
                }
                PortfolioResultDTO r = runScoped(query, strategy);
                if (r.getEngine() == null) {
                    r.setEngine("classic");
                }
                return r;
            }
        });
    }

    static String resolveEngine(BackTestQueryDTO query, BaseStrategy strategy) {
        String raw = query == null ? null : query.getEngine();
        if (raw != null && !raw.trim().isEmpty()) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if ("session".equals(e) || "classic".equals(e)) {
                return e;
            }
            throw new IllegalArgumentException("engine 仅支持 classic|session，当前=" + raw);
        }
        if (strategy instanceof SessionStrategy) {
            return "session";
        }
        return "classic";
    }

    /**
     * session 组合：MIN_1 并集分钟轴 + 共享现金池（见 {@link SessionPortfolioBackTestEngine}）。
     */
    private PortfolioResultDTO runSessionScoped(BackTestQueryDTO query, BaseStrategy strategy) {
        if (!(strategy instanceof SessionStrategy)) {
            throw new IllegalArgumentException("engine=session 需要 SessionStrategy，当前="
                    + (strategy == null ? "null" : strategy.name()));
        }
        boolean failDep = query != null && query.getFailOnMissingDep() != null
                && query.getFailOnMissingDep().booleanValue();
        return sessionPortfolioBackTestEngine.run(query, (SessionStrategy) strategy, failDep);
    }

    private PortfolioResultDTO runScoped(BackTestQueryDTO query, BaseStrategy strategy) {
        BigDecimal initCapitalPreview = query == null || query.getInitCapital() == null
                ? new BigDecimal("100000") : query.getInitCapital();
        BigDecimal commissionRate = query != null && query.getFeeRate() != null
                ? query.getFeeRate() : p().getFeeRate();
        String fingerprint = ConfigFingerprint.of(p(), strategy.fingerprintId(), commissionRate);
        if (query == null || query.getStockCodeList() == null || query.getStockCodeList().isEmpty()) {
            PortfolioResultDTO empty = PortfolioResultDTO.empty(BigDecimal.ZERO);
            empty.setConfigFingerprint(fingerprint);
            return empty;
        }
        BigDecimal initCapital = initCapitalPreview;

        Map<String, List<BarDTO>> barMap = new HashMap<String, List<BarDTO>>();
        Map<String, IndicatorSignalUtil.IndicatorBundle> indMap = new HashMap<String, IndicatorSignalUtil.IndicatorBundle>();
        TreeSet<LocalDateTime> timeSet = new TreeSet<LocalDateTime>();
        for (String code : query.getStockCodeList()) {
            List<BarDTO> bars = marketDataService.getKline(code, BarPeriod.DAY, query.getBackStart(), query.getBackEnd());
            if (bars.size() < 65) {
                continue;
            }
            barMap.put(code, bars);
            indMap.put(code, IndicatorSignalUtil.precompute(bars));
            for (BarDTO b : bars) {
                timeSet.add(b.getBarBegin());
            }
        }
        if (barMap.isEmpty()) {
            PortfolioResultDTO empty = PortfolioResultDTO.empty(initCapital);
            empty.setConfigFingerprint(fingerprint);
            return empty;
        }

        Map<String, StockBook> books = new HashMap<String, StockBook>();
        for (String code : barMap.keySet()) {
            books.put(code, new StockBook());
        }

        AccountRiskState accountRisk = new AccountRiskState(p());
        accountRisk.reset(initCapital);

        BigDecimal cash = initCapital;
        BigDecimal peak = initCapital;
        BigDecimal maxDd = BigDecimal.ZERO;
        List<BackTradeRecord> trades = new ArrayList<BackTradeRecord>();
        List<String> equityTimes = new ArrayList<String>();
        List<BigDecimal> equityCurve = new ArrayList<BigDecimal>();

        List<LocalDateTime> timeline = new ArrayList<LocalDateTime>(timeSet);
        LocalDate currentDay = null;
        int step = 0;

        for (LocalDateTime t : timeline) {
            LocalDate tradeDay = t.toLocalDate();
            if (currentDay == null || !currentDay.equals(tradeDay)) {
                currentDay = tradeDay;
                for (StockBook b : books.values()) {
                    b.stoppedOutToday = false;
                    b.pos.clearAddedToday();
                }
            }

            // 撮合前权益：供 ADV/AUM 降频（P0-112）
            BigDecimal equity = calcEquity(cash, books, barMap, t);

            // Step1: 撮合
            for (String code : barMap.keySet()) {
                List<BarDTO> bars = barMap.get(code);
                int idx = findIndex(bars, t, books.get(code).hint);
                if (idx < 0) {
                    continue;
                }
                books.get(code).hint = idx;
                StockBook book = books.get(code);
                BarDTO bar = bars.get(idx);
                BigDecimal open = bar.getOpen();

                if (book.pendingBuyVol != null && book.pendingBuySignalDay != null
                        && tradeDay.isAfter(book.pendingBuySignalDay.plusDays(PENDING_BUY_EXPIRE_DAYS))) {
                    book.pendingBuyVol = null;
                    book.pendingBuyPyramid = false;
                    book.pendingBuySignalDay = null;
                }

                if (book.pendingSell && book.pos.hasPosition() && book.pendingSellSignalDay != null
                        && tradeDay.isAfter(book.pendingSellSignalDay)
                        && FillTimingHelper.canFillPendingOnBar(bars, idx)
                        && !openFilterService.isSuspended(bar)) {
                    int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
                    if (sellable >= 100) {
                        int vol = sellable;
                        ExitPriority exitPri = ExitPriority.fromReasonLabel(book.pendingSellReason);
                        if (exitPri == null || !exitPri.bypassParticipationCap()) {
                            long adv = IndicatorSignalUtil.avgVolume(bars, idx, 20);
                            vol = capVol(vol, adv, equity, bars, idx);
                        }
                        if (vol < 100) {
                            continue;
                        }
                        boolean full = vol >= book.pos.getShares();
                        boolean limitDown = openFilterService.isLimitDownAt(bars, idx);
                        if (LimitDownForcePolicy.deferForLimitDown(limitDown, book.limitDownFailDays)) {
                            if (book.lastLimitDownFailDay == null || !book.lastLimitDownFailDay.equals(tradeDay)) {
                                book.limitDownFailDays++;
                                book.lastLimitDownFailDay = tradeDay;
                            }
                        } else if (LimitDownForcePolicy.shouldSellNow(limitDown, book.limitDownFailDays)) {
                            BigDecimal fillBase = open;
                            if (limitDown) {
                                BigDecimal prev = openFilterService.prevTradingDayClose(bars, idx);
                                boolean st = openFilterService.isSt(code, tradeDay);
                                BigDecimal forcePx = LimitBoardHelper.limitDownPrice(prev, code, st);
                                if (forcePx == null) {
                                    forcePx = open;
                                }
                                fillBase = forcePx.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
                            }
                            cash = doSell(code, book, bars, idx, cash, fillBase, vol, full,
                                    commissionRate, trades, tradeDay, accountRisk, equity);
                        }
                    }
                }

                if (book.pendingBuyVol != null && book.pendingBuySignalDay != null
                        && tradeDay.isAfter(book.pendingBuySignalDay)
                        && FillTimingHelper.canFillPendingOnBar(bars, idx)
                        && openFilterService.canExecuteOpenFill(code, bars, idx)
                        && accountRisk.allowNewOpen(tradeDay, equity)
                        && resolvePosScale(accountRisk, equity, bars, idx).compareTo(BigDecimal.ZERO) > 0) {
                    cash = doBuy(code, book, bars, idx, cash, open, commissionRate, trades, tradeDay, accountRisk,
                            equity);
                }
            }

            // Step2: 老仓止损
            if (p().isStopLossEnabled()) {
                for (String code : barMap.keySet()) {
                    List<BarDTO> bars = barMap.get(code);
                    int idx = findIndex(bars, t, books.get(code).hint);
                    if (idx < 0) {
                        continue;
                    }
                    StockBook book = books.get(code);
                    BarDTO bar = bars.get(idx);
                    if (!book.pos.hasPosition() || !book.pos.canSellStops(tradeDay)) {
                        if (book.pos.hasPosition()) {
                            book.pos.updateHighest(bar.getHigh());
                        }
                        continue;
                    }
                    book.pos.updateHighest(bar.getHigh());
                    if (openFilterService.isSuspended(bar)) {
                        continue;
                    }
                    int sellable = (book.pos.sellableShares(tradeDay) / 100) * 100;
                    StopFillPrice.Result stopFill = StopFillPrice.resolve(
                            bar.getOpen(), bar.getLow(), book.pos.getStopPrice());
                    if (sellable >= 100 && stopFill.triggered()) {
                        BigDecimal fill = stopFill.fillBase;
                        boolean full = sellable >= book.pos.getShares();
                        cash = doSell(code, book, bars, idx, cash, fill, sellable, full,
                                commissionRate, trades, tradeDay, accountRisk, equity);
                        if (full || !book.pos.hasPosition()) {
                            book.stoppedOutToday = true;
                        }
                    }
                }
            }

            equity = calcEquity(cash, books, barMap, t);
            accountRisk.onEquity(tradeDay, equity);

            // Step3 halt → pending sell all
            if (accountRisk.isHalted()) {
                for (StockBook book : books.values()) {
                    ExitPriority cur = ExitPriority.fromReasonLabel(book.pendingSellReason);
                    if (book.pos.hasPosition()
                            && ExitPriority.ACCOUNT_HALT.canRegisterOrPreempt(
                            book.stoppedOutToday, book.pendingSell, cur)) {
                        book.pendingSell = true;
                        book.pendingSellReason = ExitPriority.ACCOUNT_HALT.getLabel();
                        book.pendingSellSignalDay = tradeDay;
                    }
                }
            }

            // Step3b: 时间止损
            if (p().getMaxHoldTradingDays() > 0) {
                for (StockBook book : books.values()) {
                    ExitPriority cur = ExitPriority.fromReasonLabel(book.pendingSellReason);
                    if (book.pos.hasPosition()
                            && ExitPriority.TIME_STOP.canRegisterOrPreempt(
                            book.stoppedOutToday, book.pendingSell, cur)) {
                        int held = tradingCalendar.tradingDaysAfter(book.pos.getEarliestOpenDate(), tradeDay);
                        if (held >= p().getMaxHoldTradingDays()) {
                            book.pendingSell = true;
                            book.pendingSellReason = ExitPriority.TIME_STOP.getLabel();
                            book.pendingSellSignalDay = tradeDay;
                        }
                    }
                }
            }

            // Step4: 信号
            for (String code : barMap.keySet()) {
                List<BarDTO> bars = barMap.get(code);
                IndicatorSignalUtil.IndicatorBundle ind = indMap.get(code);
                int idx = findIndex(bars, t, books.get(code).hint);
                if (idx < 0) {
                    continue;
                }
                StockBook book = books.get(code);
                BarDTO bar = bars.get(idx);
                BigDecimal close = bar.getClose();
                BigDecimal posScale = resolvePosScale(accountRisk, equity, bars, idx);
                boolean buySignal = strategy.isBuySignalAt(ind, idx);
                boolean sellSignal = strategy.isSellSignalAt(ind, idx);

                if (!book.pos.hasPosition() && buySignal && !book.pendingSell && book.pendingBuyVol == null
                        && accountRisk.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0
                        && openFilterService.canOpen(code, bars, idx)) {
                    BigDecimal atr = atrAt(ind, idx);
                    if (atr.compareTo(p().getAtrMinThreshold()) > 0) {
                        book.targetFullVol = positionAmountUtil.calcBuyVolume(cash, close, atr, posScale);
                        long advBuy = IndicatorSignalUtil.avgVolume(bars, idx, 20);
                        book.targetFullVol = capVol(book.targetFullVol, advBuy, equity, bars, idx);
                        int first = p().isPyramidEnabled()
                                ? positionAmountUtil.pyramidSlice(book.targetFullVol, 0) : book.targetFullVol;
                        first = capVol(first, advBuy, equity, bars, idx);
                        if (first >= 100) {
                            book.pendingBuyVol = first;
                            book.pendingBuyPyramid = false;
                            book.pendingBuySignalDay = tradeDay;
                        }
                    }
                } else if (book.pos.hasPosition() && p().isPyramidEnabled()
                        && book.pyramidStage >= 1 && book.pyramidStage < 3
                        && book.pendingBuyVol == null
                        && !book.pos.isAddedToday()
                        && close.compareTo(book.pos.getAvgCost().multiply(BigDecimal.ONE.add(p().getPyramidAddPct()))) >= 0
                        && ind.ma5[idx] > ind.ma20[idx]
                        && accountRisk.allowNewOpen(tradeDay, equity)
                        && posScale.compareTo(BigDecimal.ZERO) > 0) {
                    int slice = positionAmountUtil.pyramidSlice(book.targetFullVol, book.pyramidStage);
                    long advAdd = IndicatorSignalUtil.avgVolume(bars, idx, 20);
                    slice = capVol(slice, advAdd, equity, bars, idx);
                    BigDecimal posMv = close.multiply(BigDecimal.valueOf(book.pos.getShares()));
                    BigDecimal addMoney = close.multiply(BigDecimal.valueOf(Math.max(slice, 0)));
                    if (slice >= 100 && positionAmountUtil.withinTotalPosition(equity, calcPosMv(books, barMap, t), addMoney)) {
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

                if (book.pos.hasPosition() && p().isTrailingStopEnabled()) {
                    book.pos.raiseTrailingStop(atrAt(ind, idx), p().getTrailingAtrMultiplier());
                }

                // 分股权益峰值/回撤（按该股持仓市值+已实现）
                BigDecimal stockMv = book.pos.hasPosition()
                        ? close.multiply(BigDecimal.valueOf(book.pos.getShares())) : BigDecimal.ZERO;
                BigDecimal stockEq = book.realized.add(stockMv);
                if (stockEq.compareTo(book.peakEq) > 0) {
                    book.peakEq = stockEq;
                }
                if (book.peakEq.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal dd = book.peakEq.subtract(stockEq).divide(book.peakEq, 6, RoundingMode.HALF_UP);
                    if (dd.compareTo(book.maxDd) > 0) {
                        book.maxDd = dd;
                    }
                }
            }

            equity = calcEquity(cash, books, barMap, t);
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            if (peak.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dd = peak.subtract(equity).divide(peak, 6, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
            if (step % 30 == 0 || step == timeline.size() - 1) {
                equityTimes.add(t.format(FMT));
                equityCurve.add(equity.setScale(2, RoundingMode.HALF_UP));
            }
            // 日末固化账户昨收权益
            boolean dayEnd = step == timeline.size() - 1
                    || !timeline.get(step + 1).toLocalDate().equals(tradeDay);
            if (dayEnd) {
                accountRisk.onDayClose(equity);
            }
            step++;
        }

        BigDecimal finalAsset = calcEquity(cash, books, barMap, timeline.get(timeline.size() - 1));
        BigDecimal totalRate = finalAsset.subtract(initCapital).divide(initCapital, 6, RoundingMode.HALF_UP);

        int totalWin = 0;
        int totalRound = 0;
        List<SingleStockBackResult> stockResults = new ArrayList<SingleStockBackResult>();
        for (String code : barMap.keySet()) {
            StockBook book = books.get(code);
            totalWin += book.winRounds;
            totalRound += book.closedRounds;
            BigDecimal wr = book.closedRounds == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(book.winRounds)
                    .divide(BigDecimal.valueOf(book.closedRounds), 4, RoundingMode.HALF_UP);
            BigDecimal realized = book.realized.setScale(2, RoundingMode.HALF_UP);
            BigDecimal contrib = initCapital.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ZERO
                    : realized.divide(initCapital, 6, RoundingMode.HALF_UP);
            stockResults.add(SingleStockBackResult.builder()
                    .stockCode(code)
                    .totalTradeNum(book.tradeCount)
                    .winRate(wr)
                    .totalRate(contrib)
                    .maxDrawDown(book.maxDd)
                    .finalAsset(realized)
                    .build());
        }
        stockResults.sort(Comparator.comparing(SingleStockBackResult::getStockCode));
        BigDecimal winRate = totalRound == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalWin).divide(BigDecimal.valueOf(totalRound), 4, RoundingMode.HALF_UP);

        DecisionAnalysisLog analysis = DecisionAnalysisLog.fromTrades(trades);
        Map<String, List<BigDecimal>> closes = new HashMap<String, List<BigDecimal>>();
        for (Map.Entry<String, List<BarDTO>> e : barMap.entrySet()) {
            List<BigDecimal> cs = new ArrayList<BigDecimal>();
            for (BarDTO b : e.getValue()) {
                if (b != null && b.getClose() != null) {
                    cs.add(b.getClose());
                }
            }
            closes.put(e.getKey(), cs);
        }
        Map<String, Object> correlation = PortfolioCorrelationMonitor.report(
                closes, p().getCorrelationLookbackDays(), p().getCorrelationWarnThreshold());

        return PortfolioResultDTO.builder()
                .initCapital(initCapital)
                .finalAsset(finalAsset.setScale(2, RoundingMode.HALF_UP))
                .totalRate(totalRate)
                .maxDrawDown(maxDd)
                .totalTradeNum(trades.size())
                .winRate(winRate)
                .equityTimes(equityTimes)
                .equityCurve(equityCurve)
                .stockResults(stockResults)
                .trades(trades)
                .analysisEvents(analysis.events())
                .analysisSummary(analysis.summary() + "（组合：由成交流水生成；完整信号依据见单股分析）")
                .configFingerprint(fingerprint)
                .correlation(correlation)
                .atrRisk(AtrRiskReport.from(props, analysis))
                .build();
    }

    private BigDecimal doBuy(String code, StockBook book, List<BarDTO> bars, int idx,
                             BigDecimal cash, BigDecimal base, BigDecimal commissionRate,
                             List<BackTradeRecord> trades, LocalDate tradeDay, AccountRiskState risk,
                             BigDecimal portfolioEquity) {
        int rawVol = book.pendingBuyVol == null ? 0 : book.pendingBuyVol;
        boolean pyramid = book.pendingBuyPyramid;
        LocalDate signalDay = book.pendingBuySignalDay;
        book.pendingBuyVol = null;
        book.pendingBuyPyramid = false;
        book.pendingBuySignalDay = null;
        BigDecimal equityForScale = portfolioEquity != null ? portfolioEquity
                : cash.add(calcPosMvOne(book, bars.get(idx).getClose()));
        BigDecimal posScale = resolvePosScale(risk, equityForScale, bars, idx);
        int requestVol = FillVolumeScale.scaleToLot(rawVol, posScale);
        if (requestVol < 100) {
            // 仓位系数缩放后不足1手：取消挂单（对齐单股）
            return cash;
        }
        int vol = PartialFillSim.fillVolume(requestVol, p().getBacktestFillRatio());
        if (vol < 100) {
            int rem0 = PartialFillSim.remainder(requestVol, 0);
            if (rem0 >= 100) {
                book.pendingBuyVol = rem0;
                book.pendingBuyPyramid = pyramid;
                book.pendingBuySignalDay = signalDay;
            }
            return cash;
        }
        BigDecimal deal = tradeCostModel.buyPrice(base, bars, idx, vol);
        if (p().isLimitPriceProtectEnabled()) {
            LocalDate asOf = bars.get(idx).getBarBegin() == null ? tradeDay
                    : bars.get(idx).getBarBegin().toLocalDate();
            deal = LimitPriceProtect.clampBuy(deal, openFilterService.prevTradingDayClose(bars, idx),
                    code, openFilterService.isSt(code, asOf));
        }
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.buyFee(amount, commissionRate);
        BigDecimal equity = portfolioEquity != null ? portfolioEquity
                : cash.add(calcPosMvOne(book, bars.get(idx).getClose()));
        if (amount.add(fee).compareTo(cash) > 0) {
            // 现金不足：保留挂单重试
            book.pendingBuyVol = requestVol;
            book.pendingBuyPyramid = pyramid;
            book.pendingBuySignalDay = signalDay;
            return cash;
        }
        cash = cash.subtract(amount).subtract(fee);
        book.pos.addBuy(vol, deal, fee, tradeDay);
        book.pos.raiseStopByCost(atrAt(IndicatorSignalUtil.precompute(bars), idx),
                equity, p().getAtrStopMultiplier(), p().getHardStopCapitalPct());
        book.tradeCount++;
        trades.add(rec(code, "BUY", bars.get(idx).getBarBegin(), deal, vol, fee, amount));
        if (pyramid) {
            book.pyramidStage++;
        } else {
            book.pyramidStage = Math.max(book.pyramidStage, 1);
        }
        int rem = PartialFillSim.remainder(requestVol, vol);
        if (rem >= 100) {
            book.pendingBuyVol = rem;
            book.pendingBuyPyramid = pyramid;
            book.pendingBuySignalDay = signalDay;
        }
        return cash;
    }

    private BigDecimal doSell(String code, StockBook book, List<BarDTO> bars, int idx,
                              BigDecimal cash, BigDecimal base, int vol, boolean clearAll,
                              BigDecimal commissionRate, List<BackTradeRecord> trades,
                              LocalDate tradeDay, AccountRiskState risk, BigDecimal portfolioEquity) {
        vol = (vol / 100) * 100;
        if (vol < 100 || !book.pos.hasPosition()) {
            return cash;
        }
        BigDecimal avg = book.pos.getAvgCost();
        BigDecimal deal = tradeCostModel.sellPrice(base, bars, idx, vol);
        if (p().isLimitPriceProtectEnabled()) {
            LocalDate asOf = bars.get(idx).getBarBegin() == null ? tradeDay
                    : bars.get(idx).getBarBegin().toLocalDate();
            deal = LimitPriceProtect.clampSell(deal, openFilterService.prevTradingDayClose(bars, idx),
                    code, openFilterService.isSt(code, asOf));
        }
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.sellFee(amount, commissionRate, tradeDay);
        BigDecimal pnl = deal.subtract(avg).multiply(BigDecimal.valueOf(vol)).subtract(fee);
        cash = cash.add(amount).subtract(fee);
        trades.add(rec(code, "SELL", bars.get(idx).getBarBegin(), deal, vol, fee, amount));
        book.tradeCount++;
        book.realized = book.realized.add(pnl);
        if (clearAll || vol >= book.pos.getShares()) {
            book.pos.clear();
            book.pyramidStage = 0;
            book.targetFullVol = 0;
            book.closedRounds++;
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                book.winRounds++;
            }
            risk.onClosedRound(pnl.compareTo(BigDecimal.ZERO) > 0, tradeDay);
            book.pendingSell = false;
            book.pendingSellReason = null;
            book.pendingSellSignalDay = null;
            book.limitDownFailDays = 0;
            book.lastLimitDownFailDay = null;
        } else {
            book.pos.removeShares(vol);
            BigDecimal eq = portfolioEquity != null ? portfolioEquity : cash;
            book.pos.raiseStopByCost(atrAt(IndicatorSignalUtil.precompute(bars), idx),
                    eq, p().getAtrStopMultiplier(), p().getHardStopCapitalPct());
            book.pendingSell = true;
            if (book.pendingSellSignalDay == null) {
                book.pendingSellSignalDay = tradeDay;
            }
        }
        return cash;
    }

    private int findIndex(List<BarDTO> bars, LocalDateTime t, Integer hint) {
        int idx = hint == null || hint < 0 ? 0 : hint;
        while (idx < bars.size() && bars.get(idx).getBarBegin().isBefore(t)) {
            idx++;
        }
        if (idx >= bars.size() || !bars.get(idx).getBarBegin().equals(t)) {
            return -1;
        }
        return idx;
    }

    private BackTradeRecord rec(String code, String side, LocalDateTime t, BigDecimal deal,
                                int vol, BigDecimal fee, BigDecimal amount) {
        return BackTradeRecord.builder()
                .stockCode(code).side(side).tradeTime(t)
                .price(deal).volume(vol).fee(fee).amount(amount).build();
    }

    private BigDecimal calcEquity(BigDecimal cash, Map<String, StockBook> books,
                                  Map<String, List<BarDTO>> barMap, LocalDateTime t) {
        return cash.add(calcPosMv(books, barMap, t));
    }

    private BigDecimal calcPosMv(Map<String, StockBook> books, Map<String, List<BarDTO>> barMap, LocalDateTime t) {
        BigDecimal mv = BigDecimal.ZERO;
        for (Map.Entry<String, StockBook> e : books.entrySet()) {
            if (!e.getValue().pos.hasPosition()) {
                continue;
            }
            BigDecimal price = findPriceAt(barMap.get(e.getKey()), t);
            mv = mv.add(price.multiply(BigDecimal.valueOf(e.getValue().pos.getShares())));
        }
        return mv;
    }

    private BigDecimal calcPosMvOne(StockBook book, BigDecimal price) {
        if (!book.pos.hasPosition() || price == null) {
            return BigDecimal.ZERO;
        }
        return price.multiply(BigDecimal.valueOf(book.pos.getShares()));
    }

    private BigDecimal findPriceAt(List<BarDTO> bars, LocalDateTime t) {
        BigDecimal last = bars.get(0).getClose();
        for (BarDTO b : bars) {
            if (b.getBarBegin().isAfter(t)) {
                break;
            }
            last = b.getClose();
        }
        return last;
    }

    private BigDecimal atrAt(IndicatorSignalUtil.IndicatorBundle ind, int i) {
        if (ind == null || i < 0 || Double.isNaN(ind.atr14[i])) {
            return p().getBaseAtr();
        }
        return BigDecimal.valueOf(ind.atr14[i]);
    }

    /** 与单股对齐：账户仓位系数 × ADV断崖 × 结构突变（不改金叉） */
    private BigDecimal resolvePosScale(AccountRiskState risk, BigDecimal equity,
                                       List<BarDTO> bars, int index) {
        BigDecimal scale = risk.positionScale(equity);
        if (p().isStressScenarioEnabled()
                && StressScenarioService.isAdvCliff(bars, index, p().getStressAdvCliffRatio())) {
            scale = scale.multiply(new BigDecimal("0.5"));
        }
        if (p().isStructuralBreakEnabled()
                && StructuralBreakMonitor.crossesThreshold(bars, index,
                p().getStructuralBreakWindow(), p().getStructuralBreakThreshold())) {
            scale = scale.multiply(new BigDecimal("0.5"));
        }
        return scale;
    }

    /** P0-112：组合路径对齐单股——AUM 收紧 ADV + 当根量 POV（无 TWAP） */
    private int capVol(int vol, long adv20, BigDecimal equity, List<BarDTO> bars, int index) {
        long barVol = 0L;
        if (bars != null && index >= 0 && index < bars.size() && bars.get(index).getVolume() != null) {
            barVol = bars.get(index).getVolume().longValue();
        }
        BigDecimal eff = CapacityThrottle.effectiveMaxParticipation(
                p().getMaxParticipationAdv(), equity, p().getCapacityAumBase());
        int capped = ParticipationCap.capVolume(vol, adv20, eff);
        return CapacityThrottle.povCapVolume(capped, barVol, p().getPovMaxBarVolumePct());
    }

    private static final class StockBook {
        final PositionState pos = new PositionState();
        Integer hint = 0;
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
        int tradeCount;
        int closedRounds;
        int winRounds;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal peakEq = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
    }
}
