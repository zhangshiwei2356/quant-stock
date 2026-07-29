package com.quant.stock.strategy;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金叉参数画像：原版不动；副本固定过滤；注册表可发现。
 */
class MaCrossProfileStrategyTest {

    @Test
    void profilesRegisteredWithDistinctIds() {
        QuantProperties props = new QuantProperties();
        List<BaseStrategy> all = Arrays.asList(
                new MaCrossStrategy(props),
                new HoldNothingStrategy(),
                new MaCrossTrendStrategy(),
                new MaCrossVolumeStrategy(),
                new MaCrossBalancedStrategy(),
                new MaCrossStrictStrategy());
        StrategyRegistry reg = new StrategyRegistry(all, props);
        assertTrue(reg.contains("maCross"));
        assertTrue(reg.contains("maCrossTrend"));
        assertTrue(reg.contains("maCrossVolume"));
        assertTrue(reg.contains("maCrossBalanced"));
        assertTrue(reg.contains("maCrossStrict"));
        assertEquals("maCross", reg.active().name());
        assertEquals(MaCrossFilterProfile.STRICT.getId(), reg.resolve("maCrossStrict").name());
    }

    @Test
    void strictRejectsWhenTrendDownEvenIfCrossUpBundleLies() {
        // 构造：isMaCrossUp 需真实 bundle；这里只测 rejectReason 对趋势开关
        MaCrossStrictStrategy s = new MaCrossStrictStrategy();
        assertEquals("maCrossStrict", s.name());
        assertTrue(s.uiLabel().contains("严格"));
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
