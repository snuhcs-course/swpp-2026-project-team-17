package dev.gazedot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class HorizontalScrollTest {
    private val width = 1_080
    private val height = 2_400

    private class Feed {
        val engine = InteractionEngine()
        val actions = mutableListOf<EyeAction>()
        var now = 1_000L
        fun frame(eye: EyeState = EyeState.OPEN, point: Pair<Double, Double>? = .5 to .5) {
            now += 50; actions += engine.update(now, eye, point)
        }
        fun look(point: Pair<Double, Double>, frames: Int) = repeat(frames) { frame(point = point) }
        fun wink() { frame(); repeat(3) { frame(EyeState.LEFT_WINK, null) }; frame() }
        val scrolls get() = actions.filterIsInstance<EyeAction.Scroll>().map { it.direction }
        val clicks get() = actions.filterIsInstance<EyeAction.Click>()
    }

    private fun assertSwipe(from: Pair<Double, Double>, to: Pair<Double, Double>, durationMs: Long, actual: ScrollSwipe) {
        assertEquals(from.first, actual.from.first, 1e-6); assertEquals(from.second, actual.from.second, 1e-6)
        assertEquals(to.first, actual.to.first, 1e-6); assertEquals(to.second, actual.to.second, 1e-6)
        assertEquals(durationMs, actual.durationMs)
    }

    @Test fun lookingLeftOrRightDispatchesAHorizontalStrokeThatRevealsThatSide() {
        for ((gaze, expected) in listOf((.9 to .5) to ScrollDirection.RIGHT, (.1 to .5) to ScrollDirection.LEFT)) {
            val f = Feed(); f.wink()
            assertTrue(f.engine.scrollMode)
            f.look(gaze, 12)
            assertEquals(listOf(expected), f.scrolls)
            // TrackingService.perform() passes the direction to ControlAccessibilityService.scroll(),
            // which dispatches exactly this stroke and duration.
            val swipe = ScrollSettings.swipe(f.scrolls.single(), width, height)
            val fingerDx = swipe.to.first - swipe.from.first
            // Content on the right is revealed by moving the finger left, and vice versa.
            assertEquals(if (expected == ScrollDirection.RIGHT) -1.0 else 1.0, Math.signum(fingerDx), 0.0)
            assertEquals(swipe.from.second, swipe.to.second, 0.0)
            assertEquals(ScrollSettings.HORIZONTAL_SWIPE_FRACTION * width, abs(fingerDx), 1e-6)
            assertEquals(ScrollSettings.HORIZONTAL_SWIPE_DURATION_MS, swipe.durationMs)
            assertTrue(f.clicks.isEmpty())
        }
    }

    @Test fun verticalSwipesKeepThePreviousStrokeAndTiming() {
        // Literal pre-fix values: vertical swipes and the tutorial animation must not change.
        assertSwipe(540.0 to 1_020.0, 540.0 to 1_380.0, 650L, ScrollSettings.swipe(ScrollDirection.UP, width, height))
        assertSwipe(540.0 to 1_380.0, 540.0 to 1_020.0, 650L, ScrollSettings.swipe(ScrollDirection.DOWN, width, height))
        assertEquals(.15, ScrollSettings.DISTANCE_FRACTION, 0.0)
        assertEquals(650L, ScrollSettings.DURATION_MS)
    }

    @Test fun horizontalStrokesStayOnTheCenterRowAndAwayFromScreenEdges() {
        for ((w, h) in listOf(1_080 to 2_400, 720 to 1_280, 1_600 to 2_560, 2_400 to 1_080)) {
            val left = ScrollSettings.swipe(ScrollDirection.LEFT, w, h)
            val right = ScrollSettings.swipe(ScrollDirection.RIGHT, w, h)
            for (swipe in listOf(left, right)) {
                assertEquals(h / 2.0, swipe.from.second, 0.0)
                assertEquals(h / 2.0, swipe.to.second, 0.0)
                assertEquals(w / 2.0, (swipe.from.first + swipe.to.first) / 2, 1e-6)
                // Edge back-gesture zones are near the left/right edges.
                for (x in listOf(swipe.from.first, swipe.to.first)) assertTrue("$x of $w", x >= .2 * w && x <= .8 * w)
            }
            // Opposite directions use reversed endpoints.
            assertEquals(left.from, right.to); assertEquals(left.to, right.from)
        }
    }

    @Test fun horizontalStrokeExceedsCommonPageSwitchDistanceAndFlingSpeed() {
        // AOSP reference points, not universal limits: Launcher3 PagedView changes page after a drag of
        // more than 40 % of the page or a fling above 500 dp/s; AndroidX ViewPager flings above 400 dp/s.
        // A narrow 360 dp phone gives the lowest dp/s for a width-relative stroke.
        val density = 2.625
        val widthPx = (360 * density).toInt()
        for (direction in listOf(ScrollDirection.LEFT, ScrollDirection.RIGHT)) {
            val swipe = ScrollSettings.swipe(direction, widthPx, 2_000)
            val travel = abs(swipe.to.first - swipe.from.first)
            assertTrue(travel / widthPx > .4)
            assertTrue(travel / density / (swipe.durationMs / 1_000.0) > 500)
        }
    }

    @Test fun everyStrokeEndsBeforeTheNextRepeatCanBeDispatched() {
        // ControlAccessibilityService drops a scroll while the previous gesture is still running.
        for (direction in ScrollDirection.entries) {
            assertTrue(ScrollSettings.swipe(direction, width, height).durationMs in 1 until ScrollSettings.INTERVAL_MS)
        }
    }

    @Test fun neutralZoneStopsHorizontalScrollingUntilANewHold() {
        val f = Feed(); f.wink()
        f.look(.9 to .5, 50)
        val count = f.scrolls.size
        assertTrue(count >= 2)
        assertTrue(f.scrolls.all { it == ScrollDirection.RIGHT })
        f.look(.5 to .5, 30)
        assertEquals(count, f.scrolls.size)
        f.look(.1 to .5, 7)
        assertEquals(count, f.scrolls.size)
        f.look(.1 to .5, 1)
        assertEquals(ScrollDirection.LEFT, f.scrolls.last())
        assertTrue(f.engine.scrollMode)
        assertTrue(f.clicks.isEmpty())
    }

    @Test fun trackingLossDuringHorizontalScrollingTurnsModeOff() {
        val f = Feed(); f.wink(); f.look(.9 to .5, 12)
        assertEquals(1, f.scrolls.size)
        assertTrue(f.clicks.isEmpty())
        f.frame(EyeState.LOST, null)
        assertFalse(f.engine.scrollMode)
        f.look(.9 to .5, 40)
        assertEquals(1, f.scrolls.size)
    }
}
