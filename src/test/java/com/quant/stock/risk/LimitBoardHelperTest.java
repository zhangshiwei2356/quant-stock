package com.quant.stock.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LimitBoardHelperTest {

    @Test
    void pctByBoard() {
        assertEquals(0, new BigDecimal("0.10").compareTo(LimitBoardHelper.limitPct("600036")));
        assertEquals(0, new BigDecimal("0.20").compareTo(LimitBoardHelper.limitPct("300059")));
        assertEquals(0, new BigDecimal("0.20").compareTo(LimitBoardHelper.limitPct("688001")));
    }

    @Test
    void limitPrices() {
        BigDecimal prev = new BigDecimal("10.00");
        assertEquals(0, new BigDecimal("11.00").compareTo(LimitBoardHelper.limitUpPrice(prev, "600036")));
        assertEquals(0, new BigDecimal("9.00").compareTo(LimitBoardHelper.limitDownPrice(prev, "600036")));
        assertEquals(0, new BigDecimal("12.00").compareTo(LimitBoardHelper.limitUpPrice(prev, "300059")));
    }
}
