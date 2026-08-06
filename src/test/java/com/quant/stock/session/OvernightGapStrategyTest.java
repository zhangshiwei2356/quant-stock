package com.quant.stock.session;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隔日高开三分支：尾盘买、早盘兑现/止损、盘中回撤。
 */
class OvernightGapStrategyTest {

    private OvernightGapStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new OvernightGapStrategy(new QuantProperties());
    }

    @Test
    void closeBuyWhenMildDayAndCloseAboveOpen() {
        SessionContext ctx = base(SessionBranch.CLOSE, HoldDayState.FLAT, 0, 0)
                .prevClose(new BigDecimal("10.00"))
                .dayOpen(new BigDecimal("10.05"))
                .gapPct(new BigDecimal("0.005"))
                .dayRet(new BigDecimal("0.010"))
                .bar(bar(LocalTime.of(14, 45), "10.10"))
                .build();
        List<SessionOrderIntent> intents = strategy.pollIntents(ctx);
        assertEquals(1, intents.size());
        assertEquals(SessionOrderIntent.Side.BUY, intents.get(0).getSide());
        assertEquals("CLOSE_SETUP", intents.get(0).getReason());
    }

    @Test
    void openSellsOnGapTakeProfit() {
        SessionContext ctx = base(SessionBranch.OPEN, HoldDayState.HOLD_D1, 100, 100)
                .prevClose(new BigDecimal("10.00"))
                .dayOpen(new BigDecimal("10.20"))
                .gapPct(new BigDecimal("0.020"))
                .bar(bar(LocalTime.of(9, 30), "10.20"))
                .build();
        List<SessionOrderIntent> intents = strategy.pollIntents(ctx);
        assertEquals(1, intents.size());
        assertEquals(SessionOrderIntent.Side.SELL, intents.get(0).getSide());
        assertEquals("GAP_TP", intents.get(0).getReason());
    }

    @Test
    void openOvernightExitWhenGapFlat() {
        SessionContext ctx = base(SessionBranch.OPEN, HoldDayState.HOLD_D1, 100, 100)
                .prevClose(new BigDecimal("10.00"))
                .dayOpen(new BigDecimal("10.05"))
                .gapPct(new BigDecimal("0.005"))
                .bar(bar(LocalTime.of(9, 30), "10.05"))
                .build();
        List<SessionOrderIntent> intents = strategy.pollIntents(ctx);
        assertEquals(1, intents.size());
        assertEquals("OVERNIGHT_EXIT", intents.get(0).getReason());
    }

    @Test
    void midStopWhenBelowDayOpen() {
        SessionContext ctx = base(SessionBranch.MID, HoldDayState.HOLD_D1, 100, 100)
                .dayOpen(new BigDecimal("10.00"))
                .gapPct(new BigDecimal("0.000"))
                .bar(bar(LocalTime.of(11, 0), "9.70"))
                .build();
        List<SessionOrderIntent> intents = strategy.pollIntents(ctx);
        assertEquals(1, intents.size());
        assertEquals("MID_SL", intents.get(0).getReason());
    }

    @Test
    void onSessionOpenAdvancesHoldWhenPositioned() {
        SessionContext ctx = base(SessionBranch.OPEN, HoldDayState.HOLD_D0, 100, 100).build();
        java.util.ArrayList<SessionEvent> events = new java.util.ArrayList<SessionEvent>();
        strategy.onSessionOpen(ctx, events);
        assertEquals(HoldDayState.HOLD_D1, ctx.getHoldState());
        assertTrue(events.stream().anyMatch(e -> "HOLD_ADVANCE".equals(e.getType())));
    }

    private static SessionContext.SessionContextBuilder base(SessionBranch branch, HoldDayState hold,
                                                             int pos, int sellable) {
        return SessionContext.builder()
                .stockCode("600036")
                .sessionDay(LocalDate.of(2026, 3, 3))
                .branch(branch)
                .holdState(hold)
                .positionShares(pos)
                .sellableShares(sellable)
                .matchingEnabled(true)
                .cash(new BigDecimal("100000"))
                .equity(new BigDecimal("100000"));
    }

    private static BarDTO bar(LocalTime t, String close) {
        BigDecimal px = new BigDecimal(close);
        return BarDTO.builder()
                .code("600036")
                .barBegin(LocalDateTime.of(LocalDate.of(2026, 3, 3), t))
                .open(px).high(px).low(px).close(px)
                .volume(new BigDecimal("1000000"))
                .build();
    }
}
