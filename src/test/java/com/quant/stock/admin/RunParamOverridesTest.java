package com.quant.stock.admin;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunParamOverridesTest {

    @Test
    void parseAndApplyWhitelist() {
        Map<String, String> ov = RunParamOverrides.parseJson(
                "{\"feeRate\":\"0.0005\",\"atrStopMultiplier\":\"2.5\",\"unknownKey\":1}");
        assertEquals("0.0005", ov.get("feeRate"));
        assertEquals("2.5", ov.get("atrStopMultiplier"));
        assertTrue(!ov.containsKey("unknownKey"));

        QuantProperties p = new QuantProperties();
        p.setFeeRate(new BigDecimal("0.0003"));
        RunParamOverrides.apply(p, ov);
        assertEquals(0, p.getFeeRate().compareTo(new BigDecimal("0.0005")));
        assertEquals(0, p.getAtrStopMultiplier().compareTo(new BigDecimal("2.5")));
    }

    @Test
    void illegalJsonThrows() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                RunParamOverrides.parseJson("{not-json");
            }
        });
    }

    @Test
    void resolveStacksRunOverridesAboveSparse() {
        QuantProperties global = new QuantProperties();
        global.setDbEnabled(false);
        global.setRsiBuyMax(new BigDecimal("70"));
        global.setFeeRate(new BigDecimal("0.0003"));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.quant.stock.mapper.StrategyParamMapper> mp =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(mp.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(jdbc.getIfAvailable()).thenReturn(null);
        EffectiveParamsService svc = new EffectiveParamsService(
                global,
                new com.quant.stock.strategy.StrategyRegistry(
                        java.util.Arrays.asList(
                                new com.quant.stock.strategy.MaCrossStrategy(global),
                                new com.quant.stock.strategy.HoldNothingStrategy()),
                        global),
                mp, jdbc);
        Map<String, String> run = new HashMap<String, String>();
        run.put("rsiBuyMax", "50");
        QuantProperties snap = svc.resolve("maCross", run);
        assertEquals(0, snap.getRsiBuyMax().compareTo(new BigDecimal("50")));
        assertEquals(0, global.getRsiBuyMax().compareTo(new BigDecimal("70")));
        assertEquals(Collections.emptyMap(), RunParamOverrides.normalize(null));
    }
}
