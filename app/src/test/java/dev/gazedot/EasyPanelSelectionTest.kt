package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class EasyPanelSelectionTest {
    private val first = EasyPanelTarget("a", 20.0, 100.0, 380.0, 240.0, 14.0)
    private val second = EasyPanelTarget("b", 20.0, 256.0, 380.0, 396.0, 14.0)
    private val targets = listOf(first, second)
    private fun feed(engine: EasyPanelSelection, x: Double = 200.0, y: Double = 170.0,
        start: Long = 1_000L, duration: Long = 1_000L): List<List<String>> =
        (start..start + duration step 50).mapNotNull { engine.update(x, y, it, it, targets) }

    @Test fun freshOneSecondSelectsOneRealCandidate() {
        val engine = EasyPanelSelection()
        assertEquals(listOf(listOf("a")), feed(engine))
    }
    @Test fun boundaryReturnsTwoCandidatesInsteadOfChoosingOne() {
        assertEquals(listOf(listOf("a", "b")), feed(EasyPanelSelection(), y = 248.0))
    }
    @Test fun leavingTheCandidateOrSwitchingCandidateSetRestartsDwell() {
        val engine = EasyPanelSelection()
        assertTrue(feed(engine, duration = 750).isEmpty())
        assertNull(engine.update(200.0, 248.0, 1800, 1800, targets))
        assertTrue(feed(engine, y = 248.0, start = 1850, duration = 900).isEmpty())
        assertEquals(listOf("a", "b"), engine.update(200.0, 248.0, 2800, 2800, targets))
    }
    @Test fun invalidStaleAndNonFiniteSamplesResetProgress() {
        for ((x, y, sampleAt) in listOf(Triple(null, null, 1800L), Triple(Double.NaN, 170.0, 1800L),
            Triple(200.0, Double.POSITIVE_INFINITY, 1800L), Triple(200.0, 170.0, 1200L))) {
            val engine = EasyPanelSelection()
            feed(engine, duration = 750)
            assertNull(engine.update(x, y, sampleAt, 1800, targets))
            assertEquals(0f, engine.progress)
            assertTrue(feed(engine, start = 1850, duration = 950).isEmpty())
        }
    }
    @Test fun missingFramesExpireWithoutSelecting() {
        val engine = EasyPanelSelection()
        feed(engine, duration = 750)
        engine.expire(2001)
        assertEquals(0f, engine.progress)
        assertTrue(feed(engine, start = 2050, duration = 950).isEmpty())
    }
    @Test fun timerTicksBetweenCameraFramesDoNotEraseFirstCandidate() {
        val engine = EasyPanelSelection()
        assertNull(engine.update(200.0, 170.0, 1000, 1000, targets))
        engine.expire(1050)
        assertNull(engine.update(200.0, 170.0, 1100, 1100, targets))
        assertTrue(engine.progress > 0f)
    }
    @Test fun continuingGazeDoesNotRepeatEvenAfterTrackingLoss() {
        val engine = EasyPanelSelection()
        feed(engine)
        assertTrue(feed(engine, start = 2050, duration = 2000).isEmpty())
        engine.update(null, null, 4100, 4100, targets)
        engine.expire(5000)
        assertTrue(feed(engine, start = 5100).isEmpty())
        engine.update(200.0, 30.0, 6150, 6150, targets)
        assertEquals(listOf(listOf("a")), feed(engine, start = 6200))
    }
    @Test fun newLayoutDoesNotReuseThePreviousGazeToSelectAgain() {
        val engine = EasyPanelSelection()
        feed(engine)
        engine.reset(clearLatch = false)
        val replacement = listOf(first.copy(id = "new"))
        for (time in 2050L..4050L step 50) assertNull(engine.update(200.0, 170.0, time, time, replacement))
        engine.update(200.0, 30.0, 4100, 4100, replacement)
        var selected: List<String>? = null
        for (time in 4150L..5150L step 50) selected = engine.update(200.0, 170.0, time, time, replacement) ?: selected
        assertEquals(listOf("new"), selected)
    }
    @Test fun navigationDoesNotBecomeAnAmbiguousAction() {
        val engine = EasyPanelSelection()
        val nav = EasyPanelTarget("close", 20.0, 242.0, 380.0, 254.0)
        var selected: List<String>? = null
        for (time in 1000L..2000L step 50) selected = engine.update(200.0, 248.0, time, time, targets + nav) ?: selected
        assertEquals(listOf("close"), selected)
    }
    @Test fun duplicateCaptureTimesCannotCompleteASelection() {
        val engine = EasyPanelSelection()
        for (time in 1000L..2000L step 50) assertNull(engine.update(200.0, 170.0, 1000, time, targets))
    }
    @Test fun touchTransitionAlsoRequiresGazeToLeaveThePreviousRegion() {
        val engine = EasyPanelSelection()
        engine.holdUntilExit(first)
        assertTrue(feed(engine).isEmpty())
        assertTrue(engine.waitingForExit)
    }
    @Test fun unrelatedScreenAreaCannotSelectAnything() {
        assertTrue(feed(EasyPanelSelection(), x = 500.0, duration = 2000).isEmpty())
    }
}
