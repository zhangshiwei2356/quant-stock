package com.quant.stock.strategy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyIdAliasesTest {

    @Test
    void matchIdsForQuery_includesFingerprintAlias() {
        List<String> ids = StrategyIdAliases.matchIdsForQuery("maCross");
        assertTrue(ids.contains("maCross"));
        assertTrue(ids.contains("MaCrossStrategy"));
        assertTrue(ids.contains("MA_CROSS_FILTERED"));
    }

    @Test
    void toCanonical_mapsFingerprintName() {
        assertEquals("maCross", StrategyIdAliases.toCanonical("MaCrossStrategy", null));
        assertEquals("maCross", StrategyIdAliases.toCanonical("MA_CROSS_FILTERED", null));
    }

    @Test
    void toCanonical_blank_returnsNull() {
        assertEquals(null, StrategyIdAliases.toCanonical("  ", null));
        assertEquals(null, StrategyIdAliases.toCanonical(null, null));
    }
}
