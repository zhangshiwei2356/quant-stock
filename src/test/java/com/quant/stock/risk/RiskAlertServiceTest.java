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

/**
 * P0-97 告警分级冷却与软预算。
 */
class RiskAlertServiceTest {

    private RiskAlertService svc;
    private QuantProperties props;

    @BeforeEach
    void setUp() {
        props = new QuantProperties();
        props.setAlertCooldownWarnMinutes(60);
        props.setAlertCooldownCriticalMinutes(30);
        props.setSoftTotalPositionPct(new BigDecimal("0.70"));
        props.setSoftSinglePositionPct(new BigDecimal("0.25"));
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
        svc = new RiskAlertService(props, empty);
        svc.clearForTests();
    }

    @Test
    void cooldownDropsDuplicateWarn() {
        LocalDate d = LocalDate.of(2026, 7, 1);
        assertTrue(svc.emit(d, "600036", "SOFT_TOTAL_POSITION", AlertSeverity.WARN,
                new BigDecimal("0.71"), "first"));
        assertFalse(svc.emit(d, "600036", "SOFT_TOTAL_POSITION", AlertSeverity.WARN,
                new BigDecimal("0.72"), "second"));
        assertEquals(1, svc.recent(10).size());
    }

    @Test
    void softBudgetEmitsWhenOverLine() {
        LocalDate d = LocalDate.of(2026, 7, 2);
        svc.checkSoftBudget(d, new BigDecimal("100000"), new BigDecimal("75000"),
                "600036", new BigDecimal("30000"));
        assertTrue(svc.recent(10).stream().anyMatch(r -> "SOFT_TOTAL_POSITION".equals(r.get("ruleType"))));
        assertTrue(svc.recent(10).stream().anyMatch(r -> "SOFT_SINGLE_POSITION".equals(r.get("ruleType"))));
    }
}
