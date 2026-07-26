package com.quant.stock.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ShortSellPolicyTest {

    @Test
    void longOnly() {
        assertFalse(ShortSellPolicy.allowShort());
        assertEquals("LONG_ONLY", ShortSellPolicy.status().get("mode"));
    }
}
