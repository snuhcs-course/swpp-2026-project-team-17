package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class RetargetingIntegrationTest {
    private fun eye(x: Double, y: Double) = EyeFeatures(
        (x-.5)*.3, (y-.5)*.2, .5, .5, .3)

    @Test fun retargetingCorrectionReachesRuntimeAndNewCalibrationDoesNotAccumulateIt() {
        val calibration = ScreenCalibration.fit(ScreenCalibration(0).targets.map { (x,y) ->
            CalibrationFixation(List(24) { eye(x,y) }, x,y)
        }).model!!
        val f = eye(.5,.5)
        val before = calibration.predict(f)!!
        val corrected = calibration.withScreenCorrection(-.04,.03)!!
        val pipeline = GazePipeline()
        pipeline.process(1000,1000,EyeObservation(EyeState.OPEN,f,.22,.22),calibration,false)
        val after = pipeline.process(1100,1100,EyeObservation(EyeState.OPEN,f,.22,.22),corrected,false)
        assertEquals(TrackingQuality.TRACKING, after.quality)
        assertEquals(before.first-.04,after.point!!.first,1e-9)
        assertEquals(before.second+.03,after.point!!.second,1e-9)
        assertEquals(before,calibration.predict(f))
        assertNull(calibration.withScreenCorrection(Double.NaN,.0))
        val fresh = ScreenCalibration.fit(ScreenCalibration(0).targets.map { (x,y) ->
            CalibrationFixation(List(24) { eye(x,y) },x,y)
        }).model!!
        assertEquals(before,fresh.predict(f))
    }
}
