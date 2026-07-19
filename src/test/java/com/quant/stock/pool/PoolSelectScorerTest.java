package com.quant.stock.pool;

import com.quant.stock.backtest.dto.BatchScanResultDTO;
import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolSelectScorerTest {

    private PoolSelectScorer scorer;
    private QuantProperties props;

    @BeforeEach
    void setUp() {
        props = new QuantProperties();
        props.setPoolScoreMin(new BigDecimal("45"));
        props.setRsiBuyMax(new BigDecimal("60"));
        scorer = new PoolSelectScorer(props);
    }

    @Test
    void fullBullScoresHigherThanWeak() {
        BatchScanResultDTO bull = base("600036")
                .ma5(new BigDecimal("12"))
                .ma10(new BigDecimal("11"))
                .ma20(new BigDecimal("10"))
                .ma60(new BigDecimal("9"))
                .ma60SlopeUp(true)
                .adx14(new BigDecimal("32"))
                .mom5(new BigDecimal("0.05"))
                .mom20(new BigDecimal("0.20"))
                .atr14(new BigDecimal("0.25"))
                .lastClose(new BigDecimal("12"))
                .avgAmount20(new BigDecimal("120000000"))
                .canBuyNow(false)
                .build();
        BatchScanResultDTO weak = base("000001")
                .ma5(new BigDecimal("9"))
                .ma10(new BigDecimal("10"))
                .ma20(new BigDecimal("11"))
                .ma60(new BigDecimal("12"))
                .ma60SlopeUp(false)
                .adx14(new BigDecimal("15"))
                .mom5(new BigDecimal("-0.02"))
                .mom20(new BigDecimal("-0.10"))
                .atr14(new BigDecimal("0.80"))
                .lastClose(new BigDecimal("10"))
                .avgAmount20(new BigDecimal("1000000"))
                .canBuyNow(false)
                .build();
        List<BatchScanResultDTO> list = Arrays.asList(bull, weak);
        scorer.applyScores(list);
        assertTrue(bull.getPoolScore().compareTo(weak.getPoolScore()) > 0);
        assertTrue(scorer.isEligible(bull));
        assertFalse(scorer.isEligible(weak));
    }

    @Test
    void pickTopOrdersByPoolScore() {
        BatchScanResultDTO a = base("A")
                .ma5(new BigDecimal("12")).ma10(new BigDecimal("11"))
                .ma20(new BigDecimal("10")).ma60(new BigDecimal("9"))
                .ma60SlopeUp(true).adx14(new BigDecimal("35"))
                .mom5(new BigDecimal("0.04")).mom20(new BigDecimal("0.25"))
                .atr14(new BigDecimal("0.2")).lastClose(new BigDecimal("12"))
                .avgAmount20(new BigDecimal("200000000")).build();
        BatchScanResultDTO b = base("B")
                .ma5(new BigDecimal("11")).ma10(new BigDecimal("10.5"))
                .ma20(new BigDecimal("10")).ma60(new BigDecimal("9.5"))
                .ma60SlopeUp(true).adx14(new BigDecimal("22"))
                .mom5(new BigDecimal("0.02")).mom20(new BigDecimal("0.08"))
                .atr14(new BigDecimal("0.25")).lastClose(new BigDecimal("11"))
                .avgAmount20(new BigDecimal("60000000")).build();
        List<BatchScanResultDTO> top = scorer.pickTop(Arrays.asList(b, a), 1);
        assertEquals(1, top.size());
        assertEquals("A", top.get(0).getStockCode());
    }

    private static BatchScanResultDTO.BatchScanResultDTOBuilder base(String code) {
        return BatchScanResultDTO.builder()
                .stockCode(code)
                .rsi14(new BigDecimal("50"))
                .totalRate(BigDecimal.ZERO);
    }
}
