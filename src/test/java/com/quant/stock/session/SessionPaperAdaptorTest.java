package com.quant.stock.session;

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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionPaperAdaptorTest {

    @Test
    void overnightGapFlatWithoutPositionAdvancesOnlyWhenHeld() {
        List<BarDTO> bars = twoDays();
        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any())).thenReturn(bars);
        QuantProperties props = new QuantProperties();
        props.getSession().setPaperEnabled(true);
        OvernightGapStrategy strat = new OvernightGapStrategy(props);
        SessionPaperAdaptor adaptor = new SessionPaperAdaptor(props, new SessionDepProbe(mds));
        SessionPaperAdaptor.State state = new SessionPaperAdaptor.State();

        SessionPaperAdaptor.Outcome d0 = adaptor.onBar(strat, "600036", bars, 0,
                state, new BigDecimal("100000"), 0, 0, new BigDecimal("100000"));
        assertEquals(HoldDayState.FLAT, d0.hold);

        // 有持仓时次日 OPEN 推进持仓日态
        state.hold = HoldDayState.HOLD_D0;
        SessionPaperAdaptor.Outcome d1 = adaptor.onBar(strat, "600036", bars, 6,
                state, new BigDecimal("100000"), 100, 100, new BigDecimal("100000"));
        assertTrue(d1.events.stream().anyMatch(e ->
                "HOLD_ADVANCE".equals(e.getType()) || "FORCE_FLAT".equals(e.getType())
                        || "ENTER_HOLD".equals(e.getType()) || "SESSION_CLOSE".equals(e.getType())));
        assertTrue(d1.intents.stream().anyMatch(i -> i.getSide() == SessionOrderIntent.Side.SELL));
    }

    @Test
    void paperDisabledSkipsHooks() {
        List<BarDTO> bars = twoDays();
        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any())).thenReturn(bars);
        QuantProperties props = new QuantProperties();
        props.getSession().setPaperEnabled(false);
        SessionPaperAdaptor adaptor = new SessionPaperAdaptor(props, new SessionDepProbe(mds));
        SessionPaperAdaptor.State state = new SessionPaperAdaptor.State();
        SessionPaperAdaptor.Outcome out = adaptor.onBar(new OvernightGapStrategy(props), "600036", bars, 0,
                state, new BigDecimal("100000"), 0, 0, new BigDecimal("100000"));
        assertTrue(out.events.isEmpty());
        assertEquals(HoldDayState.FLAT, out.hold);
    }

    @Test
    void pollIntentsCollectedWhenMatchingStrategy() {
        List<BarDTO> bars = twoDays();
        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getKline(eq("600036"), eq(BarPeriod.MIN_1), any(), any())).thenReturn(bars);
        QuantProperties props = new QuantProperties();
        SessionPaperAdaptor adaptor = new SessionPaperAdaptor(props, new SessionDepProbe(mds));
        SessionPaperAdaptor.State state = new SessionPaperAdaptor.State();
        SessionStrategy buyOnce = new SessionStrategy() {
            @Override
            public String sessionId() {
                return "buyOnce";
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

            @Override
            public List<SessionOrderIntent> pollIntents(SessionContext ctx) {
                if (ctx.getBranch() == SessionBranch.OPEN && ctx.getPositionShares() == 0) {
                    return Collections.singletonList(SessionOrderIntent.buy(100, "paper-demo"));
                }
                return Collections.emptyList();
            }
        };
        SessionPaperAdaptor.Outcome out = adaptor.onBar(buyOnce, "600036", bars, 0,
                state, new BigDecimal("100000"), 0, 0, new BigDecimal("100000"));
        // 同日 OPEN：onSessionOpen + 分支首根 onBranchBar 各 poll 一次
        assertTrue(out.intents.size() >= 1);
        assertEquals(SessionOrderIntent.Side.BUY, out.intents.get(0).getSide());
    }

    private static List<BarDTO> twoDays() {
        List<BarDTO> out = new ArrayList<BarDTO>();
        LocalDate d0 = LocalDate.of(2026, 3, 2);
        LocalDate d1 = LocalDate.of(2026, 3, 3);
        add(out, d0, LocalTime.of(9, 30));
        add(out, d0, LocalTime.of(9, 45));
        add(out, d0, LocalTime.of(10, 30));
        add(out, d0, LocalTime.of(14, 0));
        add(out, d0, LocalTime.of(14, 45));
        add(out, d0, LocalTime.of(14, 59));
        add(out, d1, LocalTime.of(9, 30));
        add(out, d1, LocalTime.of(10, 30));
        add(out, d1, LocalTime.of(14, 45));
        return out;
    }

    private static void add(List<BarDTO> out, LocalDate day, LocalTime t) {
        BigDecimal px = new BigDecimal("10");
        out.add(BarDTO.builder()
                .code("600036")
                .barBegin(LocalDateTime.of(day, t))
                .open(px).high(px).low(px).close(px)
                .volume(new BigDecimal("1000"))
                .periodMinutes(1)
                .build());
    }
}
