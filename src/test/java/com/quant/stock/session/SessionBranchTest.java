package com.quant.stock.session;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBranchTest {

    @Test
    void ofMapsDefaultWindows() {
        assertEquals(SessionBranch.OPEN, SessionBranch.of(LocalTime.of(9, 30)));
        assertEquals(SessionBranch.OPEN, SessionBranch.of(LocalTime.of(9, 59)));
        assertEquals(SessionBranch.MID, SessionBranch.of(LocalTime.of(10, 0)));
        assertEquals(SessionBranch.MID, SessionBranch.of(LocalTime.of(14, 29)));
        assertEquals(SessionBranch.CLOSE, SessionBranch.of(LocalTime.of(14, 30)));
        assertEquals(SessionBranch.CLOSE, SessionBranch.of(LocalTime.of(14, 59)));
        // 午休墙钟仍落入 MID 窗口，但 SessionTradingMinutes 会过滤
        assertEquals(SessionBranch.MID, SessionBranch.of(LocalTime.of(11, 45)));
        assertNull(SessionBranch.of(LocalTime.of(8, 0)));
        assertTrue(SessionTradingMinutes.isTradingMinute(LocalTime.of(9, 45)));
        assertTrue(!SessionTradingMinutes.isTradingMinute(LocalTime.of(12, 0)));
    }
}
