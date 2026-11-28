package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class RecognitionRecoveryDelayTest {
    @Test fun keepsChoicesAvailableForTwoSecondsAfterRecovery() {
        val delay = RecognitionRecoveryDelay()
        assertFalse(delay.update(100, true))
        assertFalse(delay.update(2_099, true))
        assertTrue(delay.update(2_100, true))
    }

    @Test fun lostOrStaleTrackingRestartsTheWait() {
        val delay = RecognitionRecoveryDelay()
        assertFalse(delay.update(0, true))
        assertFalse(delay.update(1_900, false))
        assertFalse(delay.update(2_000, true))
        assertFalse(delay.update(3_999, true))
        assertTrue(delay.update(4_000, true))
    }

    @Test fun userActionClearsTheOldDeadline() {
        val delay = RecognitionRecoveryDelay()
        delay.update(0, true)
        delay.reset()
        assertFalse(delay.update(10_000, true))
        assertFalse(delay.update(11_999, true))
        assertTrue(delay.update(12_000, true))
    }
}
