package com.quant.stock.session;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 纸面分钟扫描用的会话钩子适配：按当前 bar 推进 OPEN/MID/CLOSE，收集事件与下单意图。
 * 不替代 {@link SessionBackTestEngine}；撮合仍走 {@code StrategyTask} 挂单/成交路径。
 */
@Component
@RequiredArgsConstructor
public class SessionPaperAdaptor {

    private final QuantProperties props;
    private final SessionDepProbe depProbe;

    /** 可变纸面会话态（挂在 LiveBook 上）。 */
    public static final class State {
        public HoldDayState hold = HoldDayState.FLAT;
        public LocalDate sessionDay;
        public Set<SessionBranch> seenBranchesToday = EnumSet.noneOf(SessionBranch.class);
        public BarDTO lastBar;
        public int lastBarIndex = -1;
    }

    public static final class Outcome {
        public HoldDayState hold = HoldDayState.FLAT;
        public final List<SessionEvent> events = new ArrayList<SessionEvent>();
        public final List<SessionOrderIntent> intents = new ArrayList<SessionOrderIntent>();
    }

    /**
     * 处理一根已闭合分钟 bar。
     *
     * @param state 会被就地更新
     */
    public Outcome onBar(SessionStrategy strategy, String stockCode, List<BarDTO> bars, int index,
                         State state, BigDecimal cash, int positionShares, int sellableShares,
                         BigDecimal equity) {
        Outcome out = new Outcome();
        if (strategy == null || bars == null || index < 0 || index >= bars.size() || state == null) {
            return out;
        }
        QuantProperties.Session sess = props.getSession() == null
                ? new QuantProperties.Session() : props.getSession();
        if (!sess.isPaperEnabled()) {
            out.hold = state.hold;
            return out;
        }
        BarDTO bar = bars.get(index);
        if (bar == null || bar.getBarBegin() == null) {
            out.hold = state.hold;
            return out;
        }
        LocalTime t = bar.getBarBegin().toLocalTime();
        if (!SessionTradingMinutes.isTradingMinute(t)) {
            out.hold = state.hold;
            return out;
        }
        SessionWindows windows = SessionWindows.from(props);
        SessionBranch branch = windows.of(t);
        if (branch == null) {
            out.hold = state.hold;
            return out;
        }

        Set<DataDep> required = strategy.dataDeps() == null
                ? EnumSet.of(DataDep.MIN1) : EnumSet.copyOf(strategy.dataDeps());
        LocalDateTime start = bars.get(0).getBarBegin();
        LocalDateTime end = bar.getBarBegin();
        Set<DataDep> missing = depProbe.probeUnavailable(stockCode, start, end, required);
        Set<SessionBranch> degraded = new LinkedHashSet<SessionBranch>();
        if (missing != null) {
            for (DataDep dep : missing) {
                Set<SessionBranch> affected = strategy.branchesAffectedBy(dep);
                if (affected != null) {
                    degraded.addAll(affected);
                }
            }
        }

        LocalDate day = bar.getBarBegin().toLocalDate();
        boolean newDay = state.sessionDay == null || !state.sessionDay.equals(day);
        HoldDayState hold = state.hold == null ? HoldDayState.FLAT : state.hold;

        if (newDay) {
            if (state.sessionDay != null && state.lastBar != null) {
                SessionContext closeCtx = ctx(stockCode, state.sessionDay, SessionBranch.CLOSE,
                        state.lastBar, state.lastBarIndex, hold, equity, cash, positionShares,
                        sellableShares, degraded, sess.isMatchingEnabled(), bars);
                strategy.onSessionClose(closeCtx, out.events);
                hold = closeCtx.getHoldState() == null ? hold : closeCtx.getHoldState();
                appendIntents(strategy, closeCtx, out);
            }
            state.sessionDay = day;
            state.seenBranchesToday = EnumSet.noneOf(SessionBranch.class);
            SessionContext openCtx = ctx(stockCode, day, branch, bar, index, hold, equity, cash,
                    positionShares, sellableShares, degraded, sess.isMatchingEnabled(), bars);
            strategy.onSessionOpen(openCtx, out.events);
            hold = openCtx.getHoldState() == null ? hold : openCtx.getHoldState();
            appendIntents(strategy, openCtx, out);
        }

        boolean firstOfBranch = state.seenBranchesToday.add(branch);
        SessionContext barCtx = ctx(stockCode, day, branch, bar, index, hold, equity, cash,
                positionShares, sellableShares, degraded, sess.isMatchingEnabled(), bars);
        if (barCtx.isBranchDegraded()) {
            if (firstOfBranch) {
                out.events.add(SessionEvent.builder()
                        .time(bar.getBarBegin().toString())
                        .type("BRANCH_UNAVAILABLE")
                        .branch(branch.name())
                        .detail("纸面：分支降级跳过")
                        .build());
            }
        } else if (firstOfBranch || strategy.tickEveryBar()) {
            strategy.onBranchBar(barCtx, out.events);
            hold = barCtx.getHoldState() == null ? hold : barCtx.getHoldState();
            appendIntents(strategy, barCtx, out);
        }

        state.hold = hold;
        state.lastBar = bar;
        state.lastBarIndex = index;
        out.hold = hold;
        return out;
    }

    private void appendIntents(SessionStrategy strategy, SessionContext ctx, Outcome out) {
        if (ctx == null || ctx.isBranchDegraded()) {
            return;
        }
        List<SessionOrderIntent> list = strategy.pollIntents(ctx);
        if (list != null && !list.isEmpty()) {
            out.intents.addAll(list);
        }
    }

    private static SessionContext ctx(String code, LocalDate day, SessionBranch branch, BarDTO bar, int i,
                                      HoldDayState hold, BigDecimal equity, BigDecimal cash,
                                      int shares, int sellable, Set<SessionBranch> degraded,
                                      boolean matchingEnabled, List<BarDTO> bars) {
        SessionContext.SessionContextBuilder b = SessionContext.builder()
                .stockCode(code)
                .sessionDay(day)
                .branch(branch)
                .bar(bar)
                .barIndex(i)
                .holdState(hold)
                .equity(equity)
                .cash(cash)
                .positionShares(shares)
                .sellableShares(sellable)
                .matchingEnabled(matchingEnabled)
                .degradedBranches(new LinkedHashSet<SessionBranch>(degraded));
        SessionBarAnchors.applyTo(b, bars, i);
        return b.build();
    }
}
