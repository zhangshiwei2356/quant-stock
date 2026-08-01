package com.quant.stock.admin;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemParamsServiceTest {

    private QuantProperties props;
    private SystemParamsService service;

    @BeforeEach
    void setUp() {
        props = new QuantProperties();
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbc = mock(ObjectProvider.class);
        when(jdbc.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<EffectiveParamsService> eps = mock(ObjectProvider.class);
        when(eps.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.quant.stock.strategy.StrategyRegistry> reg = mock(ObjectProvider.class);
        when(reg.getIfAvailable()).thenReturn(null);
        service = new SystemParamsService(props, jdbc, eps, reg);
    }

    @Test
    void update_requiresConfirm() {
        Map<String, Object> out = service.update(
                Collections.<String, Object>singletonMap("rsiBuyMax", "55"), false);
        assertFalse(Boolean.TRUE.equals(out.get("ok")));
    }

    @Test
    void update_rejectsUnknownKey() {
        Map<String, Object> out = service.update(
                Collections.<String, Object>singletonMap("tradeMode", "sdk"), true);
        assertFalse(Boolean.TRUE.equals(out.get("ok")));
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) out.get("errors");
        assertTrue(errors != null && !errors.isEmpty());
    }

    @Test
    void update_appliesWhitelistInMemory() {
        props.setRsiBuyMax(new BigDecimal("70"));
        props.setTrendFilterEnabled(true);
        Map<String, Object> updates = new HashMap<String, Object>();
        updates.put("rsiBuyMax", "55");
        updates.put("trendFilterEnabled", "false");
        Map<String, Object> out = service.update(updates, true);
        assertTrue(Boolean.TRUE.equals(out.get("ok")));
        assertEquals(0, props.getRsiBuyMax().compareTo(new BigDecimal("55")));
        assertFalse(props.isTrendFilterEnabled());
    }

    @Test
    void view_marksWritable() {
        Map<String, Object> view = service.view();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) view.get("groups");
        boolean found = false;
        for (Map<String, Object> g : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
            for (Map<String, Object> it : items) {
                if ("rsiBuyMax".equals(it.get("key"))) {
                    assertTrue(Boolean.TRUE.equals(it.get("writable")));
                    found = true;
                }
                if ("tradeMode".equals(it.get("key"))) {
                    assertFalse(Boolean.TRUE.equals(it.get("writable")));
                }
            }
        }
        assertTrue(found);
    }
}
