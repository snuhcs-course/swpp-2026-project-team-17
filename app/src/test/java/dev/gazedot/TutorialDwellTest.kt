package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class TutorialDwellTest {
    @Test fun update_continuousGaze_selectsAfterOneSecond() {
        // GIVEN: Fresh gaze samples remain inside the button.
        val dwell = TutorialDwell()

        // WHEN: Samples arrive every 100 ms through the one-second mark.
        for (time in 0L..900L step 100) assertFalse(dwell.update(true, time, time))

        // THEN: Selection completes at one second.
        assertEquals(.9f, dwell.progress, .001f)
        assertTrue(dwell.update(true, 1_000, 1_000))
    }

    @Test fun update_shortGaze_doesNotSelect() {
        // GIVEN: The user has looked at the button for less than one second.
        val dwell = TutorialDwell()
        for (time in 0L..900L step 100) {
            assertFalse(dwell.update(true, time, time))
        }

        // THEN: Selection has not completed yet.
        assertEquals(.9f, dwell.progress, .001f)
    }

    @Test fun update_gazeLeavesButton_resetsProgress() {
        // GIVEN: Gaze has started building selection progress.
        val dwell = TutorialDwell()
        for (time in 0L..700L step 100) dwell.update(true, time, time)

        // WHEN: A sample reports that gaze is outside the button.
        assertFalse(dwell.update(false, 800, 800))

        // THEN: Progress resets to zero.
        assertEquals(0f, dwell.progress, 0f)
    }

    @Test fun update_samplesHaveLongGap_resetsProgress() {
        // GIVEN: Gaze has started building progress with fresh samples.
        val dwell = TutorialDwell()
        for (time in 0L..800L step 100) dwell.update(true, time, time)

        // WHEN: The next sample arrives after a 300 ms gap (over the 250 ms limit).
        assertFalse(dwell.update(true, 1_100, 1_100))

        // THEN: The gap cannot count as continuous gaze, so progress is reset.
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
