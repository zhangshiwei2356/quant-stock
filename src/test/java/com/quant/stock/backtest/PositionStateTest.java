package com.quant.stock.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionStateTest {

    @Test
    void weightedCostAndT1Lots() {
        PositionState pos = new PositionState();
        LocalDate d1 = LocalDate.of(2026, 1, 2);
        LocalDate d2 = LocalDate.of(2026, 1, 3);

        pos.addBuy(200, new BigDecimal("10.00"), new BigDecimal("0.60"), d1);
        assertEquals(200, pos.getShares());
        // (2000+0.60)/200 = 10.003
        assertEquals(0, new BigDecimal("10.0030").compareTo(pos.getAvgCost()));

        pos.addBuy(100, new BigDecimal("11.00"), BigDecimal.ZERO, d2);
        assertEquals(300, pos.getShares());
        assertEquals(200, pos.sellableShares(d2));
        assertTrue(pos.canSellStops(d2));
        assertFalse(pos.canSellStops(d1));

        BigDecimal removed = pos.removeShares(200);
        assertEquals(100, pos.getShares());
        assertTrue(removed.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void stopOnlyRaises() {
        PositionState pos = new PositionState();
        pos.addBuy(100, new BigDecimal("10"), BigDecimal.ZERO, LocalDate.of(2026, 1, 2));
        pos.raiseStopByCost(new BigDecimal("0.5"), new BigDecimal("100000"),
                new BigDecimal("2"), new BigDecimal("0.02"));
        BigDecimal first = pos.getStopPrice();
        pos.raiseStopByCost(new BigDecimal("2"), new BigDecimal("100000"),
                new BigDecimal("2"), new BigDecimal("0.02"));
        assertTrue(pos.getStopPrice().compareTo(first) >= 0);
    }
}
