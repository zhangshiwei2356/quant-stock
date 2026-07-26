package com.quant.stock.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-94 限价保护：买不破涨停、卖不破跌停。
 */
class LimitPriceProtectTest {

    @Test
    void clampBuyToLimitUp() {
        BigDecimal prev = new BigDecimal("10.00");
        BigDecimal up = LimitBoardHelper.limitUpPrice(prev, "600036", false);
        BigDecimal clamped = LimitPriceProtect.clampBuy(new BigDecimal("12.00"), prev, "600036", false);
        assertEquals(0, up.compareTo(clamped));
    }

    @Test
    void clampSellToLimitDown() {
        BigDecimal prev = new BigDecimal("10.00");
        BigDecimal down = LimitBoardHelper.limitDownPrice(prev, "600036", false);
        BigDecimal clamped = LimitPriceProtect.clampSell(new BigDecimal("8.00"), prev, "600036", false);
        assertEquals(0, down.compareTo(clamped));
    }

    @Test
    void withinBandUnchanged() {
        BigDecimal prev = new BigDecimal("10.00");
        BigDecimal deal = new BigDecimal("10.05");
        assertEquals(0, deal.compareTo(LimitPriceProtect.clampBuy(deal, prev, "600036", false)));
        assertEquals(0, deal.compareTo(LimitPriceProtect.clampSell(deal, prev, "600036", false)));
    }

    @Test
    void rejectBuyWhenBaseAtLimitUp() {
        BigDecimal prev = new BigDecimal("10.00");
        BigDecimal up = LimitBoardHelper.limitUpPrice(prev, "600036", false);
        assertTrue(LimitPriceProtect.shouldRejectBuy(up, prev, "600036", false));
        assertFalse(LimitPriceProtect.shouldRejectBuy(new BigDecimal("10.01"), prev, "600036", false));
    }
}
