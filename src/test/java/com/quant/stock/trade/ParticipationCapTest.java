package com.quant.stock.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticipationCapTest {

    @Test
    void capsToAdvTimesParticipationRoundedDownToLot() {
        // ADV=10000, 10% → max 1000
        assertEquals(1000, ParticipationCap.capVolume(5000, 10000L, new BigDecimal("0.10")));
        assertEquals(900, ParticipationCap.capVolume(900, 10000L, new BigDecimal("0.10")));
    }

    @Test
    void disabledWhenNonPositive() {
        assertEquals(5000, ParticipationCap.capVolume(5000, 10000L, BigDecimal.ZERO));
        assertEquals(5000, ParticipationCap.capVolume(5000, 10000L, new BigDecimal("-1")));
    }

    @Test
    void belowOneLotReturnsZero() {
        assertEquals(0, ParticipationCap.capVolume(5000, 500L, new BigDecimal("0.10")));
        assertEquals(0, ParticipationCap.capVolume(50, 100000L, new BigDecimal("0.10")));
    }
}
