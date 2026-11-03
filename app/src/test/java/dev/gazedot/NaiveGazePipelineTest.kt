package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class NaiveGazePipelineTest {
    private fun eye(x: Double, y: Double) = EyeFeatures(x, y, .5, .5, .3)
    private fun model(): GazeModel = GazeModel.fit(listOf(-.05, 0.0, .05).flatMap { x ->
        listOf(-.04, 0.0, .04).map { y -> Anchor(eye(x, y), .5 + x * 6, .5 + y * 7) }
    })!!
    private fun observation(f: EyeFeatures?) = EyeObservation(if (f == null) EyeState.LOST else EyeState.OPEN, f, .2, .2)

    @Test fun coordinatesMatchLegacyPredictorAndSmoothing() {
        val model = model()
        val pipeline = GazePipeline()
        var expected: Pair<Double, Double>? = null
        listOf(eye(0.0, 0.0), eye(.02, -.01), eye(-.03, .03), null, eye(.04, -.02)).forEachIndexed { i, f ->
            val raw = f?.let(model::predict)
            expected = if (raw == null) null else expected?.let {
                it.first + .28 * (raw.first - it.first) to it.second + .28 * (raw.second - it.second)
            } ?: raw
            val at = 1000L + i * 66
            val sample = pipeline.process(at, at, observation(f), model, false)
            assertEquals(raw, sample.rawPoint)
            assertEquals(expected, sample.point)
            assertTrue(sample.actions.isEmpty())
        }
    }
    @Test fun calibrationIsRequiredButFreshFeaturesRemainAvailable() {
        val f = eye(0.0, 0.0)
        val sample = GazePipeline().process(1000, 1000, observation(f), null, true)
        assertNull(sample.point)
        assertTrue(sample.actions.isEmpty())
        assertEquals(f, sample.calibrationFeatures(1000, 0))
    }
    @Test fun oldPoseRejectionAndScreenClampingArePreserved() {
        val model = model()
        val pipeline = GazePipeline()
        val outside = pipeline.process(1000, 1000, observation(eye(.2, -.2)), model, false)
        assertEquals(1.0, outside.point!!.first, 0.0)
        assertEquals(0.0, outside.point.second, 0.0)
        val moved = eye(0.0, 0.0).copy(faceX = .7)
        val sample = pipeline.process(1066, 1066, observation(moved), model, true)
        assertNull(sample.point)
        assertTrue(sample.actions.isEmpty())
        assertEquals(moved, sample.calibrationFeatures(1066, 1000))
    }
    @Test fun delayedFramesCannotClickOrLeakCoordinates() {
        val model = model()
        val pipeline = GazePipeline()
        for (at in 1000L..1726L step 66) pipeline.process(at, at, observation(eye(0.0, 0.0)), model, true)
        val stale = pipeline.process(1792, 2100, observation(eye(0.0, 0.0)), model, true)
        assertEquals(TrackingQuality.STALE, stale.quality)
        assertNull(stale.point)
        assertFalse(stale.actions.any { it is EyeAction.Click })
        val next = pipeline.process(2166, 2166, observation(eye(0.0, 0.0)), model, true)
        assertEquals(0f, next.dwellProgress)
    }

    @Test fun assistanceUsesTheActualDwellTargetWhenSmallJitterCrossesAButtonBoundary() {
        val model = model()
        val pipeline = GazePipeline()
        val assist = GazeAssistDetector()
        var proposed: AssistFocus? = null
        for (at in 0L..2_500L step 100) {
            val f = eye(if ((at / 100) % 2 == 0L) -.01 else .01, 0.0)
            val sample = pipeline.process(at, at, observation(f), model, true,
                targetKey = { if (it.first < .5) "left-button" else "right-button" })
            assertFalse(sample.actions.any { it is EyeAction.Click })
            val point = sample.point!!
            val area = assist.update(AssistPoint(point.first * 1_000, point.second * 2_000),
                sample.timestampMs, at, 1_000.0, sample.dwellProgress, false, sample.dwellTargetKey)
            if (area != null) proposed = area
        }
        assertNotNull(proposed)
    }

    @Test fun panelOwnershipPreventsBackgroundClickAndResumesWithAFreshDwell() {
        val model = model()
        val pipeline = GazePipeline()
        for (at in 0L..800L step 100) pipeline.process(at, at, observation(eye(0.0, 0.0)), model, true)
        for (at in 900L..2_000L step 100) {
            val sample = pipeline.process(at, at, observation(eye(0.0, 0.0)), model, false)
            assertFalse(sample.actions.any { it is EyeAction.Click })
            assertEquals(0f, sample.dwellProgress, 0f)
        }
        for (at in 2_100L..3_000L step 100) {
            assertFalse(pipeline.process(at, at, observation(eye(0.0, 0.0)), model, true)
                .actions.any { it is EyeAction.Click })
        }
        assertTrue(pipeline.process(3_100, 3_100, observation(eye(0.0, 0.0)), model, true)
            .actions.any { it is EyeAction.Click })
    }
}
