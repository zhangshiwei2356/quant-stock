package com.quant.stock.session;

import com.quant.stock.backtest.dto.BackTestResult;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话引擎验收：三分支事件、INDEX 降级、failOnMissingDep。
 */
class SessionBackTestEngineTest {

    @Test
    void scaffoldCountsThreeBranches() {
        List<BarDTO> bars = syntheticSessionDays("600036", 3);
        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any())).thenReturn(bars);
        SessionBackTestEngine engine = new SessionBackTestEngine(
                mds, new SessionDepProbe(mds), new QuantProperties());

        BackTestResult r = engine.run("600036", null, null, new BigDecimal("100000"),
                new BranchScaffoldStrategy(), false);

        assertEquals("session", r.getEngine());
        assertTrue(r.getSessionEvents() != null && !r.getSessionEvents().isEmpty());
        assertTrue(countType(r, "BRANCH_TICK") >= 3);
        assertTrue(hasBranch(r, "OPEN"));
        assertTrue(hasBranch(r, "MID"));
        assertTrue(hasBranch(r, "CLOSE"));
        assertEquals(0, r.getTotalTradeNum().intValue());
        assertTrue(r.getDegradedBranches() == null || r.getDegradedBranches().isEmpty());
        assertTrue(r.getConfigFingerprint() != null && r.getConfigFingerprint().contains("sess:"));
    }

    @Test
    void missingIndexDegradesBranchesByDefault() {
        List<BarDTO> bars = syntheticSessionDays("600036", 2);
        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any())).thenReturn(bars);
        SessionBackTestEngine engine = new SessionBackTestEngine(
                mds, new SessionDepProbe(mds), new QuantProperties());

        SessionStrategy needsIndex = new SessionStrategy() {
            @Override
            public String sessionId() {
                return "needsIndex";
            }

            @Override
            public Set<DataDep> dataDeps() {
                return EnumSet.of(DataDep.MIN1, DataDep.INDEX);
            }

            @Override
            public void onSessionOpen(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public void onBranchBar(SessionContext ctx, List<SessionEvent> out) {
                out.add(SessionEvent.builder().type("BRANCH_TICK").branch(ctx.getBranch().name()).build());
            }

            @Override
            public void onSessionClose(SessionContext ctx, List<SessionEvent> out) {
            }
        };

        BackTestResult r = engine.run("600036", null, null, new BigDecimal("100000"), needsIndex, false);
        assertFalse(r.getDegradedBranches().isEmpty());
        assertTrue(r.getDegradedBranches().contains("OPEN"));
        assertTrue(countType(r, "BRANCH_UNAVAILABLE") > 0);
        assertEquals(0, countType(r, "BRANCH_TICK"));
    }

    @Test
    void failOnMissingDepThrows() {
        List<BarDTO> bars = syntheticSessionDays("600036", 2);
        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any())).thenReturn(bars);
        SessionBackTestEngine engine = new SessionBackTestEngine(
                mds, new SessionDepProbe(mds), new QuantProperties());

        SessionStrategy needsIndex = new SessionStrategy() {
            @Override
            public String sessionId() {
                return "needsIndex";
            }

            @Override
            public Set<DataDep> dataDeps() {
                return EnumSet.of(DataDep.MIN1, DataDep.INDEX);
            }

            @Override
            public void onSessionOpen(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public void onBranchBar(SessionContext ctx, List<SessionEvent> out) {
            }

            @Override
            public void onSessionClose(SessionContext ctx, List<SessionEvent> out) {
            }
        };

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                engine.run("600036", null, null, new BigDecimal("100000"), needsIndex, true);
            }
        });
    }

    private static int countType(BackTestResult r, String type) {
        int n = 0;
        if (r.getSessionEvents() == null) {
            return 0;
        }
        for (SessionEvent e : r.getSessionEvents()) {
            if (type.equals(e.getType())) {
                n++;
            }
        }
        return n;
    }

    private static boolean hasBranch(BackTestResult r, String branch) {
        Set<String> set = new HashSet<String>();
        for (SessionEvent e : r.getSessionEvents()) {
            if ("BRANCH_TICK".equals(e.getType()) && e.getBranch() != null) {
                set.add(e.getBranch());
            }
        }
        return set.contains(branch);
    }

    /** 合成若干交易日，每日含 OPEN/MID/CLOSE 各若干根。 */
    static List<BarDTO> syntheticSessionDays(String code, int days) {
        List<BarDTO> out = new ArrayList<BarDTO>();
        LocalDate d = LocalDate.of(2026, 3, 2); // 周一
        BigDecimal px = new BigDecimal("10");
        for (int day = 0; day < days; day++) {
            LocalDate cur = d.plusDays(day);
            addMinute(out, code, cur, LocalTime.of(9, 30), px);
            addMinute(out, code, cur, LocalTime.of(9, 45), px);
            addMinute(out, code, cur, LocalTime.of(10, 30), px);
            addMinute(out, code, cur, LocalTime.of(14, 0), px);
            addMinute(out, code, cur, LocalTime.of(14, 45), px);
            addMinute(out, code, cur, LocalTime.of(14, 59), px);
            px = px.add(new BigDecimal("0.01"));
        }
        // pad to MIN_BARS
        while (out.size() < 30) {
            LocalDate cur = d.plusDays(days);
            addMinute(out, code, cur, LocalTime.of(10, out.size() % 30), px);
        }
        return out;
    }

    private static void addMinute(List<BarDTO> out, String code, LocalDate day, LocalTime t, BigDecimal px) {
        out.add(BarDTO.builder()
                .code(code)
                .barBegin(LocalDateTime.of(day, t))
                .open(px).high(px).low(px).close(px)
                .volume(new BigDecimal("1000"))
                .periodMinutes(1)
                .build());
    }
}
