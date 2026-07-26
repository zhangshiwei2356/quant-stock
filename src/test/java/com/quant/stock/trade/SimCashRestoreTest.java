package com.quant.stock.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimCashRestoreTest {

    @Test
    void zeroCashIsRestored() {
        BigDecimal def = new BigDecimal("100000");
        assertEquals(0, BigDecimal.ZERO.compareTo(SimCashRestore.apply(BigDecimal.ZERO, def)));
        assertEquals(0, new BigDecimal("123").compareTo(SimCashRestore.apply(new BigDecimal("123"), def)));
        assertEquals(0, def.compareTo(SimCashRestore.apply(null, def)));
    }
}
