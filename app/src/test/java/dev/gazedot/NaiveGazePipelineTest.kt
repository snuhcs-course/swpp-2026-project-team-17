package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class NaiveGazePipelineTest {
    private fun eye(x: Double, y: Double) = EyeFeatures(x, y, .5, .5, .3)
    private fun model(): GazeModel = GazeModel.fit(listOf(-.05, 0.0, .05).flatMap { x ->
        listOf(-.04, 0.0, .04).map { y -> Anchor(eye(x, y), .5 + x * 6, .5 + y * 7) }
    })!!
    private fun observation(f: EyeFeatures?) = EyeObservation(if (f == null) EyeState.LOST else EyeState.OPEN, f, .2, .2)

    @Test fun isolatedSpikeIsHeldAndCannotTriggerAnAction() {
        val m=model();val p=GazePipeline()
        val initial=p.process(1000,1000,observation(eye(0.0,0.0)),m,true)
        val spike=p.process(1100,1100,observation(eye(.05,.04)),m,true)
        assertEquals(initial.point,spike.point)
        assertEquals(TrackingQuality.UNCERTAIN,spike.quality)
        assertFalse(spike.actions.any { it is EyeAction.Click || it is EyeAction.Scroll })
        val recovered=p.process(1200,1200,observation(eye(0.0,0.0)),m,true)
        assertEquals(initial.point,recovered.point)
        assertEquals(TrackingQuality.TRACKING,recovered.quality)
    }
    @Test fun calibrationIsRequiredButFreshFeaturesRemainAvailable() {
        val f = eye(0.0, 0.0)
        val sample = GazePipeline(250,250).process(1000, 1000, observation(f), null, true)
        assertNull(sample.point)
        assertTrue(sample.actions.isEmpty())
        assertEquals(f, sample.calibrationFeatures(1000, 0))
    }
    @Test fun faceTranslationKeepsValidMappingAndScreenClampingIsPreserved() {
        val model = model()
        val pipeline = GazePipeline(250,250)
        val outside = pipeline.process(1000, 1000, observation(eye(.2, -.2)), model, false)
        assertEquals(1.0, outside.point!!.first, 0.0)
        assertEquals(0.0, outside.point.second, 0.0)
        pipeline.reset()
        val center = pipeline.process(1033, 1033, observation(eye(0.0, 0.0)), model, true)
        val moved = eye(0.0, 0.0).copy(faceX = .7)
        val sample = pipeline.process(1066, 1066, observation(moved), model, true)
        assertEquals(TrackingQuality.TRACKING, sample.quality)
        assertEquals(center.point, sample.point)
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
    @Test fun winkModeSurvivesCoordinateFailureAndRecoversWithoutAnotherWink() {
        val p=GazePipeline();val m=model()
        fun frame(t:Long,state:EyeState,f:EyeFeatures?=eye(0.0,0.0))=
            p.process(t,t,EyeObservation(state,f,.2,.2),m,true)
        frame(1000,EyeState.OPEN)
        frame(1300,EyeState.LEFT_WINK,null)
        frame(1600,EyeState.LEFT_WINK,null)
        val enabled=frame(1900,EyeState.OPEN)
        assertTrue(enabled.scrollMode)
        val missing=frame(2100,EyeState.OPEN,eye(0.0,0.0).copy(scale=0.0))
        assertEquals(TrackingQuality.OUT_OF_RANGE,missing.quality)
        assertTrue(missing.scrollMode)
        assertFalse(missing.actions.any{it is EyeAction.Scroll || it is EyeAction.Click})
        assertTrue(frame(2300,EyeState.OPEN).scrollMode)
        assertFalse(frame(2400,EyeState.LOST,null).scrollMode)
    }
}
