package com.quant.stock.session;

import com.quant.stock.backtest.PositionState;
import com.quant.stock.backtest.dto.AnalysisEvent;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.admin.ParamsScope;
import com.quant.stock.backtest.FillTimingHelper;
import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.risk.LimitPriceProtect;
import com.quant.stock.trade.TradeCostModel;
import com.quant.stock.util.PositionAmountUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 旁路会话回测引擎：MIN_1 推进 + 三分支调度 + 依赖降级 + 可选撮合子集。
 * 不替代经典 {@link com.quant.stock.backtest.BackTestEngine}。
 */
@Service
@RequiredArgsConstructor
public class SessionBackTestEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MIN_BARS = 30;
    private static final int PENDING_BUY_EXPIRE_DAYS = 5;

    /** 会话挂单（NEXT_EFFECTIVE 模式）。 */
    private static final class PendingOrder {
        final SessionOrderIntent.Side side;
        final int volume;
        final LocalDate signalDay;
        final SessionBranch signalBranch;
        final String reason;
        final boolean bypassCap;

        PendingOrder(SessionOrderIntent.Side side, int volume, LocalDate signalDay,
                     SessionBranch signalBranch, String reason, boolean bypassCap) {
            this.side = side;
            this.volume = volume;
            this.signalDay = signalDay;
            this.signalBranch = signalBranch;
            this.reason = reason;
            this.bypassCap = bypassCap;
        }
    }

    private final MarketDataService marketDataService;
    private final SessionDepProbe depProbe;
    private final QuantProperties props;
    private final TradeCostModel tradeCostModel;
    private final PositionAmountUtil positionAmountUtil;

    private QuantProperties p() {
        return ParamsScope.current(props);
    }

    public BackTestResult run(String stockCode, LocalDateTime start, LocalDateTime end,
                              BigDecimal initCapital, SessionStrategy strategy, boolean failOnMissingDep) {
        if (strategy == null) {
            throw new IllegalArgumentException("session 策略不能为空");
        }
        BigDecimal capital = initCapital == null ? new BigDecimal("100000") : initCapital;
        List<BarDTO> bars = marketDataService.getKline(stockCode, BarPeriod.MIN_1, start, end);
        QuantProperties cfg = p();
        SessionWindows windows = SessionWindows.from(cfg);
        QuantProperties.Session sessCfg = cfg.getSession() == null ? new QuantProperties.Session() : cfg.getSession();
        boolean matchingEnabled = sessCfg.isMatchingEnabled();
        String fillMode = resolveFillMode(cfg, sessCfg);
        boolean nextEffective = isNextEffective(fillMode);
        String fingerprint = sessionFingerprint(cfg, strategy.sessionId(), failOnMissingDep, windows,
                matchingEnabled, fillMode);

        if (bars == null || bars.size() < MIN_BARS) {
            BackTestResult empty = BackTestResult.empty(stockCode, capital);
            empty.setEngine("session");
            empty.setConfigFingerprint(fingerprint);
            empty.setAnalysisSummary("MIN_1 不足（至少 " + MIN_BARS + " 根），会话回测未执行");
            return empty;
        }

        Set<DataDep> required = strategy.dataDeps() == null
                ? EnumSet.of(DataDep.MIN1)
                : EnumSet.copyOf(strategy.dataDeps());
        Set<DataDep> missing = depProbe.probeUnavailable(stockCode, start, end, required);
        if (failOnMissingDep && missing != null && !missing.isEmpty()) {
            throw new IllegalArgumentException("会话回测缺依赖: " + missing
                    + "（failOnMissingDep=true）。策略=" + strategy.sessionId());
        }

        Set<SessionBranch> degraded = new LinkedHashSet<SessionBranch>();
        if (missing != null) {
            for (DataDep dep : missing) {
                Set<SessionBranch> affected = strategy.branchesAffectedBy(dep);
                if (affected != null) {
                    degraded.addAll(affected);
                }
            }
        }

        List<SessionEvent> events = new ArrayList<SessionEvent>();
        List<BackTradeRecord> trades = new ArrayList<BackTradeRecord>();
        List<BackTestResult.MarkPoint> buyMarks = new ArrayList<BackTestResult.MarkPoint>();
        List<BackTestResult.MarkPoint> sellMarks = new ArrayList<BackTestResult.MarkPoint>();
        BranchStatsAcc stats = new BranchStatsAcc();

        HoldDayState hold = HoldDayState.FLAT;
        PositionState pos = new PositionState();
        BigDecimal cash = capital;
        LocalDate currentDay = null;
        BarDTO lastBarOfDay = null;
        int lastBarIndexOfDay = -1;
        Set<SessionBranch> seenBranchToday = EnumSet.noneOf(SessionBranch.class);
        int sessionDays = 0;

        BigDecimal peakEquity = capital;
        BigDecimal maxDd = BigDecimal.ZERO;
        int winRounds = 0;
        int closedRounds = 0;
        List<String> equityTimes = new ArrayList<String>();
        List<BigDecimal> equityCurve = new ArrayList<BigDecimal>();
        List<PendingOrder> pendingBuys = new ArrayList<PendingOrder>();
        List<PendingOrder> pendingSells = new ArrayList<PendingOrder>();

        for (int i = 0; i < bars.size(); i++) {
            BarDTO bar = bars.get(i);
            if (bar == null || bar.getBarBegin() == null) {
                continue;
            }
            LocalTime t = bar.getBarBegin().toLocalTime();
            if (!SessionTradingMinutes.isTradingMinute(t)) {
                continue;
            }
            SessionBranch branch = windows.of(t);
            if (branch == null) {
                continue;
            }
            LocalDate day = bar.getBarBegin().toLocalDate();

            // NEXT_EFFECTIVE：先撮合到期挂单（对齐经典次日≥09:45 开盘）
            if (nextEffective && matchingEnabled) {
                cash = tryFillPendings(stockCode, bars, i, day, branch, cash, pos, events, trades,
                        buyMarks, sellMarks, stats, pendingBuys, pendingSells, fillMode);
                hold = syncHold(hold, pos);
            }

            boolean newDay = currentDay == null || !currentDay.equals(day);
            if (newDay) {
                if (currentDay != null && lastBarOfDay != null) {
                    BigDecimal eqClose = markEquity(cash, pos, lastBarOfDay);
                    SessionContext closeCtx = baseCtx(stockCode, currentDay, SessionBranch.CLOSE, lastBarOfDay,
                            lastBarIndexOfDay, hold, eqClose, cash, pos, degraded, matchingEnabled);
                    strategy.onSessionClose(closeCtx, events);
                    hold = closeCtx.getHoldState() == null ? hold : closeCtx.getHoldState();
                    cash = acceptIntents(strategy, closeCtx, bars, lastBarIndexOfDay, cash, pos, events, trades,
                            buyMarks, sellMarks, stats, matchingEnabled, fillMode, nextEffective,
                            pendingBuys, pendingSells);
                    hold = syncHold(hold, pos);
                }
                currentDay = day;
                pos.clearAddedToday();
                seenBranchToday = EnumSet.noneOf(SessionBranch.class);
                sessionDays++;
                BigDecimal eqOpen = markEquity(cash, pos, bar);
                SessionContext openCtx = baseCtx(stockCode, day, branch, bar, i, hold, eqOpen, cash, pos,
                        degraded, matchingEnabled);
                strategy.onSessionOpen(openCtx, events);
                hold = openCtx.getHoldState() == null ? hold : openCtx.getHoldState();
                cash = acceptIntents(strategy, openCtx, bars, i, cash, pos, events, trades,
                        buyMarks, sellMarks, stats, matchingEnabled, fillMode, nextEffective,
                        pendingBuys, pendingSells);
                hold = syncHold(hold, pos);
            }
            lastBarOfDay = bar;
            lastBarIndexOfDay = i;

            boolean firstOfBranch = seenBranchToday.add(branch);
            if (firstOfBranch) {
                stats.tick(branch);
            }

            BigDecimal equity = markEquity(cash, pos, bar);
            SessionContext ctx = baseCtx(stockCode, day, branch, bar, i, hold, equity, cash, pos,
                    degraded, matchingEnabled);
            if (ctx.isBranchDegraded()) {
                if (firstOfBranch) {
                    events.add(SessionEvent.builder()
                            .time(FMT.format(bar.getBarBegin()))
                            .type("BRANCH_UNAVAILABLE")
                            .branch(branch.name())
                            .detail("分支降级跳过钩子；缺失依赖影响")
                            .build());
                }
            } else {
                boolean runBranch = firstOfBranch || strategy.tickEveryBar();
                if (runBranch) {
                    strategy.onBranchBar(ctx, events);
                    hold = ctx.getHoldState() == null ? hold : ctx.getHoldState();
                    cash = acceptIntents(strategy, ctx, bars, i, cash, pos, events, trades,
                            buyMarks, sellMarks, stats, matchingEnabled, fillMode, nextEffective,
                            pendingBuys, pendingSells);
                    hold = syncHold(hold, pos);
                }
            }

            equity = markEquity(cash, pos, bar);
            if (equity.compareTo(peakEquity) > 0) {
                peakEquity = equity;
            }
            if (peakEquity.signum() > 0) {
                BigDecimal dd = peakEquity.subtract(equity).divide(peakEquity, 6, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
            if (i == bars.size() - 1 || nextBarNewDay(bars, i, day)) {
                equityTimes.add(FMT.format(bar.getBarBegin()));
                equityCurve.add(equity);
            }
        }
        if (currentDay != null && lastBarOfDay != null) {
            BigDecimal eqClose = markEquity(cash, pos, lastBarOfDay);
            SessionContext closeCtx = baseCtx(stockCode, currentDay, SessionBranch.CLOSE, lastBarOfDay,
                    lastBarIndexOfDay, hold, eqClose, cash, pos, degraded, matchingEnabled);
            strategy.onSessionClose(closeCtx, events);
            cash = acceptIntents(strategy, closeCtx, bars, lastBarIndexOfDay, cash, pos, events, trades,
                    buyMarks, sellMarks, stats, matchingEnabled, fillMode, nextEffective,
                    pendingBuys, pendingSells);
            hold = syncHold(hold, pos);
        }

        // 回合胜率（简化：卖出盈亏>0）
        for (BackTradeRecord tr : trades) {
            if (tr != null && "SELL".equalsIgnoreCase(tr.getSide()) && tr.getAmount() != null) {
                // 胜率在 fill 时已累加不便回读；下方用 stats.realized 近似
            }
        }
        winRounds = stats.winRounds;
        closedRounds = stats.closedRounds;

        BigDecimal finalAsset = capital;
        if (!equityCurve.isEmpty()) {
            finalAsset = equityCurve.get(equityCurve.size() - 1);
        } else if (lastBarOfDay != null) {
            finalAsset = markEquity(cash, pos, lastBarOfDay);
        } else {
            finalAsset = cash;
        }
        BigDecimal totalRate = capital.signum() == 0 ? BigDecimal.ZERO
                : finalAsset.subtract(capital).divide(capital, 6, RoundingMode.HALF_UP);
        BigDecimal winRate = closedRounds <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(winRounds).divide(BigDecimal.valueOf(closedRounds), 4, RoundingMode.HALF_UP);

        List<AnalysisEvent> analysis = toAnalysis(stockCode, events);
        List<String> degradedNames = new ArrayList<String>();
        for (SessionBranch b : degraded) {
            degradedNames.add(b.name());
        }
        Map<String, Object> branchStats = stats.toMap(sessionDays, matchingEnabled, fillMode, windows);

        String summary = String.format(Locale.ROOT,
                "engine=session strategy=%s days=%d OPEN=%d MID=%d CLOSE=%d events=%d trades=%d degraded=%s holdEnd=%s match=%s",
                strategy.sessionId(), sessionDays,
                stats.ticks(SessionBranch.OPEN), stats.ticks(SessionBranch.MID), stats.ticks(SessionBranch.CLOSE),
                events.size(), trades.size(), degradedNames, hold, matchingEnabled);

        return BackTestResult.builder()
                .stockCode(stockCode)
                .initCapital(capital)
                .finalAsset(finalAsset)
                .totalRate(totalRate)
                .maxDrawDown(maxDd)
                .totalTradeNum(trades.size())
                .winRate(winRate)
                .trades(trades)
                .equityTimes(equityTimes)
                .equityCurve(equityCurve)
                .buyMarks(buyMarks)
                .sellMarks(sellMarks)
                .analysisEvents(analysis)
                .analysisSummary(summary)
                .configFingerprint(fingerprint)
                .atrRisk(new LinkedHashMap<String, Object>())
                .engine("session")
                .degradedBranches(degradedNames)
                .sessionEvents(events)
                .sessionBranchStats(branchStats)
                .build();
    }

    static String resolveFillMode(QuantProperties cfg, QuantProperties.Session sessCfg) {
        String raw = sessCfg == null || sessCfg.getFillMode() == null ? "AUTO" : sessCfg.getFillMode().trim();
        if (raw.isEmpty() || "AUTO".equalsIgnoreCase(raw)) {
            boolean next = cfg == null || cfg.isNextBarOpenFill();
            return next ? "NEXT_EFFECTIVE" : "BAR_CLOSE";
        }
        if ("NEXT_EFFECTIVE".equalsIgnoreCase(raw) || "CLASSIC".equalsIgnoreCase(raw)
                || "NEXT_BAR".equalsIgnoreCase(raw)) {
            return "NEXT_EFFECTIVE";
        }
        return "BAR_CLOSE";
    }

    static boolean isNextEffective(String fillMode) {
        return "NEXT_EFFECTIVE".equalsIgnoreCase(fillMode);
    }

    private BigDecimal acceptIntents(SessionStrategy strategy, SessionContext ctx, List<BarDTO> bars, int index,
                                     BigDecimal cash, PositionState pos, List<SessionEvent> events,
                                     List<BackTradeRecord> trades, List<BackTestResult.MarkPoint> buyMarks,
                                     List<BackTestResult.MarkPoint> sellMarks, BranchStatsAcc stats,
                                     boolean matchingEnabled, String fillMode, boolean nextEffective,
                                     List<PendingOrder> pendingBuys, List<PendingOrder> pendingSells) {
        if (!matchingEnabled || strategy == null || ctx == null || ctx.isBranchDegraded()) {
            return cash;
        }
        List<SessionOrderIntent> intents = strategy.pollIntents(ctx);
        if (intents == null || intents.isEmpty()) {
            return cash;
        }
        BigDecimal cashNow = cash;
        for (SessionOrderIntent intent : intents) {
            if (intent == null || intent.getSide() == null) {
                continue;
            }
            if (nextEffective) {
                enqueueIntent(intent, ctx, cashNow, events, pendingBuys, pendingSells);
            } else if (intent.getSide() == SessionOrderIntent.Side.BUY) {
                cashNow = executeBuy(intent, ctx, bars, index, cashNow, pos, events, trades, buyMarks, stats,
                        fillMode, false);
            } else {
                cashNow = executeSell(intent, ctx, bars, index, cashNow, pos, events, trades, sellMarks, stats,
                        fillMode, false);
            }
            ctx.setCash(cashNow);
            ctx.setPositionShares(pos.getShares());
            ctx.setSellableShares(pos.sellableShares(ctx.getSessionDay()));
            ctx.setEquity(markEquity(cashNow, pos, ctx.getBar()));
        }
        return cashNow;
    }

    private void enqueueIntent(SessionOrderIntent intent, SessionContext ctx, BigDecimal cash,
                               List<SessionEvent> events, List<PendingOrder> pendingBuys,
                               List<PendingOrder> pendingSells) {
        if (intent.getSide() == SessionOrderIntent.Side.BUY) {
            if (ctx.getPositionShares() > 0) {
                return;
            }
            for (PendingOrder p : pendingBuys) {
                if (p != null) {
                    return; // 已有挂买
                }
            }
            BigDecimal ref = ctx.getBar() != null && ctx.getBar().getClose() != null
                    ? ctx.getBar().getClose() : BigDecimal.ONE;
            int vol = intent.getVolume();
            if (vol <= 0) {
                vol = positionAmountUtil.calcBuyVolume(cash, ref, p().getBaseAtr());
            }
            vol = (vol / 100) * 100;
            if (vol < 100) {
                events.add(ev(ctx, "REJECT_BUY", "挂单量不足一手；" + nullToEmpty(intent.getReason())));
                return;
            }
            pendingBuys.add(new PendingOrder(SessionOrderIntent.Side.BUY, vol, ctx.getSessionDay(),
                    ctx.getBranch(), intent.getReason(), intent.isBypassParticipationCap()));
            events.add(ev(ctx, "PEND_BUY", "vol=" + vol + " 待次日≥09:45开盘；" + nullToEmpty(intent.getReason())));
        } else {
            if (ctx.getPositionShares() <= 0) {
                return;
            }
            if (!pendingSells.isEmpty()) {
                return;
            }
            int vol = intent.getVolume();
            pendingSells.add(new PendingOrder(SessionOrderIntent.Side.SELL, vol, ctx.getSessionDay(),
                    ctx.getBranch(), intent.getReason(), intent.isBypassParticipationCap()));
            events.add(ev(ctx, "PEND_SELL", "待次日≥09:45开盘；" + nullToEmpty(intent.getReason())));
        }
    }

    private BigDecimal tryFillPendings(String stockCode, List<BarDTO> bars, int index, LocalDate tradeDay,
                                       SessionBranch branch, BigDecimal cash, PositionState pos,
                                       List<SessionEvent> events, List<BackTradeRecord> trades,
                                       List<BackTestResult.MarkPoint> buyMarks,
                                       List<BackTestResult.MarkPoint> sellMarks, BranchStatsAcc stats,
                                       List<PendingOrder> pendingBuys, List<PendingOrder> pendingSells,
                                       String fillMode) {
        if (!FillTimingHelper.canFillPendingOnBar(bars, index)) {
            return cash;
        }
        // 过期挂买
        java.util.Iterator<PendingOrder> it = pendingBuys.iterator();
        while (it.hasNext()) {
            PendingOrder p = it.next();
            if (p.signalDay != null && tradeDay.isAfter(p.signalDay.plusDays(PENDING_BUY_EXPIRE_DAYS))) {
                events.add(SessionEvent.builder()
                        .time(FMT.format(bars.get(index).getBarBegin()))
                        .type("EXPIRE_BUY")
                        .branch(branch == null ? null : branch.name())
                        .detail("挂买超过 " + PENDING_BUY_EXPIRE_DAYS + " 日历日")
                        .build());
                it.remove();
            }
        }
        SessionContext fillCtx = baseCtx(stockCode, tradeDay, branch, bars.get(index), index,
                HoldDayState.FLAT, markEquity(cash, pos, bars.get(index)), cash, pos,
                java.util.Collections.<SessionBranch>emptySet(), true);
        // 先卖后买；仅成交成功才清挂单（拒单保留待下一可撮合分钟或过期）
        if (!pendingSells.isEmpty()) {
            PendingOrder ps = pendingSells.get(0);
            if (ps.signalDay != null && tradeDay.isAfter(ps.signalDay)) {
                SessionOrderIntent intent = SessionOrderIntent.builder()
                        .side(SessionOrderIntent.Side.SELL)
                        .volume(ps.volume)
                        .reason(ps.reason)
                        .bypassParticipationCap(ps.bypassCap)
                        .build();
                fillCtx.setBranch(ps.signalBranch != null ? ps.signalBranch : branch);
                int before = trades.size();
                cash = executeSell(intent, fillCtx, bars, index, cash, pos, events, trades, sellMarks, stats,
                        fillMode, true);
                if (trades.size() > before) {
                    pendingSells.clear();
                }
            }
        }
        if (!pendingBuys.isEmpty() && !pos.hasPosition()) {
            PendingOrder pb = pendingBuys.get(0);
            if (pb.signalDay != null && tradeDay.isAfter(pb.signalDay)) {
                SessionOrderIntent intent = SessionOrderIntent.builder()
                        .side(SessionOrderIntent.Side.BUY)
                        .volume(pb.volume)
                        .reason(pb.reason)
                        .bypassParticipationCap(pb.bypassCap)
                        .build();
                fillCtx.setCash(cash);
                fillCtx.setBranch(pb.signalBranch != null ? pb.signalBranch : branch);
                int before = trades.size();
                cash = executeBuy(intent, fillCtx, bars, index, cash, pos, events, trades, buyMarks, stats,
                        fillMode, true);
                if (trades.size() > before) {
                    pendingBuys.clear();
                }
            }
        }
        return cash;
    }

    private BigDecimal executeBuy(SessionOrderIntent intent, SessionContext ctx, List<BarDTO> bars, int index,
                                  BigDecimal cash, PositionState pos, List<SessionEvent> events,
                                  List<BackTradeRecord> trades, List<BackTestResult.MarkPoint> buyMarks,
                                  BranchStatsAcc stats, String fillMode, boolean useOpen) {
        BarDTO bar = ctx.getBar();
        if (bar == null || cash == null) {
            return cash;
        }
        BigDecimal base = useOpen
                ? (bar.getOpen() != null ? bar.getOpen() : bar.getClose())
                : bar.getClose();
        if (base == null) {
            return cash;
        }
        int vol = intent.getVolume();
        if (vol <= 0) {
            vol = positionAmountUtil.calcBuyVolume(cash, base, p().getBaseAtr());
        }
        vol = (vol / 100) * 100;
        BigDecimal equityForCap = markEquity(cash, pos, bar);
        if (!intent.isBypassParticipationCap()) {
            vol = SessionParticipation.capVolume(vol, bars, index, p(), equityForCap, false);
        }
        if (vol < 100) {
            events.add(ev(ctx, "REJECT_BUY", "量不足一手或 ADV/AUM/POV 拒绝；" + nullToEmpty(intent.getReason())));
            return cash;
        }
        BigDecimal deal = tradeCostModel.buyPrice(base, bars, index, vol);
        BigDecimal prev = prevClose(bars, index);
        if (p().isLimitPriceProtectEnabled()) {
            if (LimitPriceProtect.shouldRejectBuy(base, prev, ctx.getStockCode(), false)) {
                events.add(ev(ctx, "REJECT_BUY", "涨停拒买；" + nullToEmpty(intent.getReason())));
                return cash;
            }
            deal = LimitPriceProtect.clampBuy(deal, prev, ctx.getStockCode(), false);
        }
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.buyFee(amount, p().getFeeRate());
        BigDecimal need = amount.add(fee);
        if (need.compareTo(cash) > 0) {
            int afford = cash.subtract(fee.max(new BigDecimal("5")))
                    .divide(deal, 0, RoundingMode.DOWN).intValue();
            afford = (afford / 100) * 100;
            if (afford < 100) {
                events.add(ev(ctx, "REJECT_BUY", "现金不足；" + nullToEmpty(intent.getReason())));
                return cash;
            }
            vol = afford;
            amount = deal.multiply(BigDecimal.valueOf(vol));
            fee = tradeCostModel.buyFee(amount, p().getFeeRate());
            need = amount.add(fee);
        }
        cash = cash.subtract(need);
        pos.addBuy(vol, deal, fee, ctx.getSessionDay());
        trades.add(BackTradeRecord.builder()
                .stockCode(ctx.getStockCode())
                .side("BUY")
                .tradeTime(bar.getBarBegin())
                .price(deal)
                .volume(vol)
                .fee(fee)
                .amount(amount)
                .build());
        buyMarks.add(BackTestResult.MarkPoint.builder().time(FMT.format(bar.getBarBegin())).price(deal).build());
        stats.buy(ctx.getBranch(), amount);
        events.add(ev(ctx, "FILL_BUY", "vol=" + vol + " px=" + deal + " fee=" + fee
                + " mode=" + fillMode + (useOpen ? " open" : " close")
                + " " + nullToEmpty(intent.getReason())));
        return cash;
    }

    private BigDecimal executeSell(SessionOrderIntent intent, SessionContext ctx, List<BarDTO> bars, int index,
                                   BigDecimal cash, PositionState pos, List<SessionEvent> events,
                                   List<BackTradeRecord> trades, List<BackTestResult.MarkPoint> sellMarks,
                                   BranchStatsAcc stats, String fillMode, boolean useOpen) {
        BarDTO bar = ctx.getBar();
        if (bar == null || !pos.hasPosition()) {
            return cash;
        }
        BigDecimal base = useOpen
                ? (bar.getOpen() != null ? bar.getOpen() : bar.getClose())
                : bar.getClose();
        if (base == null) {
            return cash;
        }
        int sellable = pos.sellableShares(ctx.getSessionDay());
        int vol = intent.getVolume() <= 0 ? sellable : Math.min(intent.getVolume(), sellable);
        vol = (vol / 100) * 100;
        if (vol < 100) {
            events.add(ev(ctx, "REJECT_SELL", "T+1 无可卖老仓；" + nullToEmpty(intent.getReason())));
            return cash;
        }
        if (!intent.isBypassParticipationCap()) {
            BigDecimal equityForCap = markEquity(cash, pos, bar);
            vol = SessionParticipation.capVolume(vol, bars, index, p(), equityForCap, false);
            if (vol < 100) {
                events.add(ev(ctx, "REJECT_SELL", "ADV/AUM/POV 拒绝卖出；" + nullToEmpty(intent.getReason())));
                return cash;
            }
        }
        BigDecimal deal = tradeCostModel.sellPrice(base, bars, index, vol);
        BigDecimal prev = prevClose(bars, index);
        if (p().isLimitPriceProtectEnabled()) {
            deal = LimitPriceProtect.clampSell(deal, prev, ctx.getStockCode(), false);
        }
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.sellFee(amount, p().getFeeRate(), ctx.getSessionDay());
        BigDecimal removedCost = pos.removeShares(vol);
        cash = cash.add(amount).subtract(fee);
        BigDecimal pnl = amount.subtract(fee).subtract(removedCost);
        trades.add(BackTradeRecord.builder()
                .stockCode(ctx.getStockCode())
                .side("SELL")
                .tradeTime(bar.getBarBegin())
                .price(deal)
                .volume(vol)
                .fee(fee)
                .amount(amount)
                .build());
        sellMarks.add(BackTestResult.MarkPoint.builder().time(FMT.format(bar.getBarBegin())).price(deal).build());
        stats.sell(ctx.getBranch(), amount, pnl);
        events.add(ev(ctx, "FILL_SELL", "vol=" + vol + " px=" + deal + " fee=" + fee
                + " pnl=" + pnl.setScale(2, RoundingMode.HALF_UP)
                + " mode=" + fillMode + (useOpen ? " open" : " close")
                + " " + nullToEmpty(intent.getReason())));
        return cash;
    }

    private static HoldDayState syncHold(HoldDayState hold, PositionState pos) {
        if (pos == null || !pos.hasPosition()) {
            return HoldDayState.FLAT;
        }
        return hold == null || hold == HoldDayState.FLAT ? HoldDayState.HOLD_D0 : hold;
    }

    private static BigDecimal markEquity(BigDecimal cash, PositionState pos, BarDTO bar) {
        BigDecimal c = cash == null ? BigDecimal.ZERO : cash;
        if (pos == null || !pos.hasPosition() || bar == null || bar.getClose() == null) {
            return c;
        }
        return c.add(bar.getClose().multiply(BigDecimal.valueOf(pos.getShares())));
    }

    private SessionContext baseCtx(String code, LocalDate day, SessionBranch branch, BarDTO bar, int i,
                                   HoldDayState hold, BigDecimal equity, BigDecimal cash, PositionState pos,
                                   Set<SessionBranch> degraded, boolean matchingEnabled) {
        return SessionContext.builder()
                .stockCode(code)
                .sessionDay(day)
                .branch(branch)
                .bar(bar)
                .barIndex(i)
                .holdState(hold)
                .equity(equity)
                .cash(cash)
                .positionShares(pos == null ? 0 : pos.getShares())
                .sellableShares(pos == null ? 0 : pos.sellableShares(day))
                .matchingEnabled(matchingEnabled)
                .degradedBranches(new LinkedHashSet<SessionBranch>(degraded))
                .build();
    }

    private static SessionEvent ev(SessionContext ctx, String type, String detail) {
        String time = "";
        if (ctx.getBar() != null && ctx.getBar().getBarBegin() != null) {
            time = FMT.format(ctx.getBar().getBarBegin());
        }
        return SessionEvent.builder()
                .time(time)
                .type(type)
                .branch(ctx.getBranch() == null ? null : ctx.getBranch().name())
                .detail(detail)
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean nextBarNewDay(List<BarDTO> bars, int i, LocalDate day) {
        if (i + 1 >= bars.size()) {
            return true;
        }
        BarDTO n = bars.get(i + 1);
        if (n == null || n.getBarBegin() == null) {
            return true;
        }
        return !n.getBarBegin().toLocalDate().equals(day);
    }

    private static BigDecimal prevClose(List<BarDTO> bars, int index) {
        if (bars == null || index <= 0) {
            return null;
        }
        LocalDate day = bars.get(index).getBarBegin() == null ? null : bars.get(index).getBarBegin().toLocalDate();
        for (int i = index - 1; i >= 0; i--) {
            BarDTO b = bars.get(i);
            if (b == null || b.getBarBegin() == null || b.getClose() == null) {
                continue;
            }
            if (day != null && b.getBarBegin().toLocalDate().isBefore(day)) {
                return b.getClose();
            }
        }
        return bars.get(Math.max(0, index - 1)).getClose();
    }

    private List<AnalysisEvent> toAnalysis(String code, List<SessionEvent> events) {
        List<AnalysisEvent> out = new ArrayList<AnalysisEvent>();
        if (events == null) {
            return out;
        }
        int cap = Math.min(events.size(), 500);
        for (int i = 0; i < cap; i++) {
            SessionEvent e = events.get(i);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("branch", e.getBranch());
            data.put("detail", e.getDetail());
            out.add(AnalysisEvent.builder()
                    .type(e.getType() == null ? "SESSION" : e.getType())
                    .time(e.getTime())
                    .stockCode(code)
                    .title(e.getType())
                    .reason(e.getDetail())
                    .data(data)
                    .build());
        }
        return out;
    }

    private String sessionFingerprint(QuantProperties cfg, String strategyId, boolean failOnMissingDep,
                                      SessionWindows windows, boolean matchingEnabled, String fillMode) {
        QuantProperties use = cfg == null ? p() : cfg;
        String classic = ConfigFingerprint.of(use, strategyId == null ? "branchScaffold" : strategyId,
                use.getFeeRate());
        String extra = "engine=session|failOnMissingDep=" + failOnMissingDep
                + "|" + windows.fingerprintPart()
                + "|match=" + matchingEnabled
                + "|fill=" + fillMode;
        return classic + "|sess:" + Integer.toHexString(extra.hashCode());
    }

    /** 分分支累计。 */
    static final class BranchStatsAcc {
        private final EnumMap<SessionBranch, Integer> ticks = new EnumMap<SessionBranch, Integer>(SessionBranch.class);
        private final EnumMap<SessionBranch, Integer> buys = new EnumMap<SessionBranch, Integer>(SessionBranch.class);
        private final EnumMap<SessionBranch, Integer> sells = new EnumMap<SessionBranch, Integer>(SessionBranch.class);
        private final EnumMap<SessionBranch, BigDecimal> buyAmt = new EnumMap<SessionBranch, BigDecimal>(SessionBranch.class);
        private final EnumMap<SessionBranch, BigDecimal> sellAmt = new EnumMap<SessionBranch, BigDecimal>(SessionBranch.class);
        private final EnumMap<SessionBranch, BigDecimal> realized = new EnumMap<SessionBranch, BigDecimal>(SessionBranch.class);
        int winRounds;
        int closedRounds;

        BranchStatsAcc() {
            for (SessionBranch b : SessionBranch.values()) {
                ticks.put(b, 0);
                buys.put(b, 0);
                sells.put(b, 0);
                buyAmt.put(b, BigDecimal.ZERO);
                sellAmt.put(b, BigDecimal.ZERO);
                realized.put(b, BigDecimal.ZERO);
            }
        }

        void tick(SessionBranch b) {
            if (b != null) {
                ticks.put(b, ticks.get(b) + 1);
            }
        }

        int ticks(SessionBranch b) {
            return b == null ? 0 : ticks.get(b);
        }

        void buy(SessionBranch b, BigDecimal amount) {
            if (b == null) {
                return;
            }
            buys.put(b, buys.get(b) + 1);
            buyAmt.put(b, buyAmt.get(b).add(amount == null ? BigDecimal.ZERO : amount));
        }

        void sell(SessionBranch b, BigDecimal amount, BigDecimal pnl) {
            if (b == null) {
                return;
            }
            sells.put(b, sells.get(b) + 1);
            sellAmt.put(b, sellAmt.get(b).add(amount == null ? BigDecimal.ZERO : amount));
            realized.put(b, realized.get(b).add(pnl == null ? BigDecimal.ZERO : pnl));
            closedRounds++;
            if (pnl != null && pnl.signum() > 0) {
                winRounds++;
            }
        }

        Map<String, Object> toMap(int sessionDays, boolean matchingEnabled, String fillMode, SessionWindows windows) {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("sessionDays", sessionDays);
            root.put("matchingEnabled", matchingEnabled);
            root.put("fillMode", fillMode);
            root.put("windows", windows == null ? Collections.emptyMap() : windows.fingerprintPart());
            for (SessionBranch b : SessionBranch.values()) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("branchTicks", ticks.get(b));
                m.put("buys", buys.get(b));
                m.put("sells", sells.get(b));
                m.put("buyAmount", buyAmt.get(b));
                m.put("sellAmount", sellAmt.get(b));
                m.put("realizedPnl", realized.get(b).setScale(2, RoundingMode.HALF_UP));
                root.put(b.name(), m);
            }
            return root;
        }
    }
}
