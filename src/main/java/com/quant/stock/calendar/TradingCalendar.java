package com.quant.stock.calendar;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A 股交易日历（本地静态版）：排除周末 + 内置法定节假日（需每年按上交所公告维护）。
 * <p>
 * A 股不在调休补班的周六/日开市，故无需单独建模「补班」；周末一律非交易日即可。
 */
@Component
public class TradingCalendar {

    private final Set<LocalDate> holidays;

    public TradingCalendar() {
        Set<LocalDate> h = new HashSet<LocalDate>();
        // 2025（上交所公告：国庆/中秋连休至 10/8）
        addRange(h, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1));
        addRange(h, LocalDate.of(2025, 1, 28), LocalDate.of(2025, 2, 4));
        addRange(h, LocalDate.of(2025, 4, 4), LocalDate.of(2025, 4, 6));
        addRange(h, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 5));
        addRange(h, LocalDate.of(2025, 5, 31), LocalDate.of(2025, 6, 2));
        addRange(h, LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 8));
        // 2026（上交所 2025-12-22 通知）
        addRange(h, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));
        addRange(h, LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 23));
        addRange(h, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6));
        addRange(h, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5));
        addRange(h, LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 21));
        addRange(h, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 27)); // 中秋
        addRange(h, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)); // 国庆；10/8 开市
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

    /** 含 date 在内向前找最近一个 A 股交易日（最多回溯 30 天）。 */
    public LocalDate lastTradingDayOnOrBefore(LocalDate date) {
        if (date == null) {
            return null;
        }
        LocalDate d = date;
        for (int i = 0; i < 30; i++) {
            if (isAshareTradingDay(d)) {
                return d;
            }
            d = d.minusDays(1);
        }
        return date;
    }
}
