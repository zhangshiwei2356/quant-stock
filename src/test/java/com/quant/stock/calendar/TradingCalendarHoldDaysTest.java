package com.quant.stock.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingCalendarHoldDaysTest {

    @Test
    void countsWeekdaysAfterOpen() {
        TradingCalendar cal = new TradingCalendar();
        // 2026-07-20 Mon open → 2026-07-21 Tue = 1 trading day after
        assertEquals(1, cal.tradingDaysAfter(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 21)));
        // through Fri 7/24 = 4
        assertEquals(4, cal.tradingDaysAfter(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 24)));
        // weekend doesn't add
        assertEquals(4, cal.tradingDaysAfter(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 26)));
        assertTrue(cal.tradingDaysAfter(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 20)) == 0);
    }
}
