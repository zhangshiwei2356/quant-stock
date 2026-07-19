package com.quant.stock.calendar;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A 股交易日历（本地静态版）：排除周末 + 内置法定节假日（需每年维护）。
 * 调休补班未单独建模时按「非周末且非假日」交易；复杂调休见待办清单。
 */
@Component
public class TradingCalendar {

    private final Set<LocalDate> holidays;

    public TradingCalendar() {
        Set<LocalDate> h = new HashSet<LocalDate>();
        // 2025
        addRange(h, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1));
        addRange(h, LocalDate.of(2025, 1, 28), LocalDate.of(2025, 2, 4));
        addRange(h, LocalDate.of(2025, 4, 4), LocalDate.of(2025, 4, 6));
        addRange(h, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 5));
        addRange(h, LocalDate.of(2025, 5, 31), LocalDate.of(2025, 6, 2));
        addRange(h, LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 8));
        // 2026
        addRange(h, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));
        addRange(h, LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 23));
        addRange(h, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6));
        addRange(h, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5));
        addRange(h, LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 21));
        addRange(h, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8));
        this.holidays = Collections.unmodifiableSet(h);
    }

    private static void addRange(Set<LocalDate> set, LocalDate from, LocalDate to) {
        LocalDate d = from;
        while (!d.isAfter(to)) {
            set.add(d);
            d = d.plusDays(1);
        }
    }

    public boolean isAshareTradingDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek w = date.getDayOfWeek();
        if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.contains(date);
    }
}
