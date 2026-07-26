package com.quant.stock.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FillVolumeScaleTest {

    @Test
    void fullScaleKeepsLots() {
        assertEquals(500, FillVolumeScale.scaleToLot(500, BigDecimal.ONE));
        // 400×0.5=200 → 整手 200；250×0.5=125 → 整手 100
        assertEquals(200, FillVolumeScale.scaleToLot(400, new BigDecimal("0.5")));
        assertEquals(100, FillVolumeScale.scaleToLot(250, new BigDecimal("0.5")));
    }

    @Test
    void belowOneLotCancels() {
        assertEquals(0, FillVolumeScale.scaleToLot(100, new BigDecimal("0.5")));
        assertEquals(0, FillVolumeScale.scaleToLot(500, BigDecimal.ZERO));
        assertEquals(0, FillVolumeScale.scaleToLot(80, BigDecimal.ONE));
    }
}
