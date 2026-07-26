package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRiskStateTest {

    @Test
    void dailyLossBlocksOpen() {
        QuantProperties props = new QuantProperties();
        props.setDailyLossLimitPct(new BigDecimal("0.03"));
        AccountRiskState s = new AccountRiskState(props);
        s.reset(new BigDecimal("100000"));
        LocalDate d = LocalDate.of(2026, 1, 5);
        s.onEquity(d, new BigDecimal("100000"));
        s.onDayClose(new BigDecimal("100000"));
        LocalDate d2 = LocalDate.of(2026, 1, 6);
        assertFalse(s.allowNewOpen(d2, new BigDecimal("96000"))); // -4%
    }

    @Test
    void consecutiveLossBlocksThenRecoversNextDay() {
        QuantProperties props = new QuantProperties();
        props.setConsecutiveLossLimit(2);
        AccountRiskState s = new AccountRiskState(props);
        s.reset(new BigDecimal("100000"));
        LocalDate d = LocalDate.of(2026, 1, 5);
        s.onClosedRound(false, d);
        s.onClosedRound(false, d);
        assertFalse(s.allowNewOpen(d, new BigDecimal("100000")));
        assertTrue(s.allowNewOpen(d.plusDays(1), new BigDecimal("100000")));
    }

    @Test
    void drawdownScaleAndHalt() {
        QuantProperties props = new QuantProperties();
        props.setDrawdownReducePct(new BigDecimal("0.15"));
        props.setDrawdownHaltPct(new BigDecimal("0.25"));
        props.setDrawdownDurationReduceDays(0);
        props.setDrawdownDurationHaltDays(0);
        AccountRiskState s = new AccountRiskState(props);
        s.reset(new BigDecimal("100000"));
        LocalDate d = LocalDate.of(2026, 1, 5);
        s.onEquity(d, new BigDecimal("100000"));
        s.onEquity(d, new BigDecimal("84000")); // -16%
        assertEquals(0, new BigDecimal("0.5").compareTo(s.positionScale(new BigDecimal("84000"))));
        s.onEquity(d, new BigDecimal("74000")); // -26%
        assertTrue(s.isHalted());
        assertEquals(AccountRiskState.HALT_DEPTH, s.getHaltReason());
        assertEquals(0, BigDecimal.ZERO.compareTo(s.positionScale(new BigDecimal("74000"))));
    }

    @Test
    void durationReduceAndHaltWithoutDeepDrawdown() {
        QuantProperties props = new QuantProperties();
        props.setDrawdownReducePct(new BigDecimal("0.15"));
        props.setDrawdownHaltPct(new BigDecimal("0.25"));
        props.setDrawdownDurationReduceDays(2);
        props.setDrawdownDurationHaltDays(3);
        AccountRiskState s = new AccountRiskState(props);
        s.reset(new BigDecimal("100000"));
        // mild -5% for 3 trading days → duration halt
        s.onEquity(LocalDate.of(2026, 1, 5), new BigDecimal("100000"));
        s.onEquity(LocalDate.of(2026, 1, 6), new BigDecimal("95000"));
        assertEquals(1, s.getUnderwaterTradingDays());
        assertEquals(0, BigDecimal.ONE.compareTo(s.positionScale(new BigDecimal("95000"))));
        s.onEquity(LocalDate.of(2026, 1, 7), new BigDecimal("95000"));
        assertEquals(2, s.getUnderwaterTradingDays());
        assertEquals(0, new BigDecimal("0.5").compareTo(s.positionScale(new BigDecimal("95000"))));
        s.onEquity(LocalDate.of(2026, 1, 8), new BigDecimal("95000"));
        assertEquals(3, s.getUnderwaterTradingDays());
        assertTrue(s.isHalted());
        assertEquals(AccountRiskState.HALT_DURATION, s.getHaltReason());
    }

    @Test
    void newPeakResetsUnderwaterDays() {
        QuantProperties props = new QuantProperties();
        props.setDrawdownDurationReduceDays(5);
        props.setDrawdownDurationHaltDays(10);
        AccountRiskState s = new AccountRiskState(props);
        s.reset(new BigDecimal("100000"));
        s.onEquity(LocalDate.of(2026, 1, 5), new BigDecimal("100000"));
        s.onEquity(LocalDate.of(2026, 1, 6), new BigDecimal("99000"));
        s.onEquity(LocalDate.of(2026, 1, 7), new BigDecimal("98000"));
        assertEquals(2, s.getUnderwaterTradingDays());
        s.onEquity(LocalDate.of(2026, 1, 8), new BigDecimal("101000"));
        assertEquals(0, s.getUnderwaterTradingDays());
        assertFalse(s.isHalted());
    }
}
