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
