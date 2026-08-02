package com.quant.stock.session;

import com.quant.stock.config.QuantProperties;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 可配置的会话三分支墙钟窗口；解析失败时回退 {@link SessionBranch} 默认值。
 */
public final class SessionWindows {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("H:mm");

    private final LocalTime openStart;
    private final LocalTime openEnd;
    private final LocalTime midStart;
    private final LocalTime midEnd;
    private final LocalTime closeStart;
    private final LocalTime closeEnd;

    public SessionWindows(LocalTime openStart, LocalTime openEnd,
                          LocalTime midStart, LocalTime midEnd,
                          LocalTime closeStart, LocalTime closeEnd) {
        this.openStart = openStart;
        this.openEnd = openEnd;
        this.midStart = midStart;
        this.midEnd = midEnd;
        this.closeStart = closeStart;
        this.closeEnd = closeEnd;
    }

    public static SessionWindows from(QuantProperties props) {
        QuantProperties.Session s = props == null || props.getSession() == null
                ? new QuantProperties.Session()
                : props.getSession();
        return new SessionWindows(
                parse(s.getOpenStart(), SessionBranch.OPEN.getStartInclusive()),
                parse(s.getOpenEnd(), SessionBranch.OPEN.getEndExclusive()),
                parse(s.getMidStart(), SessionBranch.MID.getStartInclusive()),
                parse(s.getMidEnd(), SessionBranch.MID.getEndExclusive()),
                parse(s.getCloseStart(), SessionBranch.CLOSE.getStartInclusive()),
                parse(s.getCloseEnd(), SessionBranch.CLOSE.getEndExclusive())
        );
    }

    public SessionBranch of(LocalTime t) {
        if (t == null) {
            return null;
        }
        if (!t.isBefore(openStart) && t.isBefore(openEnd)) {
            return SessionBranch.OPEN;
        }
        if (!t.isBefore(midStart) && t.isBefore(midEnd)) {
            return SessionBranch.MID;
        }
        if (!t.isBefore(closeStart) && t.isBefore(closeEnd)) {
            return SessionBranch.CLOSE;
        }
        if (!t.isBefore(closeEnd) && !t.isAfter(closeEnd)) {
            return SessionBranch.CLOSE;
        }
        return null;
    }

    public String fingerprintPart() {
        return "open=" + openStart + "-" + openEnd
                + "|mid=" + midStart + "-" + midEnd
                + "|close=" + closeStart + "-" + closeEnd;
    }

    private static LocalTime parse(String raw, LocalTime fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalTime.parse(raw.trim(), HM);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
