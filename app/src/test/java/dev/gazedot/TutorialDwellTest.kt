package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class TutorialDwellTest {
    @Test fun slowProcessingDoesNotExpireContinuousResultsBetweenFrames() {
        val dwell = TutorialDwell()
        for (now in 0L..1_180L step 10) {
            if (now >= 180 && (now - 180) % 200 == 0L) {
                assertEquals(now == 1_180L, dwell.update(true, now - 180, now))
            }
            if (now % 50 == 0L) dwell.expire(now)
        }
        assertEquals(1f, dwell.progress, 0f)
    }

    @Test fun durationStartsWhenFirstValidResultArrivesNotWhenItWasCaptured() {
        val dwell = TutorialDwell()
        dwell.update(true, 0, 200)
        for (at in 200L..1_000L step 100) assertFalse(dwell.update(true, at, at + 50))
        assertFalse(dwell.update(true, 1_100, 1_150))
        assertTrue(dwell.update(true, 1_200, 1_250))
    }

    @Test fun receiptGapStillExpiresEvenWhenCaptureTimestampsAreClose() {
        val dwell = TutorialDwell()
        dwell.update(true, 0, 0)
        dwell.update(true, 100, 100)
        assertFalse(dwell.update(true, 200, 400))
        assertEquals(0f, dwell.progress, 0f)
    }

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

    @Test fun enlargedTutorialAcceptsTheEntireBoxIncludingPreviouslyExcludedEdges() {
        val old = TutorialButtonBounds(111, 703, 178, 44)
        val enlarged = TutorialButtonBounds(93, 698, 214, 54)
        val newCorner = ScreenGaze(94.0 / 400, 699.0 / 1_000, 1_000)
        assertFalse(old.contains(newCorner, 400, 1_000, rounded = false))
        assertTrue(enlarged.contains(newCorner, 400, 1_000, rounded = false))
        assertFalse(enlarged.contains(newCorner, 400, 1_000))
        assertFalse(enlarged.contains(ScreenGaze(92.0 / 400, .725, 1_000), 400, 1_000, rounded = false))
        assertFalse(enlarged.contains(ScreenGaze(.5, .752, 1_000), 400, 1_000, rounded = false))
    }
}
