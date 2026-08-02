package com.quant.stock.session;

import com.quant.stock.admin.ParamsScope;
import com.quant.stock.backtest.FillTimingHelper;
import com.quant.stock.backtest.PositionState;
import com.quant.stock.backtest.dto.AnalysisEvent;
import com.quant.stock.backtest.dto.BackTestQueryDTO;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.backtest.dto.PortfolioResultDTO;
import com.quant.stock.backtest.dto.SingleStockBackResult;
import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.portfolio.PortfolioCorrelationMonitor;
import com.quant.stock.risk.AccountRiskState;
import com.quant.stock.risk.LimitPriceProtect;
import com.quant.stock.risk.StressScenarioService;
import com.quant.stock.risk.StructuralBreakMonitor;
import com.quant.stock.trade.FillVolumeScale;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 组合会话回测：MIN_1 并集分钟轴 + 共享现金池 + {@link AccountRiskState}。
 * 不跑金叉五步；单股路径仍用 {@link SessionBackTestEngine}。
 */
@Service
@RequiredArgsConstructor
public class SessionPortfolioBackTestEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MIN_BARS = 30;
    private static final int PENDING_BUY_EXPIRE_DAYS = 5;

    private final MarketDataService marketDataService;
    private final SessionDepProbe depProbe;
    private final QuantProperties props;
    private final TradeCostModel tradeCostModel;
    private final PositionAmountUtil positionAmountUtil;

    private QuantProperties p() {
        return ParamsScope.current(props);
    }

    public PortfolioResultDTO run(BackTestQueryDTO query, SessionStrategy strategy, boolean failOnMissingDep) {
        if (strategy == null) {
            throw new IllegalArgumentException("engine=session 需要 SessionStrategy");
        }
        BigDecimal initCapital = query == null || query.getInitCapital() == null
                ? new BigDecimal("100000") : query.getInitCapital();
        BigDecimal commissionRate = query != null && query.getFeeRate() != null
                ? query.getFeeRate() : p().getFeeRate();
        String fingerprint = ConfigFingerprint.of(p(), strategy.sessionId(), commissionRate) + "|pfSessShared";

        if (query == null || query.getStockCodeList() == null || query.getStockCodeList().isEmpty()) {
            PortfolioResultDTO empty = PortfolioResultDTO.empty(BigDecimal.ZERO);
            empty.setEngine("session");
            empty.setConfigFingerprint(fingerprint);
            return empty;
        }

        List<String> codes = new ArrayList<String>();
        for (String c : query.getStockCodeList()) {
            if (c != null && !c.trim().isEmpty()) {
                codes.add(c.trim());
            }
        }
        if (codes.isEmpty()) {
            PortfolioResultDTO empty = PortfolioResultDTO.empty(initCapital);
            empty.setEngine("session");
            empty.setConfigFingerprint(fingerprint);
            return empty;
        }

        QuantProperties cfg = p();
        SessionWindows windows = SessionWindows.from(cfg);
        QuantProperties.Session sessCfg = cfg.getSession() == null ? new QuantProperties.Session() : cfg.getSession();
        boolean matchingEnabled = sessCfg.isMatchingEnabled();
        String fillMode = SessionBackTestEngine.resolveFillMode(cfg, sessCfg);
        boolean nextEffective = SessionBackTestEngine.isNextEffective(fillMode);

        List<Leg> legs = new ArrayList<Leg>();
        TreeSet<LocalDateTime> timeSet = new TreeSet<LocalDateTime>();
        Set<String> degradedNames = new LinkedHashSet<String>();

        for (String code : codes) {
            List<BarDTO> bars = marketDataService.getKline(code, BarPeriod.MIN_1,
                    query.getBackStart(), query.getBackEnd());
            if (bars == null || bars.size() < MIN_BARS) {
                continue;
            }
            Set<DataDep> required = strategy.dataDeps() == null
                    ? EnumSet.of(DataDep.MIN1) : EnumSet.copyOf(strategy.dataDeps());
            Set<DataDep> missing = depProbe.probeUnavailable(code, query.getBackStart(), query.getBackEnd(), required);
            if (failOnMissingDep && missing != null && !missing.isEmpty()) {
                throw new IllegalArgumentException("会话组合回测缺依赖: " + missing
                        + " code=" + code + "（failOnMissingDep=true）");
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
            for (SessionBranch b : degraded) {
                degradedNames.add(b.name());
            }
            Leg leg = new Leg(code, bars, strategy, degraded);
            legs.add(leg);
            for (BarDTO b : bars) {
                if (b == null || b.getBarBegin() == null) {
                    continue;
                }
                LocalTime lt = b.getBarBegin().toLocalTime();
                if (SessionTradingMinutes.isTradingMinute(lt) && windows.of(lt) != null) {
                    timeSet.add(b.getBarBegin());
                }
            }
        }

        if (legs.isEmpty()) {
            PortfolioResultDTO empty = PortfolioResultDTO.empty(initCapital);
            empty.setEngine("session");
            empty.setConfigFingerprint(fingerprint);
            empty.setAnalysisSummary("engine=session sharedCash：无足够 MIN_1 成分股");
            return empty;
        }

        AccountRiskState accountRisk = new AccountRiskState(cfg);
        accountRisk.reset(initCapital);
        BigDecimal cash = initCapital;
        List<BackTradeRecord> trades = new ArrayList<BackTradeRecord>();
        List<AnalysisEvent> analysis = new ArrayList<AnalysisEvent>();
        List<String> equityTimes = new ArrayList<String>();
        List<BigDecimal> equityCurve = new ArrayList<BigDecimal>();
        BigDecimal peak = initCapital;
        BigDecimal maxDd = BigDecimal.ZERO;
        int sessionDays = 0;
        LocalDate portfolioDay = null;
        int step = 0;
        List<LocalDateTime> timeline = new ArrayList<LocalDateTime>(timeSet);

        for (LocalDateTime t : timeline) {
            LocalDate day = t.toLocalDate();
            LocalTime lt = t.toLocalTime();
            SessionBranch branch = windows.of(lt);
            if (branch == null) {
                step++;
                continue;
            }

            boolean newPortfolioDay = portfolioDay == null || !portfolioDay.equals(day);
            if (newPortfolioDay) {
                portfolioDay = day;
                sessionDays++;
                for (Leg leg : legs) {
                    leg.pos.clearAddedToday();
                    leg.seenBranchToday = EnumSet.noneOf(SessionBranch.class);
                }
            }

            // —— 1) 撮合到期挂单：先全体卖，再全体买 ——
            if (matchingEnabled && nextEffective) {
                for (Leg leg : legs) {
                    int idx = findIndex(leg.bars, t, leg.hint);
                    if (idx < 0) {
                        continue;
                    }
                    leg.hint = idx;
                    cash = tryFillSells(leg, idx, day, branch, cash, trades, fillMode, accountRisk);
                }
                BigDecimal equityPreBuy = calcEquity(cash, legs, t);
                for (Leg leg : legs) {
                    int idx = findIndex(leg.bars, t, leg.hint);
                    if (idx < 0) {
                        continue;
                    }
                    leg.hint = idx;
                    cash = tryFillBuys(leg, idx, day, branch, cash, trades, fillMode,
                            accountRisk, equityPreBuy, legs, t);
                }
            }

            // —— 2) 会话钩子 + 意图 ——
            for (Leg leg : legs) {
                int idx = findIndex(leg.bars, t, leg.hint);
                if (idx < 0) {
                    continue;
                }
                leg.hint = idx;
                BarDTO bar = leg.bars.get(idx);
                if (bar.getClose() != null) {
                    leg.lastMarkPrice = bar.getClose();
                }

                boolean newLegDay = leg.currentDay == null || !leg.currentDay.equals(day);
                if (newLegDay) {
                    if (leg.currentDay != null && leg.lastBarOfDay != null) {
                        cash = runSessionClose(leg, cash, trades, matchingEnabled, fillMode, nextEffective);
                    }
                    leg.currentDay = day;
                    BigDecimal eq = calcEquity(cash, legs, t);
                    SessionContext openCtx = baseCtx(leg, day, branch, bar, idx, eq, cash, matchingEnabled);
                    leg.strategy.onSessionOpen(openCtx, leg.events);
                    leg.hold = openCtx.getHoldState() == null ? leg.hold : openCtx.getHoldState();
                    cash = acceptIntents(leg, openCtx, idx, cash, trades, matchingEnabled, fillMode, nextEffective,
                            accountRisk, eq, legs, t);
                    leg.hold = syncHold(leg.hold, leg.pos);
                }
                leg.lastBarOfDay = bar;
                leg.lastBarIndexOfDay = idx;

                boolean firstOfBranch = leg.seenBranchToday.add(branch);
                if (firstOfBranch) {
                    leg.stats.tick(branch);
                }
                BigDecimal equity = calcEquity(cash, legs, t);
                SessionContext ctx = baseCtx(leg, day, branch, bar, idx, equity, cash, matchingEnabled);
                if (ctx.isBranchDegraded()) {
                    if (firstOfBranch) {
                        leg.events.add(SessionEvent.builder()
                                .time(FMT.format(t))
                                .type("BRANCH_UNAVAILABLE")
                                .branch(branch.name())
                                .detail("分支降级跳过钩子；缺失依赖影响")
                                .build());
                    }
                } else {
                    boolean runBranch = firstOfBranch || leg.strategy.tickEveryBar();
                    if (runBranch) {
                        leg.strategy.onBranchBar(ctx, leg.events);
                        leg.hold = ctx.getHoldState() == null ? leg.hold : ctx.getHoldState();
                        cash = acceptIntents(leg, ctx, idx, cash, trades, matchingEnabled, fillMode, nextEffective,
                                accountRisk, equity, legs, t);
                        leg.hold = syncHold(leg.hold, leg.pos);
                    }
                }
            }

            BigDecimal equity = calcEquity(cash, legs, t);
            accountRisk.onEquity(day, equity);

            // 熔断：有仓腿挂全卖
            if (accountRisk.isHalted() && matchingEnabled) {
                for (Leg leg : legs) {
                    if (!leg.pos.hasPosition()) {
                        continue;
                    }
                    int idx = findIndex(leg.bars, t, leg.hint);
                    if (idx < 0) {
                        continue;
                    }
                    SessionContext haltCtx = baseCtx(leg, day, branch, leg.bars.get(idx), idx, equity, cash, true);
                    if (nextEffective) {
                        enqueueSellAll(leg, haltCtx, "account-halt");
                    } else if (leg.pos.sellableShares(day) >= 100) {
                        SessionOrderIntent sell = SessionOrderIntent.sellAll("account-halt");
                        cash = executeSell(leg, sell, idx, cash, trades, fillMode, false, accountRisk);
                    }
                }
            }

            equity = calcEquity(cash, legs, t);
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(equity).divide(peak, 6, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
            if (step % 20 == 0 || step == timeline.size() - 1) {
                equityTimes.add(FMT.format(t));
                equityCurve.add(equity);
            }
            step++;
        }

        // 收尾日末钩子
        for (Leg leg : legs) {
            if (leg.currentDay != null && leg.lastBarOfDay != null) {
                cash = runSessionClose(leg, cash, trades, matchingEnabled, fillMode, nextEffective);
            }
        }

        BigDecimal finalAsset = equityCurve.isEmpty()
                ? calcEquity(cash, legs, timeline.isEmpty() ? null : timeline.get(timeline.size() - 1))
                : equityCurve.get(equityCurve.size() - 1);
        BigDecimal totalRate = initCapital.signum() == 0 ? BigDecimal.ZERO
                : finalAsset.subtract(initCapital).divide(initCapital, 6, RoundingMode.HALF_UP);

        int winRounds = 0;
        int closedRounds = 0;
        Map<String, Object> branchAgg = new LinkedHashMap<String, Object>();
        branchAgg.put("mode", "SHARED_CASH_SESSION");
        branchAgg.put("legs", legs.size());
        branchAgg.put("fillMode", fillMode);
        branchAgg.put("matchingEnabled", matchingEnabled);
        branchAgg.put("sessionDays", sessionDays);
        branchAgg.put("halted", accountRisk.isHalted());
        branchAgg.put("haltReason", accountRisk.getHaltReason());
        List<SessionEvent> sessionEvents = new ArrayList<SessionEvent>();
        List<SingleStockBackResult> stockResults = new ArrayList<SingleStockBackResult>();
        for (Leg leg : legs) {
            winRounds += leg.stats.winRounds;
            closedRounds += leg.stats.closedRounds;
            branchAgg.put(leg.code, leg.stats.toMap(sessionDays, matchingEnabled, fillMode, windows));
            for (SessionEvent e : leg.events) {
                if (sessionEvents.size() < 800) {
                    // 事件 detail 带头腿代码，便于组合面板区分
                    SessionEvent tagged = SessionEvent.builder()
                            .time(e.getTime())
                            .type(e.getType())
                            .branch(e.getBranch())
                            .detail((e.getDetail() == null ? "" : e.getDetail()) + " [" + leg.code + "]")
                            .build();
                    sessionEvents.add(tagged);
                }
                if (analysis.size() >= 800) {
                    continue;
                }
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("branch", e.getBranch());
                data.put("detail", e.getDetail());
                analysis.add(AnalysisEvent.builder()
                        .type(e.getType() == null ? "SESSION" : e.getType())
                        .time(e.getTime())
                        .stockCode(leg.code)
                        .title(e.getType())
                        .reason(e.getDetail())
                        .data(data)
                        .build());
            }
            BigDecimal legMv = leg.pos.hasPosition() && leg.lastMarkPrice != null
                    ? leg.lastMarkPrice.multiply(BigDecimal.valueOf(leg.pos.getShares()))
                    : BigDecimal.ZERO;
            stockResults.add(SingleStockBackResult.builder()
                    .stockCode(leg.code)
                    .totalTradeNum(countTrades(trades, leg.code))
                    .finalAsset(legMv)
                    .winRate(leg.stats.closedRounds <= 0 ? BigDecimal.ZERO
                            : BigDecimal.valueOf(leg.stats.winRounds)
                            .divide(BigDecimal.valueOf(leg.stats.closedRounds), 4, RoundingMode.HALF_UP))
                    .build());
        }
        BigDecimal winRate = closedRounds <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(winRounds).divide(BigDecimal.valueOf(closedRounds), 4, RoundingMode.HALF_UP);

        String summary = String.format(Locale.ROOT,
                "engine=session sharedCash legs=%d init=%s final=%s trades=%d degraded=%s fill=%s",
                legs.size(), initCapital, finalAsset, trades.size(), degradedNames, fillMode);

        Map<String, List<BigDecimal>> dayCloses = dailyCloseSeries(legs);
        Map<String, Object> correlation = PortfolioCorrelationMonitor.report(
                dayCloses, cfg.getCorrelationLookbackDays(), cfg.getCorrelationWarnThreshold());

        return PortfolioResultDTO.builder()
                .initCapital(initCapital)
                .finalAsset(finalAsset)
                .totalRate(totalRate)
                .maxDrawDown(maxDd)
                .totalTradeNum(trades.size())
                .winRate(winRate)
                .equityTimes(equityTimes)
                .equityCurve(equityCurve)
                .stockResults(stockResults)
                .trades(trades)
                .analysisEvents(analysis)
                .analysisSummary(summary)
                .configFingerprint(fingerprint)
                .correlation(correlation)
                .atrRisk(new LinkedHashMap<String, Object>())
                .engine("session")
                .degradedBranches(new ArrayList<String>(degradedNames))
                .sessionBranchStats(branchAgg)
                .sessionEvents(sessionEvents)
                .build();
    }

    /** 由分钟序列取各交易日最后一根收盘，供相关监控按「日收益」计算。 */
    private static Map<String, List<BigDecimal>> dailyCloseSeries(List<Leg> legs) {
        Map<String, List<BigDecimal>> out = new LinkedHashMap<String, List<BigDecimal>>();
        if (legs == null) {
            return out;
        }
        for (Leg leg : legs) {
            List<BigDecimal> series = new ArrayList<BigDecimal>();
            LocalDate lastDay = null;
            BigDecimal lastClose = null;
            if (leg.bars != null) {
                for (BarDTO b : leg.bars) {
                    if (b == null || b.getBarBegin() == null || b.getClose() == null) {
                        continue;
                    }
                    LocalDate d = b.getBarBegin().toLocalDate();
                    if (lastDay != null && !d.equals(lastDay) && lastClose != null) {
                        series.add(lastClose);
                    }
                    lastDay = d;
                    lastClose = b.getClose();
                }
            }
            if (lastClose != null) {
                series.add(lastClose);
            }
            out.put(leg.code, series);
        }
        return out;
    }

    private BigDecimal runSessionClose(Leg leg, BigDecimal cash, List<BackTradeRecord> trades,
                                       boolean matchingEnabled, String fillMode, boolean nextEffective) {
        BigDecimal eq = cash;
        if (leg.lastMarkPrice != null && leg.pos.hasPosition()) {
            eq = cash.add(leg.lastMarkPrice.multiply(BigDecimal.valueOf(leg.pos.getShares())));
        }
        SessionContext closeCtx = baseCtx(leg, leg.currentDay, SessionBranch.CLOSE, leg.lastBarOfDay,
                leg.lastBarIndexOfDay, eq, cash, matchingEnabled);
        leg.strategy.onSessionClose(closeCtx, leg.events);
        leg.hold = closeCtx.getHoldState() == null ? leg.hold : closeCtx.getHoldState();
        // 日末意图：无完整组合上下文时用简化 equity
        return acceptIntents(leg, closeCtx, leg.lastBarIndexOfDay, cash, trades, matchingEnabled, fillMode,
                nextEffective, null, eq, null, null);
    }

    private BigDecimal acceptIntents(Leg leg, SessionContext ctx, int index, BigDecimal cash,
                                     List<BackTradeRecord> trades, boolean matchingEnabled, String fillMode,
                                     boolean nextEffective, AccountRiskState risk, BigDecimal portfolioEquity,
                                     List<Leg> allLegs, LocalDateTime t) {
        if (!matchingEnabled || ctx == null || ctx.isBranchDegraded()) {
            return cash;
        }
        List<SessionOrderIntent> intents = leg.strategy.pollIntents(ctx);
        if (intents == null || intents.isEmpty()) {
            return cash;
        }
        BigDecimal cashNow = cash;
        for (SessionOrderIntent intent : intents) {
            if (intent == null || intent.getSide() == null) {
                continue;
            }
            if (nextEffective) {
                if (intent.getSide() == SessionOrderIntent.Side.BUY) {
                    enqueueBuy(leg, intent, ctx, cashNow);
                } else {
                    enqueueSellAll(leg, ctx, intent.getReason() == null ? "intent" : intent.getReason());
                    if (intent.getVolume() > 0 && !leg.pendingSells.isEmpty()) {
                        PendingOrder last = leg.pendingSells.get(leg.pendingSells.size() - 1);
                        leg.pendingSells.set(leg.pendingSells.size() - 1,
                                new PendingOrder(SessionOrderIntent.Side.SELL, intent.getVolume(),
                                        last.signalDay, last.signalBranch, last.reason, last.bypassCap));
                    }
                }
            } else if (intent.getSide() == SessionOrderIntent.Side.BUY) {
                cashNow = executeBuy(leg, intent, index, cashNow, trades, fillMode, false,
                        risk, portfolioEquity, allLegs, t);
            } else {
                cashNow = executeSell(leg, intent, index, cashNow, trades, fillMode, false, risk);
            }
            ctx.setCash(cashNow);
            ctx.setPositionShares(leg.pos.getShares());
            ctx.setSellableShares(leg.pos.sellableShares(ctx.getSessionDay()));
        }
        return cashNow;
    }

    private void enqueueBuy(Leg leg, SessionOrderIntent intent, SessionContext ctx, BigDecimal cash) {
        if (leg.pos.hasPosition() || !leg.pendingBuys.isEmpty()) {
            return;
        }
        BigDecimal ref = ctx.getBar() != null && ctx.getBar().getClose() != null
                ? ctx.getBar().getClose() : BigDecimal.ONE;
        int vol = intent.getVolume();
        if (vol <= 0) {
            vol = positionAmountUtil.calcBuyVolume(cash, ref, p().getBaseAtr());
        }
        vol = (vol / 100) * 100;
        if (vol < 100) {
            leg.events.add(ev(ctx, "REJECT_BUY", "挂单量不足一手；" + nullToEmpty(intent.getReason())));
            return;
        }
        leg.pendingBuys.add(new PendingOrder(SessionOrderIntent.Side.BUY, vol, ctx.getSessionDay(),
                ctx.getBranch(), intent.getReason(), intent.isBypassParticipationCap()));
        leg.events.add(ev(ctx, "PEND_BUY", "vol=" + vol + " 待次日≥09:45开盘；" + nullToEmpty(intent.getReason())));
    }

    private void enqueueSellAll(Leg leg, SessionContext ctx, String reason) {
        if (!leg.pos.hasPosition() || !leg.pendingSells.isEmpty()) {
            return;
        }
        leg.pendingSells.add(new PendingOrder(SessionOrderIntent.Side.SELL, 0, ctx.getSessionDay(),
                ctx.getBranch(), reason, true));
        leg.events.add(ev(ctx, "PEND_SELL", "待次日≥09:45开盘；" + nullToEmpty(reason)));
    }

    private BigDecimal tryFillSells(Leg leg, int index, LocalDate tradeDay, SessionBranch branch,
                                    BigDecimal cash, List<BackTradeRecord> trades, String fillMode,
                                    AccountRiskState risk) {
        if (!FillTimingHelper.canFillPendingOnBar(leg.bars, index) || leg.pendingSells.isEmpty()) {
            return cash;
        }
        PendingOrder ps = leg.pendingSells.get(0);
        if (ps.signalDay == null || !tradeDay.isAfter(ps.signalDay)) {
            return cash;
        }
        SessionOrderIntent intent = SessionOrderIntent.builder()
                .side(SessionOrderIntent.Side.SELL)
                .volume(ps.volume)
                .reason(ps.reason)
                .bypassParticipationCap(ps.bypassCap)
                .build();
        SessionContext fillCtx = baseCtx(leg, tradeDay,
                ps.signalBranch != null ? ps.signalBranch : branch,
                leg.bars.get(index), index, cash, cash, true);
        int before = trades.size();
        cash = executeSell(leg, intent, index, cash, trades, fillMode, true, risk);
        if (trades.size() > before) {
            leg.pendingSells.clear();
        }
        return cash;
    }

    private BigDecimal tryFillBuys(Leg leg, int index, LocalDate tradeDay, SessionBranch branch,
                                   BigDecimal cash, List<BackTradeRecord> trades, String fillMode,
                                   AccountRiskState risk, BigDecimal portfolioEquity,
                                   List<Leg> allLegs, LocalDateTime t) {
        expireBuys(leg, index, tradeDay, branch);
        if (!FillTimingHelper.canFillPendingOnBar(leg.bars, index) || leg.pendingBuys.isEmpty()
                || leg.pos.hasPosition()) {
            return cash;
        }
        PendingOrder pb = leg.pendingBuys.get(0);
        if (pb.signalDay == null || !tradeDay.isAfter(pb.signalDay)) {
            return cash;
        }
        SessionOrderIntent intent = SessionOrderIntent.builder()
                .side(SessionOrderIntent.Side.BUY)
                .volume(pb.volume)
                .reason(pb.reason)
                .bypassParticipationCap(pb.bypassCap)
                .build();
        int before = trades.size();
        cash = executeBuy(leg, intent, index, cash, trades, fillMode, true,
                risk, portfolioEquity, allLegs, t);
        if (trades.size() > before) {
            leg.pendingBuys.clear();
        }
        return cash;
    }

    private void expireBuys(Leg leg, int index, LocalDate tradeDay, SessionBranch branch) {
        java.util.Iterator<PendingOrder> it = leg.pendingBuys.iterator();
        while (it.hasNext()) {
            PendingOrder p = it.next();
            if (p.signalDay != null && tradeDay.isAfter(p.signalDay.plusDays(PENDING_BUY_EXPIRE_DAYS))) {
                leg.events.add(SessionEvent.builder()
                        .time(FMT.format(leg.bars.get(index).getBarBegin()))
                        .type("EXPIRE_BUY")
                        .branch(branch == null ? null : branch.name())
                        .detail("挂买超过 " + PENDING_BUY_EXPIRE_DAYS + " 日历日")
                        .build());
                it.remove();
            }
        }
    }

    private BigDecimal executeBuy(Leg leg, SessionOrderIntent intent, int index, BigDecimal cash,
                                  List<BackTradeRecord> trades, String fillMode, boolean useOpen,
                                  AccountRiskState risk, BigDecimal portfolioEquity,
                                  List<Leg> allLegs, LocalDateTime t) {
        BarDTO bar = leg.bars.get(index);
        if (bar == null || cash == null) {
            return cash;
        }
        LocalDate tradeDay = bar.getBarBegin() == null ? null : bar.getBarBegin().toLocalDate();
        BigDecimal equity = portfolioEquity != null ? portfolioEquity
                : (allLegs != null && t != null ? calcEquity(cash, allLegs, t) : cash);
        if (risk != null && tradeDay != null && !risk.allowNewOpen(tradeDay, equity)) {
            leg.events.add(evBar(leg, bar, SessionBranch.OPEN, "REJECT_BUY", "账户禁开；" + nullToEmpty(intent.getReason())));
            return cash;
        }
        BigDecimal posScale = resolvePosScale(risk, equity, leg.bars, index);
        if (posScale.compareTo(BigDecimal.ZERO) <= 0) {
            leg.events.add(evBar(leg, bar, SessionBranch.OPEN, "REJECT_BUY", "仓位系数为0；" + nullToEmpty(intent.getReason())));
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
            vol = positionAmountUtil.calcBuyVolume(cash, base, p().getBaseAtr(), posScale);
        } else {
            vol = FillVolumeScale.scaleToLot(vol, posScale);
        }
        vol = (vol / 100) * 100;
        if (!intent.isBypassParticipationCap()) {
            vol = SessionParticipation.capVolume(vol, leg.bars, index, p(), equity, false);
        }
        if (vol < 100) {
            leg.events.add(evBar(leg, bar, SessionBranch.OPEN, "REJECT_BUY",
                    "量不足一手或 ADV/AUM/POV/仓位系数拒绝；" + nullToEmpty(intent.getReason())));
            return cash;
        }
        BigDecimal deal = tradeCostModel.buyPrice(base, leg.bars, index, vol);
        BigDecimal prev = prevClose(leg.bars, index);
        if (p().isLimitPriceProtectEnabled()) {
            if (LimitPriceProtect.shouldRejectBuy(base, prev, leg.code, false)) {
                leg.events.add(evBar(leg, bar, SessionBranch.OPEN, "REJECT_BUY", "涨停拒买；" + nullToEmpty(intent.getReason())));
                return cash;
            }
            deal = LimitPriceProtect.clampBuy(deal, prev, leg.code, false);
        }
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.buyFee(amount, p().getFeeRate());
        BigDecimal need = amount.add(fee);
        BigDecimal posMv = allLegs != null && t != null ? calcPosMv(allLegs, t) : BigDecimal.ZERO;
        if (!positionAmountUtil.withinTotalPosition(equity, posMv, amount)) {
            leg.events.add(evBar(leg, bar, SessionBranch.OPEN, "REJECT_BUY", "突破总仓上限；" + nullToEmpty(intent.getReason())));
            return cash;
        }
        if (need.compareTo(cash) > 0) {
            int afford = cash.subtract(fee.max(new BigDecimal("5")))
                    .divide(deal, 0, RoundingMode.DOWN).intValue();
            afford = (afford / 100) * 100;
            if (afford < 100) {
                leg.events.add(evBar(leg, bar, SessionBranch.OPEN, "REJECT_BUY", "现金不足；" + nullToEmpty(intent.getReason())));
                return cash;
            }
            vol = afford;
            amount = deal.multiply(BigDecimal.valueOf(vol));
            fee = tradeCostModel.buyFee(amount, p().getFeeRate());
            need = amount.add(fee);
            if (!positionAmountUtil.withinTotalPosition(equity, posMv, amount)) {
                leg.events.add(evBar(leg, bar, SessionBranch.OPEN, "REJECT_BUY", "突破总仓上限；" + nullToEmpty(intent.getReason())));
                return cash;
            }
        }
        cash = cash.subtract(need);
        leg.pos.addBuy(vol, deal, fee, tradeDay);
        leg.hold = syncHold(leg.hold, leg.pos);
        trades.add(BackTradeRecord.builder()
                .stockCode(leg.code)
                .side("BUY")
                .tradeTime(bar.getBarBegin())
                .price(deal)
                .volume(vol)
                .fee(fee)
                .amount(amount)
                .build());
        leg.stats.buy(leg.currentBranchOr(SessionBranch.OPEN), amount);
        leg.events.add(evBar(leg, bar, SessionBranch.OPEN, "FILL_BUY",
                "vol=" + vol + " px=" + deal + " fee=" + fee
                        + " mode=" + fillMode + (useOpen ? " open" : " close")
                        + " " + nullToEmpty(intent.getReason())));
        return cash;
    }

    private BigDecimal executeSell(Leg leg, SessionOrderIntent intent, int index, BigDecimal cash,
                                   List<BackTradeRecord> trades, String fillMode, boolean useOpen,
                                   AccountRiskState risk) {
        BarDTO bar = leg.bars.get(index);
        if (bar == null || !leg.pos.hasPosition()) {
            return cash;
        }
        LocalDate tradeDay = bar.getBarBegin() == null ? null : bar.getBarBegin().toLocalDate();
        BigDecimal base = useOpen
                ? (bar.getOpen() != null ? bar.getOpen() : bar.getClose())
                : bar.getClose();
        if (base == null) {
            return cash;
        }
        int sellable = tradeDay == null ? 0 : leg.pos.sellableShares(tradeDay);
        int vol = intent.getVolume() <= 0 ? sellable : Math.min(intent.getVolume(), sellable);
        vol = (vol / 100) * 100;
        if (vol < 100) {
            leg.events.add(evBar(leg, bar, SessionBranch.CLOSE, "REJECT_SELL",
                    "T+1 无可卖老仓；" + nullToEmpty(intent.getReason())));
            return cash;
        }
        if (!intent.isBypassParticipationCap()) {
            BigDecimal eqCap = cash;
            if (leg.lastMarkPrice != null && leg.pos.hasPosition()) {
                eqCap = cash.add(leg.lastMarkPrice.multiply(BigDecimal.valueOf(leg.pos.getShares())));
            }
            vol = SessionParticipation.capVolume(vol, leg.bars, index, p(), eqCap, false);
            if (vol < 100) {
                leg.events.add(evBar(leg, bar, SessionBranch.CLOSE, "REJECT_SELL",
                        "ADV/AUM/POV 拒绝卖出；" + nullToEmpty(intent.getReason())));
                return cash;
            }
        }
        BigDecimal deal = tradeCostModel.sellPrice(base, leg.bars, index, vol);
        BigDecimal prev = prevClose(leg.bars, index);
        if (p().isLimitPriceProtectEnabled()) {
            deal = LimitPriceProtect.clampSell(deal, prev, leg.code, false);
        }
        BigDecimal amount = deal.multiply(BigDecimal.valueOf(vol));
        BigDecimal fee = tradeCostModel.sellFee(amount, p().getFeeRate(), tradeDay);
        BigDecimal removedCost = leg.pos.removeShares(vol);
        cash = cash.add(amount).subtract(fee);
        BigDecimal pnl = amount.subtract(fee).subtract(removedCost);
        boolean cleared = !leg.pos.hasPosition();
        trades.add(BackTradeRecord.builder()
                .stockCode(leg.code)
                .side("SELL")
                .tradeTime(bar.getBarBegin())
                .price(deal)
                .volume(vol)
                .fee(fee)
                .amount(amount)
                .build());
        leg.stats.sell(leg.currentBranchOr(SessionBranch.CLOSE), amount, pnl);
        if (cleared && risk != null && tradeDay != null) {
            risk.onClosedRound(pnl.signum() > 0, tradeDay);
            leg.hold = HoldDayState.FLAT;
        } else {
            leg.hold = syncHold(leg.hold, leg.pos);
        }
        leg.events.add(evBar(leg, bar, SessionBranch.CLOSE, "FILL_SELL",
                "vol=" + vol + " px=" + deal + " fee=" + fee
                        + " pnl=" + pnl.setScale(2, RoundingMode.HALF_UP)
                        + " mode=" + fillMode + (useOpen ? " open" : " close")
                        + " " + nullToEmpty(intent.getReason())));
        return cash;
    }

    private SessionContext baseCtx(Leg leg, LocalDate day, SessionBranch branch, BarDTO bar, int i,
                                   BigDecimal equity, BigDecimal cash, boolean matchingEnabled) {
        return SessionContext.builder()
                .stockCode(leg.code)
                .sessionDay(day)
                .branch(branch)
                .bar(bar)
                .barIndex(i)
                .holdState(leg.hold)
                .equity(equity)
                .cash(cash)
                .positionShares(leg.pos.getShares())
                .sellableShares(leg.pos.sellableShares(day))
                .matchingEnabled(matchingEnabled)
                .degradedBranches(new LinkedHashSet<SessionBranch>(leg.degraded))
                .build();
    }

    /** 与经典组合对齐：账户仓位系数 × ADV断崖 × 结构突变 */
    private BigDecimal resolvePosScale(AccountRiskState risk, BigDecimal equity,
                                       List<BarDTO> bars, int index) {
        BigDecimal scale = risk == null ? BigDecimal.ONE : risk.positionScale(equity);
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

    private static BigDecimal calcEquity(BigDecimal cash, List<Leg> legs, LocalDateTime t) {
        return cash.add(calcPosMv(legs, t));
    }

    private static BigDecimal calcPosMv(List<Leg> legs, LocalDateTime t) {
        BigDecimal mv = BigDecimal.ZERO;
        if (legs == null) {
            return mv;
        }
        for (Leg leg : legs) {
            if (!leg.pos.hasPosition()) {
                continue;
            }
            BigDecimal px = priceAtOrLast(leg, t);
            if (px != null) {
                mv = mv.add(px.multiply(BigDecimal.valueOf(leg.pos.getShares())));
            }
        }
        return mv;
    }

    private static BigDecimal priceAtOrLast(Leg leg, LocalDateTime t) {
        if (leg.bars == null || leg.bars.isEmpty()) {
            return leg.lastMarkPrice;
        }
        BigDecimal last = leg.bars.get(0).getClose();
        for (BarDTO b : leg.bars) {
            if (b == null || b.getBarBegin() == null) {
                continue;
            }
            if (t != null && b.getBarBegin().isAfter(t)) {
                break;
            }
            if (b.getClose() != null) {
                last = b.getClose();
            }
        }
        return last;
    }

    private static int findIndex(List<BarDTO> bars, LocalDateTime t, int hint) {
        int idx = hint < 0 ? 0 : hint;
        while (idx < bars.size() && bars.get(idx) != null && bars.get(idx).getBarBegin() != null
                && bars.get(idx).getBarBegin().isBefore(t)) {
            idx++;
        }
        if (idx >= bars.size() || bars.get(idx) == null || bars.get(idx).getBarBegin() == null
                || !bars.get(idx).getBarBegin().equals(t)) {
            return -1;
        }
        return idx;
    }

    private static HoldDayState syncHold(HoldDayState hold, PositionState pos) {
        if (pos == null || !pos.hasPosition()) {
            return HoldDayState.FLAT;
        }
        return hold == null || hold == HoldDayState.FLAT ? HoldDayState.HOLD_D0 : hold;
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

    private static SessionEvent evBar(Leg leg, BarDTO bar, SessionBranch branch, String type, String detail) {
        return SessionEvent.builder()
                .time(bar != null && bar.getBarBegin() != null ? FMT.format(bar.getBarBegin()) : "")
                .type(type)
                .branch(branch == null ? null : branch.name())
                .detail(detail)
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int countTrades(List<BackTradeRecord> trades, String code) {
        int n = 0;
        if (trades == null) {
            return 0;
        }
        for (BackTradeRecord tr : trades) {
            if (tr != null && code.equals(tr.getStockCode())) {
                n++;
            }
        }
        return n;
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

    private static final class Leg {
        final String code;
        final List<BarDTO> bars;
        final SessionStrategy strategy;
        final Set<SessionBranch> degraded;
        final PositionState pos = new PositionState();
        final List<PendingOrder> pendingBuys = new ArrayList<PendingOrder>();
        final List<PendingOrder> pendingSells = new ArrayList<PendingOrder>();
        final List<SessionEvent> events = new ArrayList<SessionEvent>();
        final SessionBackTestEngine.BranchStatsAcc stats = new SessionBackTestEngine.BranchStatsAcc();
        HoldDayState hold = HoldDayState.FLAT;
        Set<SessionBranch> seenBranchToday = EnumSet.noneOf(SessionBranch.class);
        LocalDate currentDay;
        BarDTO lastBarOfDay;
        int lastBarIndexOfDay = -1;
        int hint;
        BigDecimal lastMarkPrice;

        Leg(String code, List<BarDTO> bars, SessionStrategy strategy, Set<SessionBranch> degraded) {
            this.code = code;
            this.bars = bars;
            this.strategy = strategy;
            this.degraded = degraded == null ? EnumSet.noneOf(SessionBranch.class) : degraded;
        }

        SessionBranch currentBranchOr(SessionBranch fallback) {
            return fallback;
        }
    }
}
