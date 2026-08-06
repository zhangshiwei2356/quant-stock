package com.quant.stock.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFingerprintTest {

    @Test
    void sameConfigSameFingerprint() {
        QuantProperties a = new QuantProperties();
        QuantProperties b = new QuantProperties();
        assertEquals(ConfigFingerprint.of(a), ConfigFingerprint.of(b));
        assertTrue(ConfigFingerprint.of(a).startsWith("v1:"));
        assertEquals(19, ConfigFingerprint.of(a).length());
    }

    @Test
    void changingRiskKnobChangesFingerprint() {
        QuantProperties a = new QuantProperties();
        QuantProperties b = new QuantProperties();
        b.setMaxParticipationAdv(new BigDecimal("0.05"));
        assertNotEquals(ConfigFingerprint.of(a), ConfigFingerprint.of(b));
    }

    @Test
    void defaultActiveStrategyMapsToMaCrossFingerprintName() {
        QuantProperties p = new QuantProperties();
        assertEquals("maCross", p.getActiveStrategy());
        assertEquals("MaCrossStrategy", ConfigFingerprint.fingerprintStrategyId(p));
        assertEquals(ConfigFingerprint.of(p, "MaCrossStrategy", null), ConfigFingerprint.of(p));
    }

    @Test
    void otherActiveStrategyChangesFingerprintStrategyField() {
        QuantProperties a = new QuantProperties();
        QuantProperties b = new QuantProperties();
        b.setActiveStrategy("maCrossTrend");
        assertEquals("maCrossTrend", ConfigFingerprint.fingerprintStrategyId(b));
        assertNotEquals(ConfigFingerprint.of(a), ConfigFingerprint.of(b));
    }
}
