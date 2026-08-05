package com.quant.stock.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyScoreCalculatorTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void noRuns_scoreNull() {
        Map<String, Object> m = StrategyScoreCalculator.score(null, null, null, null, 0);
        assertNull(m.get("score"));
        assertEquals(100, m.get("scoreMax"));
        assertTrue(((List<?>) m.get("components")).isEmpty());
    }

    @Test
    void strongMetrics_nearFull() {
        // 收益 50%→30；回撤 5%→25；胜率 100%→20；盈利占比 100%→15；样本 10→10 = 100
        Map<String, Object> m = StrategyScoreCalculator.score(
                bd("0.50"), bd("0.05"), bd("1.00"), bd("1.00"), 10);
        assertEquals(100, m.get("score"));
        assertEquals("A", m.get("grade"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comps = (List<Map<String, Object>>) m.get("components");
        assertEquals(5, comps.size());
    }

    @Test
    void weakMetrics_lowScore() {
        Map<String, Object> m = StrategyScoreCalculator.score(
                bd("-0.20"), bd("0.40"), bd("0"), bd("0"), 1);
        int score = (Integer) m.get("score");
        // 仅样本 1/10 → 1 分
        assertEquals(1, score);
        assertEquals("E", m.get("grade"));
    }

    @Test
    void midReturn_partialPoints() {
        // (0.15 - (-0.20)) / 0.70 * 30 ≈ 15
        BigDecimal p = StrategyScoreCalculator.pointsReturn(bd("0.15"));
        assertEquals(0, p.compareTo(bd("15.00")));
    }

    @Test
    void sampleCapAtTen() {
        assertEquals(0, StrategyScoreCalculator.pointsSample(10).compareTo(bd("10.00")));
        assertEquals(0, StrategyScoreCalculator.pointsSample(99).compareTo(bd("10.00")));
        assertEquals(0, StrategyScoreCalculator.pointsSample(5).compareTo(bd("5.00")));
    }

    @Test
    void gradeBoundaries() {
        assertEquals("A", StrategyScoreCalculator.gradeOf(90));
        assertEquals("B", StrategyScoreCalculator.gradeOf(80));
        assertEquals("C", StrategyScoreCalculator.gradeOf(70));
        assertEquals("D", StrategyScoreCalculator.gradeOf(60));
        assertEquals("E", StrategyScoreCalculator.gradeOf(59));
        assertNotNull(StrategyScoreCalculator.gradeOf(0));
    }
}
