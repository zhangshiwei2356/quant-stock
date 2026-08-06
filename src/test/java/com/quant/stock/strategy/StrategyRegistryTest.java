package com.quant.stock.strategy;

import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略注册表：默认 maCross、切换对照画像、未知 id 回退。
 */
class StrategyRegistryTest {

    @Test
    void activeDefaultsToMaCross() {
        QuantProperties props = new QuantProperties();
        MaCrossStrategy ma = new MaCrossStrategy(props);
        MaCrossTrendStrategy trend = new MaCrossTrendStrategy();
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, trend), props);
        assertEquals("maCross", reg.active().name());
        assertSame(ma, reg.active());
        assertEquals("MaCrossStrategy", reg.active().fingerprintId());
        assertEquals("MaCrossStrategy", ConfigFingerprint.fingerprintStrategyId(props));
    }

    @Test
    void switchToMaCrossTrend() {
        QuantProperties props = new QuantProperties();
        props.setActiveStrategy("maCrossTrend");
        MaCrossStrategy ma = new MaCrossStrategy(props);
        MaCrossTrendStrategy trend = new MaCrossTrendStrategy();
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, trend), props);
        assertEquals("maCrossTrend", reg.active().name());
        assertSame(trend, reg.resolve("MACROSSTREND"));
        assertTrue(reg.ids().contains("maCross"));
        assertTrue(reg.ids().contains("maCrossTrend"));
    }

    @Test
    void unknownIdFallsBackToMaCross() {
        QuantProperties props = new QuantProperties();
        props.setActiveStrategy("does-not-exist");
        MaCrossStrategy ma = new MaCrossStrategy(props);
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, new MaCrossTrendStrategy()), props);
        assertSame(ma, reg.active());
    }
}
