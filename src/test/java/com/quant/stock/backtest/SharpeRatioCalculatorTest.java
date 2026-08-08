package com.quant.stock.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权益曲线年化夏普（RF=0）。
 */
class SharpeRatioCalculatorTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void emptyOrShort_returnsNull() {
        assertNull(SharpeRatioCalculator.fromEquityCurve(null, "DAY"));
        assertNull(SharpeRatioCalculator.fromEquityCurve(Collections.<BigDecimal>emptyList(), "DAY"));
        assertNull(SharpeRatioCalculator.fromEquityCurve(Collections.singletonList(bd("100")), "DAY"));
        // 仅 1 个收益点（2 个权益）→ 样本标准差不可用
        assertNull(SharpeRatioCalculator.fromEquityCurve(Arrays.asList(bd("100"), bd("110")), "DAY"));
    }

    @Test
    void flatCurve_returnsNull() {
        List<BigDecimal> flat = Arrays.asList(bd("100"), bd("100"), bd("100"), bd("100"));
        assertNull(SharpeRatioCalculator.fromEquityCurve(flat, "DAY"));
    }

    @Test
    void risingCurve_positiveSharpe() {
        // 稳定上涨：夏普应为正
        List<BigDecimal> up = Arrays.asList(
                bd("100"), bd("101"), bd("102.5"), bd("103"), bd("105"), bd("106"));
        BigDecimal sharpe = SharpeRatioCalculator.fromEquityCurve(up, "DAY");
        assertNotNull(sharpe);
        assertTrue(sharpe.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void fallingCurve_negativeOrLowSharpe() {
        List<BigDecimal> down = Arrays.asList(
                bd("100"), bd("99"), bd("97"), bd("96"), bd("94"), bd("93"));
        BigDecimal sharpe = SharpeRatioCalculator.fromEquityCurve(down, "DAY");
        assertNotNull(sharpe);
        assertTrue(sharpe.compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void annualizationFactor_byPeriod() {
        assertEquals(252.0, SharpeRatioCalculator.annualizationFactor("DAY"), 0.001);
        assertEquals(52.0, SharpeRatioCalculator.annualizationFactor("WEEK"), 0.001);
        assertEquals(12.0, SharpeRatioCalculator.annualizationFactor("MONTH"), 0.001);
        assertEquals(240.0 * 252, SharpeRatioCalculator.annualizationFactor("MIN_1"), 0.001);
        assertEquals(48.0 * 252, SharpeRatioCalculator.annualizationFactor("MIN_5"), 0.001);
        assertEquals(252.0, SharpeRatioCalculator.annualizationFactor(null), 0.001);
    }
}
