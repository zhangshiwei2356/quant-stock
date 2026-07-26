package com.quant.stock.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityThrottleTest {

    @Test
    void tightensParticipationWhenEquityAboveBase() {
        BigDecimal configured = new BigDecimal("0.10");
        BigDecimal eff = CapacityThrottle.effectiveMaxParticipation(
                configured, new BigDecimal("200000"), new BigDecimal("100000"));
        assertEquals(0, new BigDecimal("0.05").compareTo(eff));
    }

    @Test
    void unchangedWhenEquityAtOrBelowBase() {
        BigDecimal configured = new BigDecimal("0.10");
        BigDecimal eff = CapacityThrottle.effectiveMaxParticipation(
                configured, new BigDecimal("100000"), new BigDecimal("100000"));
        assertEquals(0, configured.compareTo(eff));
    }

    @Test
    void povCapsToBarVolumeShare() {
        int capped = CapacityThrottle.povCapVolume(5000, 10000L, new BigDecimal("0.10"));
        assertEquals(1000, capped);
        assertTrue(CapacityThrottle.povCapVolume(500, 10000L, new BigDecimal("0.10")) >= 100
                || CapacityThrottle.povCapVolume(500, 10000L, new BigDecimal("0.10")) == 500);
    }

    @Test
    void statusDeclaresTwapUnavailable() {
        com.quant.stock.config.QuantProperties p = new com.quant.stock.config.QuantProperties();
        java.util.Map<String, Object> m = CapacityThrottle.status(p, new BigDecimal("200000"));
        assertEquals("UNAVAILABLE", m.get("twapSlicer"));
        assertEquals(0, new BigDecimal("0.05").compareTo((BigDecimal) m.get("effectiveMaxParticipationAdv")));
    }
}
