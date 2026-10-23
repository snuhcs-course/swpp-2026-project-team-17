package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class GazePipelineTest {
    private val features = EyeFeatures(0.0, .02, .5, .5, .3)
    private val open = EyeObservation(EyeState.OPEN, features, .2, .2)
    private val closed = EyeObservation(EyeState.CLOSED, null, .05, .05)
    private val local = object : GazePredictor {
        override fun predict(f: EyeFeatures) = .4 to .6
    }
    private fun startDwell(p: GazePipeline): GazeSample {
        var result = p.process(1000, 1000, open, local, true)
        for (t in 1050L..1500L step 50) result = p.process(t, t, open, local, true)
        assertTrue(result.dwellProgress > 0f)
        return result
    }

    @Test fun candidateDoesNotReplaceLocalGaze() {
        val s = GazePipeline().process(1000, 1000, open, local, true, .9 to .1)
        assertEquals(.4 to .6, s.point)
        assertEquals(TrackingQuality.TRACKING, s.quality)
    }
    @Test fun staleFrameCancelsPendingDwell() {
        val p = GazePipeline(); startDwell(p)
        val s = p.process(2300, 2600, closed, local, true)
        assertEquals(TrackingQuality.STALE, s.quality)
        assertNull(s.point); assertEquals(0f, s.dwellProgress, 0f)
        assertEquals(listOf(EyeAction.Cancel), s.actions)
    }
    @Test fun lossAndRecoveryDoNotReusePreviousPoint() {
        val p = GazePipeline(); startDwell(p)
        val lost = p.process(1550, 1550, EyeObservation(EyeState.LOST, null, 0.0, 0.0), local, true)
        assertEquals(0f, lost.dwellProgress, 0f); assertTrue(lost.actions.contains(EyeAction.Cancel))
        val recovered = p.process(1600, 1600, open, local, true)
        assertEquals(.4 to .6, recovered.point); assertTrue(recovered.actions.isEmpty())
    }
    @Test fun disabledActionsStillProducePointerAndCancelClick() {
        val p = GazePipeline(); startDwell(p)
        val s = p.process(1550, 1550, open, local, false)
        assertNotNull(s.point); assertEquals(0f, s.dwellProgress, 0f)
        assertEquals(listOf(EyeAction.Cancel), s.actions)
    }
    @Test fun invalidModelOutputCannotReachPointerOrGestures() {
        val bad = object : GazePredictor { override fun predict(f: EyeFeatures) = Double.NaN to .4 }
        val s = GazePipeline().process(1000, 1000, open, bad, true)
        assertEquals(TrackingQuality.OUT_OF_RANGE, s.quality)
        assertNull(s.point); assertTrue(s.actions.isEmpty())
    }
    @Test fun changingModelCancelsPreviousClick() {
        val p = GazePipeline(); startDwell(p)
        val s = p.process(1550, 1550, open, BasicGazePredictor, true)
        assertEquals(0f, s.dwellProgress, 0f); assertTrue(s.actions.contains(EyeAction.Cancel))
    }
    @Test fun gapAndResetCannotCompleteOldDwell() {
        val p = GazePipeline(); startDwell(p)
        val s = p.process(3000, 3000, closed, local, true)
        assertEquals(0f, s.dwellProgress, 0f); assertFalse(s.actions.any { it is EyeAction.Click })
        p.reset()
        assertTrue(p.process(3050, 3050, closed, local, true).actions.isEmpty())
    }
    @Test fun winkModeFlowsThroughPipelineAndUsesScreenThirds() {
        val bottom = object : GazePredictor { override fun predict(f: EyeFeatures) = .5 to .9 }
        val p = GazePipeline()
        p.process(1_000, 1_000, open, bottom, true)
        for (t in 1_050L..1_150L step 50) {
            p.process(t, t, EyeObservation(EyeState.LEFT_WINK, null, .05, .2), bottom, true)
        }
        val toggled = p.process(1_200, 1_200, open, bottom, true)
        assertTrue(toggled.scrollMode)
        assertTrue(toggled.actions.contains(EyeAction.ScrollMode(true)))
        val actions = (1_250L..2_000L step 50).flatMap { p.process(it, it, open, bottom, true).actions }
        assertTrue(actions.contains(EyeAction.Scroll(ScrollDirection.DOWN)))
        assertFalse(actions.any { it is EyeAction.Click })
    }
    @Test fun offscreenCoordinatesArePreservedButNoLongerStartScrollMode() {
        val outside = object : GazePredictor { override fun predict(f: EyeFeatures) = .5 to 1.25 }
        val p = GazePipeline()
        val sample = p.process(1_000, 1_000, open, outside, true)
        assertEquals(.5 to 1.25, sample.point)
        assertFalse(sample.scrollMode)
        assertTrue(sample.actions.isEmpty())
    }
    @Test fun outOfOrderAndFutureFramesAreRejected() {
        val p = GazePipeline()
        p.process(1000, 1000, open, local, true)
        assertEquals(TrackingQuality.STALE, p.process(999, 1000, open, local, true).quality)
        assertEquals(TrackingQuality.STALE, p.process(999, 1000, open, local, true).quality)
        assertEquals(TrackingQuality.STALE, p.process(2000, 1000, open, local, true).quality)
    }
}
