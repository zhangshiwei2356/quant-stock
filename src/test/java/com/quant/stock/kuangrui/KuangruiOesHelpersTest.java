package com.quant.stock.kuangrui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KuangruiOesHelpersTest {

    @Test
    void priceScale_allowZero() {
        assertEquals(new BigDecimal("0.0000"), KuangruiPriceScale.toYuanAllowZero(0L));
        assertEquals(new BigDecimal("12.3400"), KuangruiPriceScale.toYuanAllowZero(123400L));
        assertEquals(BigDecimal.ZERO, KuangruiPriceScale.toYuanAllowZero((Long) null));
    }

    @Test
    void viewMapper_cashAndHolding() {
        Map<String, Object> cash = OesViewMapper.cash("A001", 1_000_000L, 800_000L, 700_000L);
        assertEquals(new BigDecimal("100.0000"), cash.get("currentTotalBal"));
        assertEquals(new BigDecimal("80.0000"), cash.get("currentAvailableBal"));
        assertEquals("A001", cash.get("cashAcctId"));

        Map<String, Object> h = OesViewMapper.holding("600036", 1000L, 800L, 123400L);
        assertEquals("600036", h.get("code"));
        assertEquals(1000L, h.get("sumHld"));
        assertEquals(800L, h.get("sellAvlHld"));
        assertEquals(new BigDecimal("12.3400"), h.get("costPrice"));
    }

    @Test
    void viewMapper_normalizeCodeAndOrdStatus() {
        assertEquals("600036", OesViewMapper.normalizeCode("600036.SH"));
        assertEquals("000001", OesViewMapper.normalizeCode("1"));
        assertEquals("FILLED", OesViewMapper.ordStatusLabel(3));
        assertEquals("CANCELLED", OesViewMapper.ordStatusLabel(4));
        assertEquals("STATUS_99", OesViewMapper.ordStatusLabel(99));
    }

    @Test
    void viewMapper_orderAndTrade() {
        Map<String, Object> o = OesViewMapper.order("000001", 9L, 1, 3, 100000L, 200, 200);
        assertEquals("000001", o.get("code"));
        assertEquals("FILLED", o.get("ordStatusLabel"));
        assertEquals(new BigDecimal("10.0000"), o.get("ordPrice"));
        assertEquals(200, o.get("cumQty"));

        Map<String, Object> t = OesViewMapper.trade("000001", 9L, 100000L, 100, 10_000_000L);
        assertEquals(new BigDecimal("10.0000"), t.get("trdPrice"));
        assertEquals(100, t.get("trdQty"));
        assertEquals(new BigDecimal("1000.0000"), t.get("trdAmt"));
    }

    @Test
    void noop_isNotLive() {
        NoopOesReadonlyService noop = new NoopOesReadonlyService();
        assertFalse(noop.isLive());
        assertFalse(noop.ensureReady());
        assertTrue(noop.queryCash().isEmpty());
        assertEquals("noop", noop.status().get("impl"));
        assertEquals(Boolean.FALSE, noop.snapshot().get("ok"));
    }
}
