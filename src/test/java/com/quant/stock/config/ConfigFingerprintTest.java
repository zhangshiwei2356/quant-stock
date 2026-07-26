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
}
