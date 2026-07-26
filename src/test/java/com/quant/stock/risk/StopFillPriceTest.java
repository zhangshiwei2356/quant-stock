package com.quant.stock.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-115 缺口穿价 / 盘中触及止损成交基准用例集。
 */
class StopFillPriceTest {

    @Test
    void gapThroughFillsAtOpenNotStop() {
        // 昨止损 10，今开 9.2（跳空跌破），最低 9.0 → 应按开盘 9.2，而非乐观止损价 10
        StopFillPrice.Result r = StopFillPrice.resolve(
                new BigDecimal("9.20"), new BigDecimal("9.00"), new BigDecimal("10.00"));
        assertTrue(r.triggered());
        assertEquals(StopFillPrice.Mode.GAP_THROUGH, r.mode);
        assertEquals(0, new BigDecimal("9.20").compareTo(r.fillBase));
    }

    @Test
    void intradayTouchFillsAtStop() {
        // 开盘 10.5 > 止损 10，最低 9.8 触及 → 按止损价 10
        StopFillPrice.Result r = StopFillPrice.resolve(
                new BigDecimal("10.50"), new BigDecimal("9.80"), new BigDecimal("10.00"));
        assertTrue(r.triggered());
        assertEquals(StopFillPrice.Mode.INTRADAY_TOUCH, r.mode);
        assertEquals(0, new BigDecimal("10.00").compareTo(r.fillBase));
    }

    @Test
    void notTriggeredWhenLowAboveStop() {
        StopFillPrice.Result r = StopFillPrice.resolve(
                new BigDecimal("10.50"), new BigDecimal("10.20"), new BigDecimal("10.00"));
        assertFalse(r.triggered());
        assertEquals(StopFillPrice.Mode.NONE, r.mode);
    }

    @Test
    void openEqualsStopIsGapThrough() {
        StopFillPrice.Result r = StopFillPrice.resolve(
                new BigDecimal("10.00"), new BigDecimal("9.50"), new BigDecimal("10.00"));
        assertEquals(StopFillPrice.Mode.GAP_THROUGH, r.mode);
        assertEquals(0, new BigDecimal("10.00").compareTo(r.fillBase));
    }

    @Test
    void limitDownOpenIsGapThroughAdverse() {
        // 跌停开盘视作跳空穿价：基准=开盘/跌停价（后续由撮合层再处理封板）
        StopFillPrice.Result r = StopFillPrice.resolve(
                new BigDecimal("9.00"), new BigDecimal("9.00"), new BigDecimal("10.00"));
        assertEquals(StopFillPrice.Mode.GAP_THROUGH, r.mode);
        assertEquals(0, new BigDecimal("9.00").compareTo(r.fillBase));
    }
}
