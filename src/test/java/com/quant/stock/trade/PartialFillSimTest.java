package com.quant.stock.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartialFillSimTest {

    @Test
    void fullRatioFillsAll() {
        assertEquals(1000, PartialFillSim.fillVolume(1000, BigDecimal.ONE));
        assertEquals(0, PartialFillSim.remainder(1000, 1000));
    }

    @Test
    void halfRatioLeavesRemainder() {
        int filled = PartialFillSim.fillVolume(1000, new BigDecimal("0.5"));
        assertEquals(500, filled);
        assertEquals(500, PartialFillSim.remainder(1000, filled));
    }

    @Test
    void roundsDownToLot() {
        assertEquals(400, PartialFillSim.fillVolume(1000, new BigDecimal("0.45")));
    }
}
