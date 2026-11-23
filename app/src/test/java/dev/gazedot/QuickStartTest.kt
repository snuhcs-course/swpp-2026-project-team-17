package dev.gazedot
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class QuickStartTest {
    @After fun clean() { TrackerState.cancelCalibration(); TrackerState.model=null; TrackerState.trackingStopped() }
    @Test fun producesCoordinatesImmediatelyWithoutUserLabelsAndHasFullRange() {
        val model=GazeModel.quickStart()
        assertTrue(model.isQuickStart)
        val center=EyeFeatures(0.0,.02,.5,.5,.3)
        assertEquals(.5,model.predict(center)!!.first,1e-6)
        assertEquals(.5,model.predict(center)!!.second,1e-6)
        assertEquals(0.0,model.predict(center.copy(x=.1))!!.first,1e-6)
        assertEquals(1.0,model.predict(center.copy(x=-.1))!!.first,1e-6)
        assertEquals(0.0,model.predict(center.copy(y=-.04))!!.second,1e-6)
        assertEquals(1.0,model.predict(center.copy(y=.08))!!.second,1e-6)
        assertNull(model.predict(center.copy(scale=0.0)))
        assertNull(model.predict(center.copy(x=Double.NaN)))
    }
    private fun calibrated()=GazeModel.fit(listOf(-.1,0.0,.1).flatMap{x -> listOf(-.08,0.0,.08).map { y ->
        Anchor(EyeFeatures(x,y,.5,.5,.3),.5+x*3,.5+y*4)
    }})!!
    @Test fun switchingModePreservesEarlierCalibrationAndDoesNotFabricateOne() {
        TrackerState.model=null
        TrackerState.enableQuickStart()
        assertTrue(TrackerState.model!!.isQuickStart)
        TrackerState.disableQuickStart()
        assertNull(TrackerState.model)
        val saved=calibrated();TrackerState.model=saved
        TrackerState.enableQuickStart();TrackerState.enableQuickStart()
        TrackerState.disableQuickStart()
        assertSame(saved,TrackerState.model)
    }
    @Test fun successfulCalibrationExitsQuickStartAndFailedCalibrationKeepsIt() {
        TrackerState.enableQuickStart()
        assertFalse(TrackerState.finishCalibration(TrackerState.beginCalibration(),null))
        assertTrue(TrackerState.model!!.isQuickStart)
        val model=calibrated()
        assertTrue(TrackerState.finishCalibration(TrackerState.beginCalibration(),model))
        assertSame(model,TrackerState.model)
        assertFalse(TrackerState.model!!.isQuickStart)
    }
}
