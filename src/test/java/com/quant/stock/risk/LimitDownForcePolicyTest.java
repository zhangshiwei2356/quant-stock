package com.quant.stock.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitDownForcePolicyTest {

    @Test
    void forceOnDayThree() {
        assertFalse(LimitDownForcePolicy.forceSell(2));
        assertTrue(LimitDownForcePolicy.forceSell(3));
        assertTrue(LimitDownForcePolicy.forceSell(4));
    }

    @Test
    void deferThenSellAfterThreshold() {
        assertTrue(LimitDownForcePolicy.deferForLimitDown(true, 0));
        assertTrue(LimitDownForcePolicy.deferForLimitDown(true, 2));
        assertFalse(LimitDownForcePolicy.deferForLimitDown(true, 3));
        assertFalse(LimitDownForcePolicy.deferForLimitDown(false, 0));

        assertTrue(LimitDownForcePolicy.shouldSellNow(false, 0));
        assertFalse(LimitDownForcePolicy.shouldSellNow(true, 1));
        assertTrue(LimitDownForcePolicy.shouldSellNow(true, 3));
    }
}
