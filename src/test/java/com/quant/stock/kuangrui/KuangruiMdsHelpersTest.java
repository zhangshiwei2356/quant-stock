package com.quant.stock.kuangrui;

import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class KuangruiMdsHelpersTest {

    @Test
    void priceScale_dividesBy10000() {
        assertEquals(new BigDecimal("12.3400"), KuangruiPriceScale.toYuan(123400L));
        assertNull(KuangruiPriceScale.toYuan(0L));
        assertNull(KuangruiPriceScale.toYuan(-1L));
        assertEquals(new BigDecimal("0.0000"), KuangruiPriceScale.toYuanAllowZero(0L));
    }

    @Test
    void exchangeIds_fromCode() {
        assertEquals(KuangruiExchangeIds.SSE, KuangruiExchangeIds.fromStockCode("600036"));
        assertEquals(KuangruiExchangeIds.SZSE, KuangruiExchangeIds.fromStockCode("000001"));
        assertEquals(KuangruiExchangeIds.SZSE, KuangruiExchangeIds.fromStockCode("300059"));
        assertEquals(KuangruiExchangeIds.BSE, KuangruiExchangeIds.fromStockCode("920000"));
        assertEquals(600036, KuangruiExchangeIds.toInstrId("600036"));
    }

    @Test
    void barTime_floorsToMinute_hhmmssAndMillis() {
        LocalDateTime a = MdsBarTimeUtil.barBegin(20260803, 93512);
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 35, 0), a);
        LocalDateTime b = MdsBarTimeUtil.barBegin(20260803, 93512999);
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 35, 0), b);
    }

    @Test
    void minuteBucket_rollsOhlcAndClosesOnMinuteChange() {
        MdsMinuteBucket bucket = new MdsMinuteBucket("600036");
        LocalDateTime m1 = LocalDateTime.of(2026, 8, 3, 9, 35);
        LocalDateTime m2 = LocalDateTime.of(2026, 8, 3, 9, 36);
        assertNull(bucket.onTick(m1, new BigDecimal("10.00"), 1000L, 10_000_000L));
        assertNull(bucket.onTick(m1, new BigDecimal("10.50"), 1100L, 11_050_000L));
        BarDTO closed = bucket.onTick(m2, new BigDecimal("10.20"), 1200L, 12_070_000L);
        assertNotNull(closed);
        assertEquals(m1, closed.getBarBegin());
        assertEquals(new BigDecimal("10.00"), closed.getOpen());
        assertEquals(new BigDecimal("10.50"), closed.getHigh());
        assertEquals(new BigDecimal("10.00"), closed.getLow());
        assertEquals(new BigDecimal("10.50"), closed.getClose());
        assertEquals(100L, closed.getVolume().longValue());
        assertNotNull(bucket.snapshot());
        assertEquals(m2, bucket.snapshot().getBarBegin());
    }

    @Test
    void noopMds_m4QueriesEmpty() {
        NoopMdsMinuteIngestService noop = new NoopMdsMinuteIngestService();
        assertFalse(noop.isLive());
        assertTrue(noop.queryStockStatic("600036").isEmpty());
        assertTrue(noop.querySecurityStatus("600036").isEmpty());
        assertTrue(noop.queryTrdSessionStatus().isEmpty());
    }
}
