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
        assertEquals("PARTIAL", OesViewMapper.ordStatusLabel(3));
        assertEquals("FILLED", OesViewMapper.ordStatusLabel(8));
        assertEquals("CANCELLED", OesViewMapper.ordStatusLabel(7));
        assertEquals("REJECTED", OesViewMapper.ordStatusLabel(11));
        assertEquals("FILLED", OesViewMapper.toLocalStatusName(8));
        assertEquals("CANCELLED", OesViewMapper.toLocalStatusName(7));
    }

    @Test
    void viewMapper_orderAndTrade() {
        Map<String, Object> o = OesViewMapper.order("000001", 9L, 1, 8, 100000L, 200, 200);
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
    void priceScale_toMilli() {
        assertEquals(123400, KuangruiPriceScale.toMilliInt(new BigDecimal("12.34")));
        assertEquals(0, KuangruiPriceScale.toMilliInt(null));
        assertEquals(0, KuangruiPriceScale.toMilliInt(BigDecimal.ZERO));
    }

    @Test
    void noopOrder_isNotLive() {
        NoopOesOrderService noop = new NoopOesOrderService();
        assertFalse(noop.isOrderLive());
        assertTrue(noop.pollEvents().isEmpty());
        assertFalse(noop.cancelByClSeqNo(1, "600036"));
        assertFalse(noop.placeLimit("600036", com.quant.stock.trade.dto.OrderDTO.Side.BUY,
                new BigDecimal("10"), 100, 1, "c").isAccepted());
    }

    @Test
    void noop_isNotLive() {
        NoopOesReadonlyService noop = new NoopOesReadonlyService();
        assertFalse(noop.isLive());
        assertFalse(noop.ensureReady());
        assertTrue(noop.queryCash().isEmpty());
        assertTrue(noop.queryStock("600036").isEmpty());
        assertTrue(noop.queryTradingDay().isEmpty());
        assertTrue(noop.queryCommissionRate().isEmpty());
        assertEquals("noop", noop.status().get("impl"));
        assertEquals(Boolean.FALSE, noop.snapshot().get("ok"));
    }

    @Test
    void viewMapper_stockTradingDayCommission() {
        Map<String, Object> s = OesViewMapper.stock("600036", "招商银行",
                110000L, 90000L, 100000L, 25_0000_0000L, 20_0000_0000L, 0, 0);
        assertEquals("600036", s.get("code"));
        assertEquals(new BigDecimal("11.0000"), s.get("upperLimit"));
        assertEquals(new BigDecimal("9.0000"), s.get("lowerLimit"));
        assertEquals(Boolean.FALSE, s.get("suspended"));
        assertEquals(new BigDecimal("20.0000"), s.get("floatSharesYi"));

        Map<String, Object> sus = OesViewMapper.stock("000001", "x", 0, 0, 0, 0, 0, 1, 0);
        assertEquals(Boolean.TRUE, sus.get("suspended"));

        Map<String, Object> td = OesViewMapper.tradingDay(20260806);
        assertEquals("2026-08-06", td.get("tradingDay"));
        assertEquals("", OesViewMapper.formatYyyymmdd(0));

        Map<String, Object> c = OesViewMapper.commission(1, 1, 30000L, 50000L, null);
        assertEquals(0, new BigDecimal("0.00030000").compareTo((BigDecimal) c.get("feeRate")));
        assertEquals(new BigDecimal("5.0000"), c.get("minFee"));
        assertEquals(0, new BigDecimal("0.0003").compareTo(OesViewMapper.decodeFeeRate(30000L)));
    }

    @Test
    void mdsViewMapper_sessionAndStatus() {
        Map<String, Object> st = MdsViewMapper.securityStatus("600036", 0, 0, 1);
        assertEquals(Boolean.FALSE, st.get("suspended"));
        Map<String, Object> sess = MdsViewMapper.trdSession(1, 1, 1);
        assertEquals(Boolean.TRUE, sess.get("open"));
    }
}
