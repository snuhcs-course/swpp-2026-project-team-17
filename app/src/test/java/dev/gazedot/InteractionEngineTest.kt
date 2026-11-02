package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class InteractionEngineTest {
    private class Feed {
        val engine = InteractionEngine()
        val actions = mutableListOf<EyeAction>()
        var now = 1000L
        fun frame(eye: EyeState, point: Pair<Double, Double>? = .4 to .6, step: Long = 50, key: String? = null) {
            now += step; actions += engine.update(now, eye, point, targetKey = key)
        }
        fun open(frames: Int = 5, point: Pair<Double, Double> = .4 to .6, key: String? = null) {
            repeat(frames) { frame(EyeState.OPEN, point, key = key) }
        }
        fun closed(frames: Int = 22) { repeat(frames) { frame(EyeState.CLOSED, null) } }
        val clicks get() = actions.filterIsInstance<EyeAction.Click>()
    }
    @Test fun continuousOneSecondFixationClicksWithoutClosingEyes() {
        val f = Feed(); f.open(20)
        assertTrue(f.clicks.isEmpty())
        assertTrue(f.engine.progress > .9f)
        f.open(1)
        assertEquals(listOf(EyeAction.Click(.4 to .6)), f.clicks)
    }
    @Test fun continuingToLookDoesNotRepeatUntilGazeLeaves() {
        val f = Feed(); f.open(80)
        assertEquals(1, f.clicks.size)
        f.open(1, .8 to .2); f.open(21)
        assertEquals(2, f.clicks.size)
    }
    @Test fun blinkAndUncertainOrMissingEyesCannotReleaseSelectedTarget() {
        for (eye in listOf(EyeState.CLOSED, EyeState.UNCERTAIN, EyeState.LOST)) {
            val f = Feed(); f.open(21)
            f.frame(eye, null)
            f.open(40)
            assertEquals(1, f.clicks.size)
        }
    }
    @Test fun targetIdentityAllowsMovementWithinTheSameButton() {
        val f = Feed()
        repeat(21) { f.frame(EyeState.OPEN, if (it % 2 == 0) .3 to .4 else .6 to .7, key = "button") }
        assertEquals(1, f.clicks.size)
        f.open(40, .7 to .8, "button")
        assertEquals(1, f.clicks.size)
    }
    @Test fun differentButtonAtTheSamePositionRequiresANewFullSecond() {
        val f = Feed(); f.open(15, key = "a"); f.open(20, key = "b")
        assertTrue(f.clicks.isEmpty())
        f.open(1, key = "b")
        assertEquals(1, f.clicks.size)
    }
    @Test fun movingFixationRestartsTimingEvenIfEndpointsMatch() {
        val f = Feed(); f.open(15); f.open(1, .8 to .2); f.open(20)
        assertTrue(f.clicks.isEmpty())
        f.open(1)
        assertEquals(1, f.clicks.size)
    }
    @Test fun longClosuresNeverClick() {
        val f = Feed(); f.open(); f.closed(50); f.open(); f.closed(50)
        assertTrue(f.clicks.isEmpty())
    }
    @Test fun ordinaryBlinkDoesNothingButFastDoubleBlinkDictatesOnce() {
        val f = Feed(); f.open(); f.closed(2); f.open(3)
        assertTrue(f.actions.isEmpty())
        f.closed(2); f.open(8)
        assertEquals(listOf(EyeAction.Dictate), f.actions)
    }
    @Test fun slowSeparatedBlinksDoNotDictate() {
        val f = Feed(); f.open(); f.closed(2); f.open(12); f.closed(2); f.open()
        assertTrue(f.actions.isEmpty())
    }
    @Test fun invalidEyesAndFrameGapsRestartPendingDwell() {
        for (eye in listOf(EyeState.LOST, EyeState.CLOSED, EyeState.UNCERTAIN)) {
            val f = Feed(); f.open(15); f.frame(eye, null); f.open(20)
            assertTrue(f.clicks.isEmpty())
            f.open(1)
            assertEquals(1, f.clicks.size)
        }
        val f = Feed(); f.open(15); f.frame(EyeState.OPEN, step = 500); f.open(20)
        assertTrue(f.clicks.isEmpty())
        f.open(1)
        assertEquals(1, f.clicks.size)
    }
    @Test fun delayedResultsUseReceiptTimeAndStillCompleteDwell() {
        val engine = InteractionEngine()
        val actions = (0L..1000L step 200).flatMap {
            engine.update(it, EyeState.OPEN, .4 to .6, receivedAt = it + 180)
        }
        assertEquals(listOf(EyeAction.Click(.4 to .6)), actions)
    }
    @Test fun lookingAtTopBottomOrOffscreenWithoutWinkingNeverScrolls() {
        for (point in listOf(.5 to .1, .5 to .9, .5 to 1.3, .5 to -.3)) {
            val f = Feed(); f.open(40, point)
            assertTrue(f.actions.none { it is EyeAction.Scroll })
        }
    }
    @Test fun winkModeScrollsButSuppressesClicksAndSecondWinkExits() {
        val f = Feed(); f.open()
        repeat(3) { f.frame(EyeState.LEFT_WINK, null) }; f.open(1)
        assertEquals(EyeAction.ScrollMode(true), f.actions.last())
        f.open(12, .5 to .9)
        assertTrue(f.actions.contains(EyeAction.Scroll(ScrollDirection.DOWN)))
        f.open(30, .5 to .9)
        assertTrue(f.clicks.isEmpty())
        repeat(3) { f.frame(EyeState.RIGHT_WINK, null) }; f.open(1)
        assertEquals(EyeAction.ScrollMode(false), f.actions.last())
        val scrollCount = f.actions.filterIsInstance<EyeAction.Scroll>().size
        f.open(25, .5 to .1)
        assertEquals(scrollCount, f.actions.filterIsInstance<EyeAction.Scroll>().size)
        assertEquals(1, f.clicks.size)
    }

    @Test fun screenContentUpdatesCannotCancelAWinkToExitScrollMode() {
        val f = Feed(); f.open()
        repeat(3) { f.frame(EyeState.LEFT_WINK, null) }; f.open(1)
        assertTrue(f.engine.scrollMode)
        repeat(3) {
            f.frame(EyeState.RIGHT_WINK, null)
            f.engine.cancelClick()
        }
        f.open(1)
        assertFalse(f.engine.scrollMode)
        assertEquals(EyeAction.ScrollMode(false), f.actions.last())
    }

    @Test fun horizontalScrollingUsesModeAndNeverClicks() {
        val f = Feed(); f.open()
        repeat(3) { f.frame(EyeState.LEFT_WINK, null) }; f.open(1)
        f.open(12, .1 to .5)
        assertTrue(f.actions.contains(EyeAction.Scroll(ScrollDirection.LEFT)))
        f.open(25, .9 to .5)
        assertTrue(f.actions.contains(EyeAction.Scroll(ScrollDirection.RIGHT)))
        assertTrue(f.clicks.isEmpty())
    }
    @Test fun doubleBlinkInterruptsDwellAndDoesNotClick() {
        val f = Feed(); f.open(15); f.closed(2); f.open(3); f.closed(2); f.open(8)
        assertTrue(f.actions.contains(EyeAction.Dictate))
        assertTrue(f.clicks.isEmpty())
    }
    @Test fun cancellingPendingActionsDoesNotReleasePostClickLatch() {
        val f = Feed(); f.open(21)
        f.engine.reset(preserveSelection = true)
        f.open(40)
        assertEquals(1, f.clicks.size)
    }
    @Test fun startingWithClosedEyesCannotClick() {
        val f = Feed(); f.closed(50)
        assertTrue(f.actions.isEmpty())
    }
}
