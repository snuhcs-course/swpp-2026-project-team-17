package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class EyeGesturesTest {
    private class Feed {
        val controller = ScrollController()
        var now = 1_000L
        val events = mutableListOf<ScrollStep>()
        fun frame(eye: EyeState = EyeState.OPEN, point: Pair<Double, Double>? = .5 to .5, step: Long = 50): ScrollStep {
            now += step
            return controller.update(now, now, eye, point).also { events += it }
        }
        fun wink(side: EyeState) {
            frame()
            repeat(3) { frame(side, null) }
            frame()
        }
    }

    @Test fun eitherWinkTogglesOnceOnlyAfterReopening() {
        for (eye in listOf(EyeState.LEFT_WINK, EyeState.RIGHT_WINK)) {
            val f = Feed(); f.frame()
            repeat(10) { f.frame(eye, null) }
            assertFalse(f.controller.enabled)
            assertTrue(f.frame().modeChanged == true)
            repeat(10) { f.frame() }
            assertEquals(1, f.events.count { it.modeChanged == true })
            f.wink(eye)
            assertFalse(f.controller.enabled)
            assertTrue(f.events.last().wink)
        }
    }

    @Test fun briefOrLongWinksAndClosedStartupCannotToggle() {
        val f = Feed(); f.frame(); f.frame(EyeState.LEFT_WINK, null); f.frame()
        assertFalse(f.controller.enabled)
        repeat(20) { f.frame(EyeState.RIGHT_WINK, null) }; f.frame()
        assertFalse(f.controller.enabled)
        val startup = Feed(); repeat(3) { startup.frame(EyeState.LEFT_WINK, null) }; startup.frame()
        assertFalse(startup.controller.enabled)
    }

    @Test fun doubleBlinkIsDistinctFromWinkAndCanRequestRecalibration() {
        val detector = EyeGestureDetector()
        val sequence = listOf(0L to EyeState.OPEN, 50L to EyeState.CLOSED, 100L to EyeState.CLOSED,
            150L to EyeState.OPEN, 250L to EyeState.CLOSED, 300L to EyeState.CLOSED, 350L to EyeState.OPEN)
        val events = sequence.mapNotNull { detector.update(it.first, it.second) }
        assertEquals(listOf(EyeGesture.DOUBLE_BLINK), events)
    }

    @Test fun shortThresholdTransitionsDoNotEraseBilateralDoubleBlink() {
        val detector = EyeGestureDetector()
        val sequence = listOf(0L to EyeState.OPEN, 50L to EyeState.UNCERTAIN, 100L to EyeState.CLOSED,
            150L to EyeState.CLOSED, 200L to EyeState.UNCERTAIN, 250L to EyeState.OPEN,
            350L to EyeState.UNCERTAIN, 400L to EyeState.CLOSED, 450L to EyeState.CLOSED,
            500L to EyeState.UNCERTAIN, 550L to EyeState.OPEN)
        assertEquals(listOf(EyeGesture.DOUBLE_BLINK), sequence.mapNotNull { detector.update(it.first, it.second) })
    }

    @Test fun staggeredBilateralBlinkDoesNotBecomeWink() {
        val f = Feed(); f.frame(); f.frame(EyeState.LEFT_WINK, null)
        f.frame(EyeState.CLOSED, null); f.frame(EyeState.CLOSED, null); f.frame()
        assertFalse(f.controller.enabled)
    }

    @Test fun lostOrAmbiguousFramesCannotCompleteGesture() {
        for (eye in listOf(EyeState.LOST, EyeState.UNCERTAIN)) {
            val f = Feed(); f.frame(); repeat(3) { f.frame(EyeState.RIGHT_WINK, null) }
            repeat(5) { f.frame(eye, null) }; f.frame()
            assertFalse(f.controller.enabled)
        }
    }

    @Test fun thirdsDetermineDirectionAndCenterPauses() {
        val f = Feed(); f.wink(EyeState.LEFT_WINK)
        repeat(10) { f.frame(point = .5 to .1) }
        assertTrue(f.events.any { it.direction == ScrollDirection.UP })
        val count = f.events.count { it.direction != null }
        repeat(30) { f.frame(point = .5 to .5) }
        assertEquals(count, f.events.count { it.direction != null })
        repeat(10) { f.frame(point = .5 to .9) }
        assertTrue(f.events.any { it.direction == ScrollDirection.DOWN })
    }

    @Test fun repeatedScrollRespectsConfiguredInterval() {
        val f = Feed(); f.wink(EyeState.RIGHT_WINK)
        val times = mutableListOf<Long>()
        repeat(100) { if (f.frame(point = .5 to .9).direction != null) times += f.now }
        assertTrue(times.size >= 3)
        assertTrue(times.zipWithNext().all { it.second - it.first >= ScrollSettings.INTERVAL_MS })
        assertTrue(ScrollSettings.DURATION_MS < ScrollSettings.INTERVAL_MS)
    }

    @Test fun leftAndRightThirdsUseTheSameHoldAndModeRules() {
        for ((x, expected) in listOf(.1 to ScrollDirection.LEFT, .9 to ScrollDirection.RIGHT)) {
            val f = Feed()
            repeat(12) { assertNull(f.frame(point = x to .5).direction) }
            f.wink(EyeState.LEFT_WINK)
            repeat(7) { assertNull(f.frame(point = x to .5).direction) }
            assertEquals(expected, f.frame(point = x to .5).direction)
            f.wink(EyeState.RIGHT_WINK)
            repeat(30) { assertNull(f.frame(point = x to .5).direction) }
        }
    }

    @Test fun cornersChooseOnlyTheDominantAxisAndMiddleIsNeutral() {
        assertEquals(ScrollDirection.LEFT, ScrollSettings.direction(.05 to .2))
        assertEquals(ScrollDirection.RIGHT, ScrollSettings.direction(.95 to .8))
        assertEquals(ScrollDirection.UP, ScrollSettings.direction(.2 to .05))
        assertEquals(ScrollDirection.DOWN, ScrollSettings.direction(.8 to .95))
        assertEquals(ScrollDirection.UP, ScrollSettings.direction(.1 to .1))
        assertNull(ScrollSettings.direction(.5 to .5))
        assertNull(ScrollSettings.direction(-.1 to .5))
        assertNull(ScrollSettings.direction(.5 to Double.NaN))
    }

    @Test fun horizontalAndVerticalSwipesUseTheCorrectAxisAndFingerDirection() {
        val width = 1_000
        val height = 2_000
        for (direction in ScrollDirection.entries) {
            val (from, to) = ScrollSettings.swipe(direction, width, height)
            assertEquals(-direction.dx * width * ScrollSettings.DISTANCE_FRACTION, to.first - from.first, .001)
            assertEquals(-direction.dy * height * ScrollSettings.DISTANCE_FRACTION, to.second - from.second, .001)
        }
    }

    @Test fun horizontalPracticeRecordsActualMovementWithoutSkippingVerticalPractice() {
        val practice = ScrollPractice()
        practice.modeChanged(true)
        practice.moved(-20, 0); practice.moved(20, 0)
        assertTrue(practice.left); assertTrue(practice.right)
        assertFalse(practice.up); assertFalse(practice.down)
        practice.modeChanged(false)
        assertFalse(practice.complete(false))
    }

    @Test fun detectionLossOrStaleInputTurnsOffMode() {
        val f = Feed(); f.wink(EyeState.LEFT_WINK); f.frame(EyeState.LOST, null)
        assertFalse(f.controller.enabled)
        f.wink(EyeState.RIGHT_WINK)
        f.controller.update(f.now + 50, f.now + 400, EyeState.OPEN, .5 to .9)
        assertFalse(f.controller.enabled)
    }

    @Test fun tutorialRequiresRealMovementBothWaysAndExplicitWinkOff() {
        val practice = ScrollPractice()
        practice.modeChanged(true)
        practice.moved(0); practice.modeChanged(false)
        assertFalse(practice.complete(false))
        practice.modeChanged(true); practice.moved(20)
        assertFalse(practice.complete(true))
        practice.moved(-20)
        assertFalse(practice.complete(true))
        // Losing tracking is not a successful wink-off gesture.
        assertFalse(practice.complete(false))
        practice.modeChanged(false)
        assertTrue(practice.complete(false))
    }

    @Test fun bilateralAndUnilateralEyeStatesAreClassifiedSeparately() {
        assertEquals(EyeState.OPEN, LandmarkFeatures.eyeState(.2, .2))
        assertEquals(EyeState.CLOSED, LandmarkFeatures.eyeState(.05, .05))
        assertEquals(EyeState.LEFT_WINK, LandmarkFeatures.eyeState(.05, .2))
        assertEquals(EyeState.RIGHT_WINK, LandmarkFeatures.eyeState(.2, .05))
        assertEquals(EyeState.UNCERTAIN, LandmarkFeatures.eyeState(.13, .2))
        assertEquals(EyeState.LOST, LandmarkFeatures.eyeState(Double.NaN, .2))
    }
}
