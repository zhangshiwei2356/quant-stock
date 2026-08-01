package com.quant.stock.admin;

import com.quant.stock.admin.dto.StrategyParamDO;
import com.quant.stock.config.QuantProperties;
import com.quant.stock.mapper.StrategyParamMapper;
import com.quant.stock.strategy.BaseStrategy;
import com.quant.stock.strategy.HoldNothingStrategy;
import com.quant.stock.strategy.MaCrossStrategy;
import com.quant.stock.strategy.StrategyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EffectiveParamsServiceTest {

    private QuantProperties global;
    private StrategyParamMapper mapper;
    private EffectiveParamsService service;

    @BeforeEach
    void setUp() {
        global = new QuantProperties();
        global.setDbEnabled(true);
        global.setRsiBuyMax(new BigDecimal("70"));
        global.setTrendFilterEnabled(true);
        mapper = mock(StrategyParamMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StrategyParamMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc = mock(ObjectProvider.class);
        when(jdbc.getIfAvailable()).thenReturn(null);
        List<BaseStrategy> strategies = java.util.Arrays.asList(
                new MaCrossStrategy(global), new HoldNothingStrategy());
        StrategyRegistry registry = new StrategyRegistry(strategies, global);
        service = new EffectiveParamsService(global, registry, provider, jdbc);
    }

    @Test
    void resolve_withoutRow_equalsGlobalCopy() {
        when(mapper.selectByStrategyId("maCross")).thenReturn(null);
        QuantProperties snap = service.resolve("maCross");
        assertEquals(0, snap.getRsiBuyMax().compareTo(new BigDecimal("70")));
        snap.setRsiBuyMax(new BigDecimal("10"));
        assertEquals(0, global.getRsiBuyMax().compareTo(new BigDecimal("70")));
    }

    @Test
    void resolve_appliesSparseOverride() {
        StrategyParamDO row = new StrategyParamDO();
        row.setStrategyId("maCross");
        row.setParamsJson("{\"rsiBuyMax\":\"55\"}");
        row.setVersion(1);
        when(mapper.selectByStrategyId("maCross")).thenReturn(row);
        QuantProperties snap = service.resolve("maCross");
        assertEquals(0, snap.getRsiBuyMax().compareTo(new BigDecimal("55")));
        global.setRsiBuyMax(new BigDecimal("80"));
        assertEquals(0, service.resolve("maCross").getRsiBuyMax().compareTo(new BigDecimal("55")));
    }

    @Test
    void saveSparse_requiresConfirm() {
        Map<String, Object> out = service.saveSparse("maCross",
                Collections.<String, Object>singletonMap("rsiBuyMax", "55"),
                null, false, null);
        assertFalse(Boolean.TRUE.equals(out.get("ok")));
    }

    @Test
    void saveSparse_rejectsUnknownStrategy() {
        Map<String, Object> out = service.saveSparse("noSuch",
                Collections.<String, Object>singletonMap("rsiBuyMax", "55"),
                null, true, null);
        assertFalse(Boolean.TRUE.equals(out.get("ok")));
    }

    @Test
    void saveSparse_andClear() {
        when(mapper.selectByStrategyId("maCross")).thenReturn(null);
        when(mapper.upsert(any(StrategyParamDO.class))).thenReturn(1);
        Map<String, Object> updates = new HashMap<String, Object>();
        updates.put("rsiBuyMax", "55");
        Map<String, Object> out = service.saveSparse("maCross", updates, null, true, null);
        assertTrue(Boolean.TRUE.equals(out.get("ok")));

        StrategyParamDO saved = new StrategyParamDO();
        saved.setStrategyId("maCross");
        saved.setParamsJson("{\"rsiBuyMax\":\"55\"}");
        saved.setVersion(0);
        when(mapper.selectByStrategyId("maCross")).thenReturn(saved);
        assertTrue(service.hasSparse("maCross"));

        Map<String, Object> cleared = service.saveSparse("maCross", null,
                Collections.singletonList("rsiBuyMax"), true, 0);
        assertTrue(Boolean.TRUE.equals(cleared.get("ok")));
    }
}
