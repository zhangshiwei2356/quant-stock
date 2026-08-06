package com.quant.stock.strategy;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金叉参数画像：原版不动；仅保留 Balanced 对照；Trend/Volume/Strict 已下线。
 */
class MaCrossProfileStrategyTest {

    @Test
    void profilesRegisteredWithDistinctIds() {
        QuantProperties props = new QuantProperties();
        List<BaseStrategy> all = Arrays.asList(
                new MaCrossStrategy(props),
                new MaCrossBalancedStrategy());
        StrategyRegistry reg = new StrategyRegistry(all, props);
        assertTrue(reg.contains("maCross"));
        assertTrue(reg.contains("maCrossBalanced"));
        assertFalse(reg.contains("maCrossTrend"));
        assertFalse(reg.contains("maCrossVolume"));
        assertFalse(reg.contains("maCrossStrict"));
        assertEquals("maCross", reg.active().name());
        assertEquals(MaCrossFilterProfile.BALANCED.getId(), reg.resolve("maCrossBalanced").name());
    }

    @Test
    void balancedProfileHasLabelAndSummary() {
        MaCrossBalancedStrategy s = new MaCrossBalancedStrategy();
        assertEquals("maCrossBalanced", s.name());
        assertTrue(s.uiLabel().contains("均衡"));
        assertFalse(s.profileSummary().isEmpty());
    }

    @Test
    void originalMaCrossStillReadsQuantProperties() {
        QuantProperties props = new QuantProperties();
        props.setTrendFilterEnabled(false);
        MaCrossStrategy ma = new MaCrossStrategy(props);
        assertEquals("maCross", ma.name());
        assertEquals("MaCrossStrategy", ma.fingerprintId());
    }
}
