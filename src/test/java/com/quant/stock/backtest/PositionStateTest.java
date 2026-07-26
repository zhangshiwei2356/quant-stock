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

    /** P0-108：止损线 = max(成本−atrMult×ATR, 成本−权益×hardPct/股数) */
    @Test
    void atrAndHardStopTakeMax() {
        PositionState atrWins = new PositionState();
        atrWins.addBuy(100, new BigDecimal("10.00"), BigDecimal.ZERO, LocalDate.of(2026, 1, 2));
        // ATR：10-2*0.5=9；硬：10-100000*0.02/100=-10 → max=9
        atrWins.raiseStopByCost(new BigDecimal("0.5"), new BigDecimal("100000"),
                new BigDecimal("2"), new BigDecimal("0.02"));
        assertEquals(0, new BigDecimal("9").compareTo(atrWins.getStopPrice()));

        PositionState hardWins = new PositionState();
        hardWins.addBuy(10000, new BigDecimal("10.00"), BigDecimal.ZERO, LocalDate.of(2026, 1, 2));
        // ATR：9；硬：10-100000*0.02/10000=9.8 → max=9.8
        hardWins.raiseStopByCost(new BigDecimal("0.5"), new BigDecimal("100000"),
                new BigDecimal("2"), new BigDecimal("0.02"));
        assertEquals(0, new BigDecimal("9.8").compareTo(hardWins.getStopPrice()));
    }

    @Test
    void trailingStopOnlyRaises() {
        PositionState pos = new PositionState();
        pos.addBuy(100, new BigDecimal("10"), BigDecimal.ZERO, LocalDate.of(2026, 1, 2));
        pos.updateHighest(new BigDecimal("12"));
        pos.raiseTrailingStop(new BigDecimal("0.5"), new BigDecimal("1.5"));
        // 12 - 1.5*0.5 = 11.25
        assertEquals(0, new BigDecimal("11.25").compareTo(pos.getStopPrice()));
        BigDecimal before = pos.getStopPrice();
        pos.raiseTrailingStop(new BigDecimal("2"), new BigDecimal("1.5")); // 更宽 trail 不应下移
        assertEquals(0, before.compareTo(pos.getStopPrice()));
    }
}
