package com.quant.stock.session;

import com.quant.stock.backtest.dto.AnalysisEvent;
import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.backtest.dto.BackTradeRecord;
import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

/**
 * 旁路会话回测引擎：MIN_1 推进 + 三分支调度 + 依赖降级；脚手架默认不撮合。
 * 不替代经典 {@link com.quant.stock.backtest.BackTestEngine}。
 */
@Service
@RequiredArgsConstructor
public class SessionBackTestEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 至少覆盖若干交易分钟才有验收意义 */
    private static final int MIN_BARS = 30;

    private final MarketDataService marketDataService;
    private final SessionDepProbe depProbe;
    private final QuantProperties props;

    /**
     * @param failOnMissingDep true 时任一声明依赖缺失则整单失败
     */
    public BackTestResult run(String stockCode, LocalDateTime start, LocalDateTime end,
                              BigDecimal initCapital, SessionStrategy strategy, boolean failOnMissingDep) {
        if (strategy == null) {
            throw new IllegalArgumentException("session 策略不能为空");
        }
        BigDecimal capital = initCapital == null ? new BigDecimal("100000") : initCapital;
        List<BarDTO> bars = marketDataService.getKline(stockCode, BarPeriod.MIN_1, start, end);
        String fingerprint = sessionFingerprint(strategy.sessionId(), failOnMissingDep);

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
        HoldDayState hold = HoldDayState.FLAT;
        LocalDate currentDay = null;
        BarDTO lastBarOfDay = null;
        int lastBarIndexOfDay = -1;
        Set<SessionBranch> seenBranchToday = EnumSet.noneOf(SessionBranch.class);
        int openDays = 0;
        int midDays = 0;
        int closeDays = 0;
        int sessionDays = 0;

        BigDecimal equity = capital;
        List<String> equityTimes = new ArrayList<String>();
        List<BigDecimal> equityCurve = new ArrayList<BigDecimal>();

        for (int i = 0; i < bars.size(); i++) {
            BarDTO bar = bars.get(i);
            if (bar == null || bar.getBarBegin() == null) {
                continue;
            }
            LocalTime t = bar.getBarBegin().toLocalTime();
            if (!SessionTradingMinutes.isTradingMinute(t)) {
                continue;
            }
            SessionBranch branch = SessionBranch.of(t);
            if (branch == null) {
                continue;
            }
            LocalDate day = bar.getBarBegin().toLocalDate();
            boolean newDay = currentDay == null || !currentDay.equals(day);
            if (newDay) {
                if (currentDay != null && lastBarOfDay != null) {
                    SessionContext closeCtx = baseCtx(stockCode, currentDay, SessionBranch.CLOSE, lastBarOfDay,
                            lastBarIndexOfDay, hold, equity, degraded);
                    strategy.onSessionClose(closeCtx, events);
                    hold = closeCtx.getHoldState() == null ? hold : closeCtx.getHoldState();
                }
                currentDay = day;
                seenBranchToday = EnumSet.noneOf(SessionBranch.class);
                sessionDays++;
                SessionContext openCtx = baseCtx(stockCode, day, branch, bar, i, hold, equity, degraded);
                strategy.onSessionOpen(openCtx, events);
                hold = openCtx.getHoldState() == null ? hold : openCtx.getHoldState();
            }
            lastBarOfDay = bar;
            lastBarIndexOfDay = i;

            boolean firstOfBranch = seenBranchToday.add(branch);
            if (firstOfBranch) {
                if (branch == SessionBranch.OPEN) {
                    openDays++;
                } else if (branch == SessionBranch.MID) {
                    midDays++;
                } else if (branch == SessionBranch.CLOSE) {
                    closeDays++;
                }
            }

            SessionContext ctx = baseCtx(stockCode, day, branch, bar, i, hold, equity, degraded);
            if (ctx.isBranchDegraded()) {
                if (firstOfBranch) {
                    events.add(SessionEvent.builder()
                            .time(FMT.format(bar.getBarBegin()))
                            .type("BRANCH_UNAVAILABLE")
                            .branch(branch.name())
                            .detail("分支降级跳过钩子；缺失依赖影响")
                            .build());
                }
                continue;
            }
            // 脚手架按「分支日首根」回调即可，避免事件爆炸
            if (firstOfBranch) {
                strategy.onBranchBar(ctx, events);
                hold = ctx.getHoldState() == null ? hold : ctx.getHoldState();
            }

            // 日末权益点（每日最后一根交易分钟）
            if (i == bars.size() - 1 || nextBarNewDay(bars, i, day)) {
                equityTimes.add(FMT.format(bar.getBarBegin()));
                equityCurve.add(equity);
            }
        }
        if (currentDay != null && lastBarOfDay != null) {
            SessionContext closeCtx = baseCtx(stockCode, currentDay, SessionBranch.CLOSE, lastBarOfDay,
                    lastBarIndexOfDay, hold, equity, degraded);
            strategy.onSessionClose(closeCtx, events);
        }

        List<AnalysisEvent> analysis = toAnalysis(stockCode, events);
        List<String> degradedNames = new ArrayList<String>();
        for (SessionBranch b : degraded) {
            degradedNames.add(b.name());
        }

        String summary = String.format(Locale.ROOT,
                "engine=session strategy=%s days=%d OPEN=%d MID=%d CLOSE=%d events=%d degraded=%s holdEnd=%s",
                strategy.sessionId(), sessionDays, openDays, midDays, closeDays, events.size(),
                degradedNames, hold);

        return BackTestResult.builder()
                .stockCode(stockCode)
                .initCapital(capital)
                .finalAsset(capital)
                .totalRate(BigDecimal.ZERO)
                .maxDrawDown(BigDecimal.ZERO)
                .totalTradeNum(0)
                .winRate(BigDecimal.ZERO)
                .trades(new ArrayList<BackTradeRecord>())
                .equityTimes(equityTimes)
                .equityCurve(equityCurve)
                .buyMarks(new ArrayList<BackTestResult.MarkPoint>())
                .sellMarks(new ArrayList<BackTestResult.MarkPoint>())
                .analysisEvents(analysis)
                .analysisSummary(summary)
                .configFingerprint(fingerprint)
                .atrRisk(new LinkedHashMap<String, Object>())
                .engine("session")
                .degradedBranches(degradedNames)
                .sessionEvents(events)
                .build();
    }

    private SessionContext baseCtx(String code, LocalDate day, SessionBranch branch, BarDTO bar, int i,
                                   HoldDayState hold, BigDecimal equity, Set<SessionBranch> degraded) {
        return SessionContext.builder()
                .stockCode(code)
                .sessionDay(day)
                .branch(branch)
                .bar(bar)
                .barIndex(i)
                .holdState(hold)
                .equity(equity)
                .degradedBranches(new LinkedHashSet<SessionBranch>(degraded))
                .build();
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

    private String sessionFingerprint(String strategyId, boolean failOnMissingDep) {
        // 与经典指纹隔离：附加 engine/session 维度再哈希
        String classic = ConfigFingerprint.of(props, strategyId == null ? "branchScaffold" : strategyId,
                props.getFeeRate());
        String extra = "engine=session|failOnMissingDep=" + failOnMissingDep
                + "|open=09:30-10:00|mid=10:00-14:30|close=14:30-15:00";
        return classic + "|sess:" + Integer.toHexString(extra.hashCode());
    }
}
