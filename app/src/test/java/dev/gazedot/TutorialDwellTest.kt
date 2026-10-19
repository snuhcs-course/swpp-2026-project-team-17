package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class TutorialDwellTest {
    @Test fun selectsOnlyAfterOneSecondOfFreshSamples() {
        val dwell = TutorialDwell()
        for (time in 0L..900L step 100) assertFalse(dwell.update(true, time, time))
        assertEquals(.9f, dwell.progress, .001f)
        assertTrue(dwell.update(true, 1_000, 1_000))
    }

    @Test fun leavingButtonOrInvalidGazeRestartsTheFullSecond() {
        val dwell = TutorialDwell()
        for (time in 0L..700L step 100) dwell.update(true, time, time)
        assertFalse(dwell.update(false, 750, 750))
        assertEquals(0f, dwell.progress, 0f)
        for (time in 800L..1_700L step 100) assertFalse(dwell.update(true, time, time))
        assertTrue(dwell.update(true, 1_800, 1_800))
        dwell.reset()
        assertEquals(0f, dwell.progress, 0f)
    }

    @Test fun missingFramesExpireWithoutSelectingAndCannotBridgeAGap() {
        val dwell = TutorialDwell()
        for (time in 0L..800L step 100) dwell.update(true, time, time)
        dwell.expire(1_051)
        assertEquals(0f, dwell.progress, 0f)
        assertFalse(dwell.update(true, 1_100, 1_100))
        assertEquals(0f, dwell.progress, 0f)
        assertFalse(dwell.update(true, 1_500, 1_500))
        assertEquals(0f, dwell.progress, 0f)
    }

    @Test fun rejectsStaleFutureAndRepeatedSamples() {
        val dwell = TutorialDwell()
        assertFalse(dwell.update(true, 0, 300))
        assertFalse(dwell.update(true, 400, 300))
        dwell.update(true, 500, 500)
        dwell.update(true, 600, 600)
        assertFalse(dwell.update(true, 600, 700))
        assertEquals(0f, dwell.progress, 0f)
    }

    @Test fun convertsNormalizedDisplayCoordinatesToActualButtonScreenBounds() {
        val button = TutorialButtonBounds(left = 100, top = 700, width = 200, height = 50)
        assertTrue(button.contains(ScreenGaze(.5, .725, 0), 400, 1_000))
        // A window-local y coordinate must not be mistaken for the full-screen coordinate.
        assertFalse(button.contains(ScreenGaze(.5, .625, 0), 400, 1_000))
        assertFalse(button.contains(ScreenGaze(.25, .7, 0), 400, 1_000)) // rounded corner
        assertFalse(button.contains(ScreenGaze(.8, .725, 0), 400, 1_000))
        assertFalse(button.contains(ScreenGaze(Double.NaN, .725, 0), 400, 1_000))
        assertFalse(button.contains(ScreenGaze(.5, Double.POSITIVE_INFINITY, 0), 400, 1_000))
        assertFalse(button.contains(ScreenGaze(1.1, .725, 0), 400, 1_000))
        assertFalse(button.copy(width = 0).contains(ScreenGaze(.5, .725, 0), 400, 1_000))
    }
}
