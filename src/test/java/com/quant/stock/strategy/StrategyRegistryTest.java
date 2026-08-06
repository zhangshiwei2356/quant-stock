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
        MaCrossBalancedStrategy balanced = new MaCrossBalancedStrategy();
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, balanced), props);
        assertEquals("maCross", reg.active().name());
        assertSame(ma, reg.active());
        assertEquals("MaCrossStrategy", reg.active().fingerprintId());
        assertEquals("MaCrossStrategy", ConfigFingerprint.fingerprintStrategyId(props));
    }

    @Test
    void switchToMaCrossBalanced() {
        QuantProperties props = new QuantProperties();
        props.setActiveStrategy("maCrossBalanced");
        MaCrossStrategy ma = new MaCrossStrategy(props);
        MaCrossBalancedStrategy balanced = new MaCrossBalancedStrategy();
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, balanced), props);
        assertEquals("maCrossBalanced", reg.active().name());
        assertSame(balanced, reg.resolve("MACROSSBALANCED"));
        assertTrue(reg.ids().contains("maCross"));
        assertTrue(reg.ids().contains("maCrossBalanced"));
    }

    @Test
    void unknownIdFallsBackToMaCross() {
        QuantProperties props = new QuantProperties();
        props.setActiveStrategy("does-not-exist");
        MaCrossStrategy ma = new MaCrossStrategy(props);
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, new MaCrossBalancedStrategy()), props);
        assertSame(ma, reg.active());
    }
}
