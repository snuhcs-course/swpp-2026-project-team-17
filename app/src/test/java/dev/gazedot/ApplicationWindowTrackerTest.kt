package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class ApplicationWindowTrackerTest {
    @Test fun sameWindowUpdatesKeepScrollingUntilAnotherWink() {
        val windows = ApplicationWindowTracker()
        val app = ApplicationWindowKey("message.app", 4)
        windows.changed(app)
        val engine = InteractionEngine()
        var now = 1000L
        val actions = mutableListOf<EyeAction>()
        fun frame(eye: EyeState = EyeState.OPEN) {
            now += 50
            actions += engine.update(now, eye, .5 to .9)
        }
        frame()
        repeat(3) { frame(EyeState.LEFT_WINK) }
        frame()
        assertTrue(engine.scrollMode)
        repeat(12) { frame() }
        assertTrue(actions.any { it is EyeAction.Scroll })
        val firstCount = actions.filterIsInstance<EyeAction.Scroll>().size
        repeat(30) {
            // The same routing used for WINDOW_STATE_CHANGED in the accessibility service.
            if (windows.changed(app)) engine.reset(preserveSelection = true) else engine.cancelClick()
            frame()
        }
        assertTrue(engine.scrollMode)
        assertTrue(actions.filterIsInstance<EyeAction.Scroll>().size > firstCount)
        repeat(3) {
            if (windows.changed(app)) engine.reset() else engine.cancelClick()
            frame(EyeState.RIGHT_WINK)
        }
        frame()
        assertFalse(engine.scrollMode)
        assertTrue(actions.none { it is EyeAction.Click })
    }

    @Test fun sameWindowUpdatesKeepHorizontalScrollingButAnotherAppResetsIt() {
        val windows = ApplicationWindowTracker()
        val launcher = ApplicationWindowKey("launcher.app", 7)
        windows.changed(launcher)
        val engine = InteractionEngine()
        var now = 1000L
        val actions = mutableListOf<EyeAction>()
        fun frame(eye: EyeState = EyeState.OPEN, point: Pair<Double, Double>? = .9 to .5) {
            now += 50
            actions += engine.update(now, eye, point)
        }
        frame()
        repeat(3) { frame(EyeState.LEFT_WINK, null) }
        frame()
        assertTrue(engine.scrollMode)
        repeat(60) { i ->
            // A page switch reports state/content/scroll events from the same launcher window.
            if (windows.changed(launcher)) engine.reset(preserveSelection = true) else engine.cancelClick()
            frame(point = if (i < 30) .9 to .5 else .1 to .5)
        }
        assertTrue(engine.scrollMode)
        val scrolls = actions.filterIsInstance<EyeAction.Scroll>().map { it.direction }
        assertTrue(ScrollDirection.RIGHT in scrolls && ScrollDirection.LEFT in scrolls)
        assertTrue(actions.none { it is EyeAction.Click })
        if (windows.changed(ApplicationWindowKey("other.app", 9))) engine.reset(preserveSelection = true)
        assertFalse(engine.scrollMode)
    }

    @Test fun appSwitchDialogAndUnknownWindowStillRequireReset() {
        val windows = ApplicationWindowTracker()
        val first = ApplicationWindowKey("first.app", 1)
        assertTrue(windows.changed(first))
        assertFalse(windows.changed(first))
        assertTrue(windows.changed(ApplicationWindowKey("first.app", 2)))
        assertTrue(windows.changed(ApplicationWindowKey("second.app", 2)))
        assertTrue(windows.changed(null))
        assertTrue(windows.changed(first))
    }
}
