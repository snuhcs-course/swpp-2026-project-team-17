package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class ScreenGazeIntegrationTest {
    private val eye = EyeFeatures(0.0, .02, .5, .5, .3)
    private val open = EyeObservation(EyeState.OPEN, eye, .2, .2)
    private val predictor = object : GazePredictor {
        override fun predict(f: EyeFeatures) = .5 to .725
    }

    @Test fun cameraPipelineDeliversTheSameFinalPointAndTimestampToTutorial() {
        val pipeline = GazePipeline()
        val dwell = TutorialDwell()
        val bounds = TutorialButtonBounds(100, 700, 200, 50)
        for (at in 1_000L..2_000L step 100) {
            val sample = pipeline.process(at, at + 20, open, predictor, false)
            val gaze = sample.toScreenGaze()!!
            assertEquals(sample.point, gaze.x to gaze.y)
            assertEquals(at, gaze.uptimeMillis)
            assertEquals(sample.quality, gaze.quality)
            assertTrue(sample.actions.isEmpty())
            assertEquals(at == 2_000L, dwell.update(
                gaze.fresh(at + 20) && bounds.contains(gaze, 400, 1_000), gaze.uptimeMillis, at + 20))
        }
    }

    @Test fun bridgeDoesNotPredictAgainOrUseRawOrCloudCandidateCoordinates() {
        val pipeline = GazePipeline()
        var output = .2 to .3
        val changing = object : GazePredictor { override fun predict(f: EyeFeatures) = output }
        pipeline.process(1_000, 1_000, open, changing, false, .9 to .9)
        output = .8 to .7
        val sample = pipeline.process(1_050, 1_050, open, changing, false, .9 to .9)
        assertNotEquals(sample.rawPoint, sample.point)
        val gaze = sample.copy(dwellProgress = .8f).toScreenGaze()!!
        assertEquals(sample.point, gaze.x to gaze.y)
        assertNotEquals(.9 to .9, gaze.x to gaze.y)
    }

    @Test fun offscreenPointSurvivesDeliveryButCannotSelectTutorial() {
        val outside = object : GazePredictor { override fun predict(f: EyeFeatures) = .5 to 1.25 }
        val sample = GazePipeline().process(1_000, 1_000, open, outside, false)
        val gaze = sample.toScreenGaze()!!
        assertEquals(1.25, gaze.y, 0.0)
        assertFalse(TutorialButtonBounds(0, 0, 400, 1_000).contains(gaze, 400, 1_000))
    }

    @Test fun bridgePreservesInvalidQualityAndMissingPoints() {
        val sample = GazePipeline().process(1_000, 1_000, open, predictor, false)
        for (quality in TrackingQuality.entries.filter { it != TrackingQuality.TRACKING }) {
            val gaze = sample.copy(quality = quality).toScreenGaze()!!
            assertEquals(quality, gaze.quality)
            assertFalse(gaze.valid)
            assertFalse(gaze.fresh(1_000))
            assertFalse(TutorialButtonBounds(100, 700, 200, 50).contains(gaze, 400, 1_000))
        }
        assertNull(sample.copy(point = null).toScreenGaze())
        assertFalse(ScreenGaze(Double.NaN, .5, 1_000).valid)
        assertFalse(ScreenGaze(.5, Double.POSITIVE_INFINITY, 1_000).valid)
    }

    @Test fun queuedCameraFramesAreNotMadeFreshByDeliveryTime() {
        val sample = GazePipeline().process(1_000, 1_000, open, predictor, false)
        val gaze = sample.toScreenGaze()!!
        assertTrue(gaze.fresh(1_250))
        assertFalse(gaze.fresh(1_251))
        assertFalse(gaze.fresh(999))
        assertEquals(1_000L, gaze.uptimeMillis)
    }

    @Test fun normalizedTargetsAndHitTestsUseTheSameDisplayAndInsetOffset() {
        val display = DisplayCoordinates(400, 1_000)
        val target = display.normalize(200.0, 725.0)
        val gaze = ScreenGaze(target.first, target.second, 1_000)
        assertEquals(100.0 to 25.0, display.inView(gaze, 100, 700))
        assertTrue(TutorialButtonBounds(100, 700, 200, 50).contains(gaze, 400, 1_000))
        assertFalse(TutorialButtonBounds(100, 750, 200, 50).contains(gaze, 400, 1_000))
    }

    @Test fun invalidCameraObservationResetsDwellAndRequiresAnotherFullSecond() {
        for (state in listOf(EyeState.CLOSED, EyeState.UNCERTAIN, EyeState.LOST)) {
            val pipeline = GazePipeline()
            val dwell = TutorialDwell()
            for (at in 1_000L..1_800L step 100) {
                val gaze = pipeline.process(at, at, open, predictor, false).toScreenGaze()!!
                assertFalse(dwell.update(gaze.fresh(at), gaze.uptimeMillis, at))
            }
            val invalid = pipeline.process(1_850, 1_850, EyeObservation(state, null, .05, .05), predictor, false)
            assertNull(invalid.toScreenGaze())
            assertFalse(dwell.update(false, invalid.timestampMs, 1_850))
            assertEquals(0f, dwell.progress, 0f)
            for (at in 1_900L..2_800L step 100) assertFalse(dwell.update(true, at, at))
            assertTrue(dwell.update(true, 2_900, 2_900))
        }
    }

    @Test fun calibrationUsesFreshUnderlyingFeaturesWhenTheSelectedModelRejectsThePose() {
        val rejects = object : GazePredictor { override fun predict(f: EyeFeatures): Pair<Double, Double>? = null }
        val sample = GazePipeline().process(1_000, 1_000, open, rejects, false)
        assertEquals(TrackingQuality.OUT_OF_RANGE, sample.quality)
        assertSame(eye, sample.calibrationFeatures(1_100, 900))
        assertNull(sample.point)
        assertNull(sample.calibrationFeatures(1_251, 900))
        assertNull(sample.calibrationFeatures(1_000, 1_000))
        assertNull(sample.calibrationFeatures(999, 900))
        for (quality in listOf(TrackingQuality.CLOSED, TrackingQuality.UNCERTAIN, TrackingQuality.LOST, TrackingQuality.STALE)) {
            assertNull(sample.copy(quality = quality).calibrationFeatures(1_000, 900))
        }
    }
}
