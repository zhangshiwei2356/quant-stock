package com.quant.stock.risk;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnoverGuardServiceTest {

    private TurnoverGuardService guard;

    @BeforeEach
    void setUp() {
        QuantProperties props = new QuantProperties();
        props.setTurnoverGuardEnabled(true);
        props.setTurnoverSoftPct(new BigDecimal("0.50"));
        props.setTurnoverHardPct(new BigDecimal("1.00"));
        ObjectProvider<RiskControlLogService> empty = new ObjectProvider<RiskControlLogService>() {
            @Override
            public RiskControlLogService getObject() {
                return null;
            }

            @Override
            public RiskControlLogService getObject(Object... args) {
                return null;
            }

            @Override
            public RiskControlLogService getIfAvailable() {
                return null;
            }

            @Override
            public RiskControlLogService getIfUnique() {
                return null;
            }
        };
        guard = new TurnoverGuardService(props, new RiskAlertService(props, empty));
        guard.clearForTests();
    }

    @Test
    void softScalesAndHardBlocks() {
        LocalDate d = LocalDate.of(2026, 7, 1);
        BigDecimal equity = new BigDecimal("100000");
        guard.recordTrade(d, new BigDecimal("60000"));
        assertEquals(0, new BigDecimal("0.5").compareTo(guard.positionScaleMultiplier(d, equity)));
        assertTrue(guard.allowNewOpen(d, equity));
        guard.recordTrade(d, new BigDecimal("50000"));
        assertFalse(guard.allowNewOpen(d, equity));
    }
}
