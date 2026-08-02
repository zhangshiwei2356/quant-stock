package com.quant.stock.session;

import java.time.LocalTime;

/** A 股连续竞价交易分钟（与聚合口径大致一致）。 */
public final class SessionTradingMinutes {

    private static final LocalTime AM_START = LocalTime.of(9, 30);
    private static final LocalTime AM_END = LocalTime.of(11, 30);
    private static final LocalTime PM_START = LocalTime.of(13, 0);
    private static final LocalTime PM_END = LocalTime.of(15, 0);

    private SessionTradingMinutes() {
    }

    public static boolean isTradingMinute(LocalTime t) {
        if (t == null) {
            return false;
        }
        boolean am = !t.isBefore(AM_START) && t.isBefore(AM_END);
        boolean pm = !t.isBefore(PM_START) && t.isBefore(PM_END);
        return am || pm;
    }
}
