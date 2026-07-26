package com.quant.stock.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitPriorityTest {

    @Test
    void rankOrderStopGtHaltGtTimeStopGtDeathCross() {
        assertTrue(ExitPriority.STOP_LOSS.getRank() > ExitPriority.ACCOUNT_HALT.getRank());
        assertTrue(ExitPriority.ACCOUNT_HALT.getRank() > ExitPriority.TIME_STOP.getRank());
        assertTrue(ExitPriority.TIME_STOP.getRank() > ExitPriority.DEATH_CROSS.getRank());
    }

    @Test
    void deathCrossBlockedWhenStoppedOrPending() {
        assertFalse(ExitPriority.DEATH_CROSS.canRegisterPending(true, false));
        assertFalse(ExitPriority.DEATH_CROSS.canRegisterPending(false, true));
        assertTrue(ExitPriority.DEATH_CROSS.canRegisterPending(false, false));
    }

    @Test
    void haltDoesNotStackPending() {
        assertTrue(ExitPriority.ACCOUNT_HALT.canRegisterPending(false, false));
        assertFalse(ExitPriority.ACCOUNT_HALT.canRegisterPending(false, true));
    }

    @Test
    void participationBypassOnlyRiskExits() {
        assertTrue(ExitPriority.STOP_LOSS.bypassParticipationCap());
        assertTrue(ExitPriority.ACCOUNT_HALT.bypassParticipationCap());
        assertTrue(ExitPriority.TIME_STOP.bypassParticipationCap());
        assertFalse(ExitPriority.DEATH_CROSS.bypassParticipationCap());
    }

    @Test
    void fromReasonLabel() {
        assertEquals(ExitPriority.DEATH_CROSS, ExitPriority.fromReasonLabel("死叉"));
        assertEquals(ExitPriority.ACCOUNT_HALT, ExitPriority.fromReasonLabel("回撤熔断（补充）"));
    }
}
