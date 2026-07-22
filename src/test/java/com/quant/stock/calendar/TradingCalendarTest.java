package com.quant.stock.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingCalendarTest {

    private final TradingCalendar calendar = new TradingCalendar();

    @Test
    void year2026MidAutumnClosedAndNationalDayOct8Open() {
        assertFalse(calendar.isAshareTradingDay(LocalDate.of(2026, 9, 25)));
        assertFalse(calendar.isAshareTradingDay(LocalDate.of(2026, 9, 26))); // 周六
        assertFalse(calendar.isAshareTradingDay(LocalDate.of(2026, 10, 7)));
        assertTrue(calendar.isAshareTradingDay(LocalDate.of(2026, 10, 8)));
        assertTrue(calendar.isAshareTradingDay(LocalDate.of(2026, 9, 28)));
    }

    @Test
    void weekendsNeverTrade() {
        assertFalse(calendar.isAshareTradingDay(LocalDate.of(2026, 10, 10))); // 周六
        assertFalse(calendar.isAshareTradingDay(LocalDate.of(2026, 3, 15))); // 周日
    }

    @Test
    void lastTradingDayOnOrBeforeSkipsHolidayWeekend() {
        // 2026-10-04 周日且在国庆休市内 → 回到 9/30（周三）
        assertEquals(LocalDate.of(2026, 9, 30),
                calendar.lastTradingDayOnOrBefore(LocalDate.of(2026, 10, 4)));
        assertEquals(LocalDate.of(2026, 10, 8),
                calendar.lastTradingDayOnOrBefore(LocalDate.of(2026, 10, 8)));
    }
}
