package com.quant.stock.session;

import com.quant.stock.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBranchTest {

    @Test
    void defaultEnumWindows() {
        assertEquals(SessionBranch.OPEN, SessionBranch.of(LocalTime.of(9, 30)));
        assertEquals(SessionBranch.OPEN, SessionBranch.of(LocalTime.of(9, 59)));
        assertEquals(SessionBranch.MID, SessionBranch.of(LocalTime.of(10, 0)));
        assertEquals(SessionBranch.CLOSE, SessionBranch.of(LocalTime.of(14, 30)));
        assertNull(SessionBranch.of(LocalTime.of(8, 0)));
        assertTrue(SessionTradingMinutes.isTradingMinute(LocalTime.of(9, 45)));
        assertTrue(!SessionTradingMinutes.isTradingMinute(LocalTime.of(12, 0)));
    }

    @Test
    void configurableWindowsFromProps() {
        QuantProperties props = new QuantProperties();
        props.getSession().setOpenEnd("09:45");
        props.getSession().setMidStart("09:45");
        SessionWindows w = SessionWindows.from(props);
        assertEquals(SessionBranch.OPEN, w.of(LocalTime.of(9, 40)));
        assertEquals(SessionBranch.MID, w.of(LocalTime.of(9, 45)));
        assertTrue(w.fingerprintPart().contains("09:45"));
    }
}
