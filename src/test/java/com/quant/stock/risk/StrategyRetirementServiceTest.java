package com.quant.stock.risk;

import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyRetirementServiceTest {

    @Test
    void autoRetireOnDurationHaltThenCooldownResume() {
        QuantProperties props = new QuantProperties();
        props.setAutoRetireOnDurationHalt(true);
        props.setRetirementCooldownTradingDays(2);
        props.setDrawdownDurationHaltDays(1);
        props.setDrawdownDurationReduceDays(0);
        props.setDrawdownHaltPct(new BigDecimal("0.99")); // avoid depth halt
        StrategyRetirementService svc = new StrategyRetirementService(props, new TradingCalendar());
        AccountRiskState risk = new AccountRiskState(props);
        risk.reset(new BigDecimal("100000"));
        LocalDate d0 = LocalDate.of(2026, 7, 20); // Mon
        risk.onEquity(d0, new BigDecimal("100000"));
        risk.onEquity(LocalDate.of(2026, 7, 21), new BigDecimal("99000"));
        assertTrue(risk.isHalted());
        assertEquals(AccountRiskState.HALT_DURATION, risk.getHaltReason());
        svc.onAccountHalt(risk, LocalDate.of(2026, 7, 21));
        assertTrue(svc.isRetired());
        assertFalse(svc.allowNewOpen());

        Map<String, Object> early = svc.resume(LocalDate.of(2026, 7, 21), false);
        assertFalse(Boolean.TRUE.equals(early.get("ok")));

        Map<String, Object> ok = svc.resume(LocalDate.of(2026, 7, 23), false); // +2 trading days
        assertTrue(Boolean.TRUE.equals(ok.get("ok")));
        assertFalse(svc.isRetired());
    }

    @Test
    void forceResumeNeedsDualConfirm() {
        QuantProperties props = new QuantProperties();
        props.setRetirementCooldownTradingDays(20);
        StrategyRetirementService svc = new StrategyRetirementService(props, new TradingCalendar());
        svc.retire(LocalDate.of(2026, 7, 20), "MANUAL", "test");

        Map<String, Object> arm = svc.resume(LocalDate.of(2026, 7, 21), true, null);
        assertFalse(Boolean.TRUE.equals(arm.get("ok")));
        assertTrue(Boolean.TRUE.equals(arm.get("needDualConfirm")));
        assertTrue(svc.isRetired());
        String token = String.valueOf(arm.get("forceConfirmToken"));
        assertTrue(token.length() >= 6);

        Map<String, Object> bad = svc.resume(LocalDate.of(2026, 7, 21), true, "WRONG");
        assertFalse(Boolean.TRUE.equals(bad.get("ok")));
        // WRONG re-arms a new token
        String token2 = String.valueOf(bad.get("forceConfirmToken"));

        Map<String, Object> ok = svc.resume(LocalDate.of(2026, 7, 21), true, token2);
        assertTrue(Boolean.TRUE.equals(ok.get("ok")));
        assertFalse(svc.isRetired());
    }
}
