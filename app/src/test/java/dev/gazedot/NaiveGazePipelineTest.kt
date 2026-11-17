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
        val pipeline = GazePipeline(250,250)
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
        val sample = GazePipeline(250,250).process(1000, 1000, observation(f), null, true)
        assertNull(sample.point)
        assertTrue(sample.actions.isEmpty())
        assertEquals(f, sample.calibrationFeatures(1000, 0))
    }
    @Test fun oldPoseRejectionAndScreenClampingArePreserved() {
        val model = model()
        val pipeline = GazePipeline(250,250)
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
        val pipeline = GazePipeline(250,250)
        for (at in 1000L..1726L step 66) pipeline.process(at, at, observation(eye(0.0, 0.0)), model, true)
        val stale = pipeline.process(1792, 2100, observation(eye(0.0, 0.0)), model, true)
        assertEquals(TrackingQuality.STALE, stale.quality)
        assertNull(stale.point)
        assertFalse(stale.actions.any { it is EyeAction.Click })
        val next = pipeline.process(2166, 2166, observation(eye(0.0, 0.0)), model, true)
        assertEquals(0f, next.dwellProgress)
    }
    @Test fun extendedTrackingDisplaysDelayedResultsWithoutClicking() {
        val p=GazePipeline(750,250); val m=model()
        for(t in 1000L..3000L step 100) {
            val s=p.process(t,t+400,observation(eye(0.0,0.0)),m,true)
            assertEquals(TrackingQuality.TRACKING,s.quality)
            assertNotNull(s.point)
            assertFalse(s.actions.any{it is EyeAction.Click || it is EyeAction.Scroll || it is EyeAction.Dictate})
            assertEquals(0f,s.dwellProgress)
        }
        val expired=p.process(3100,3851,observation(eye(0.0,0.0)),m,true)
        assertEquals(TrackingQuality.STALE,expired.quality);assertNull(expired.point)
    }
    @Test fun extendedTrackingStillRejectsDuplicatesFutureAndFaceLoss() {
        val p=GazePipeline(750,250);val m=model()
        p.process(1000,1400,observation(eye(0.0,0.0)),m,true)
        assertEquals(TrackingQuality.STALE,p.process(1000,1450,observation(eye(0.0,0.0)),m,true).quality)
        assertEquals(TrackingQuality.STALE,p.process(2000,1900,observation(eye(0.0,0.0)),m,true).quality)
        assertNull(p.process(2100,2300,observation(null),m,true).point)
    }
    @Test fun runtime500msBudgetCanClickAndStopsAt501ms() {
        val p=GazePipeline();val m=model();val actions=mutableListOf<EyeAction>()
        for(t in 1000L..2400L step 100) actions+=p.process(t,t+400,observation(eye(0.0,0.0)),m,true).actions
        assertTrue(actions.any{it is EyeAction.Click})
        val stale=p.process(2500,3001,observation(eye(0.0,0.0)),m,true)
        assertEquals(TrackingQuality.STALE,stale.quality)
        assertFalse(stale.actions.any{it is EyeAction.Click || it is EyeAction.Scroll})
    }
}
