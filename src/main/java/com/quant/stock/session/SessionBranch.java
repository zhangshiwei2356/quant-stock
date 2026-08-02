package com.quant.stock.session;

import java.time.LocalTime;

/**
 * 会话三分支（贴隔日高开形状的默认墙钟窗口，可后续外置配置）。
 */
public enum SessionBranch {
    OPEN(LocalTime.of(9, 30), LocalTime.of(10, 0)),
    MID(LocalTime.of(10, 0), LocalTime.of(14, 30)),
    CLOSE(LocalTime.of(14, 30), LocalTime.of(15, 0));

    private final LocalTime startInclusive;
    private final LocalTime endExclusive;

    SessionBranch(LocalTime startInclusive, LocalTime endExclusive) {
        this.startInclusive = startInclusive;
        this.endExclusive = endExclusive;
    }

    public LocalTime getStartInclusive() {
        return startInclusive;
    }

    public LocalTime getEndExclusive() {
        return endExclusive;
    }

    /** 按 bar 起始时间落入分支；非交易时段返回 null。 */
    public static SessionBranch of(LocalTime t) {
        if (t == null) {
            return null;
        }
        for (SessionBranch b : values()) {
            if (!t.isBefore(b.startInclusive) && t.isBefore(b.endExclusive)) {
                return b;
            }
        }
        // 15:00 整点归入 CLOSE
        if (!t.isBefore(LocalTime.of(15, 0)) && !t.isAfter(LocalTime.of(15, 0))) {
            return CLOSE;
        }
        return null;
    }
}
