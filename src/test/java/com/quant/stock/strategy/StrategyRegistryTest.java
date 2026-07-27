package com.quant.stock.strategy;

import com.quant.stock.config.ConfigFingerprint;
import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略注册表：默认 maCross、切换 holdNothing、指纹兼容。
 */
class StrategyRegistryTest {

    @Test
    void activeDefaultsToMaCross() {
        QuantProperties props = new QuantProperties();
        MaCrossStrategy ma = new MaCrossStrategy(props);
        HoldNothingStrategy hold = new HoldNothingStrategy();
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, hold), props);
        assertEquals("maCross", reg.active().name());
        assertSame(ma, reg.active());
        assertEquals("MaCrossStrategy", reg.active().fingerprintId());
        assertEquals("MaCrossStrategy", ConfigFingerprint.fingerprintStrategyId(props));
    }

    @Test
    void switchToHoldNothing() {
        QuantProperties props = new QuantProperties();
        props.setActiveStrategy("holdNothing");
        MaCrossStrategy ma = new MaCrossStrategy(props);
        HoldNothingStrategy hold = new HoldNothingStrategy();
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, hold), props);
        assertEquals("holdNothing", reg.active().name());
        assertSame(hold, reg.resolve("HOLDNOTHING"));
        assertTrue(reg.ids().contains("maCross"));
        assertTrue(reg.ids().contains("holdNothing"));
    }

    @Test
    void unknownIdFallsBackToMaCross() {
        QuantProperties props = new QuantProperties();
        props.setActiveStrategy("does-not-exist");
        MaCrossStrategy ma = new MaCrossStrategy(props);
        StrategyRegistry reg = new StrategyRegistry(Arrays.asList(ma, new HoldNothingStrategy()), props);
        assertSame(ma, reg.active());
    }
}
