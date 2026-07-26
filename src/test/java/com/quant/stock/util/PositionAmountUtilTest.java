package com.quant.stock.util;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ATR 定仓验收（P0-108）：调节系数夹紧 0.2~1.5，整手。
 */
class PositionAmountUtilTest {

    private QuantProperties props;
    private PositionAmountUtil util;

    @BeforeEach
    void setUp() {
        props = new QuantProperties();
        props.setMaxSinglePosition(new BigDecimal("0.30"));
        props.setBaseAtr(new BigDecimal("0.05"));
        util = new PositionAmountUtil(props);
    }

    @Test
    void atrLowBoostsSizeCappedAt1_5() {
        // atr=0.02 → rate=0.05/0.02=2.5 → clamp 1.5
        int volHighAtrAdj = util.calcBuyVolume(new BigDecimal("100000"), new BigDecimal("10"),
                new BigDecimal("0.02"));
        // atr=base → rate=1
        int volBase = util.calcBuyVolume(new BigDecimal("100000"), new BigDecimal("10"),
                new BigDecimal("0.05"));
        assertTrue(volHighAtrAdj > volBase);
        assertEquals(0, volHighAtrAdj % 100);
        // 上限资金 100000*0.3*1.5=45000 → 4500股
        assertEquals(4500, volHighAtrAdj);
        assertEquals(3000, volBase);
    }

    @Test
    void atrHighShrinksSizeFlooredAt0_2() {
        // atr=1.0 → rate=0.05 → clamp 0.2
        int vol = util.calcBuyVolume(new BigDecimal("100000"), new BigDecimal("10"),
                new BigDecimal("1.0"));
        // 100000*0.3*0.2=6000 → 600股
        assertEquals(600, vol);
    }

    @Test
    void pyramidSlices50_30_20() {
        assertEquals(500, util.pyramidSlice(1000, 0));
        assertEquals(300, util.pyramidSlice(1000, 1));
        assertEquals(200, util.pyramidSlice(1000, 2));
    }
}
