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
        assertTrue(model.predict(center.copy(x=.15))!!.first<.06)
        assertTrue(model.predict(center.copy(x=-.15))!!.first>.94)
        assertTrue(model.predict(center.copy(quickVertical=-.5))!!.second<.06)
        assertTrue(model.predict(center.copy(quickVertical=.5))!!.second>.94)
        assertNull(model.predict(center.copy(scale=0.0)))
        assertNull(model.predict(center.copy(x=Double.NaN)))
    }
    @Test fun eyeShapeOffsetDoesNotPinQuickPointerToAnEdge() {
        val model=GazeModel.quickStart()
        val highOffset=EyeFeatures(.02,.18,.5,.5,.3,quickVertical=0.0)
        assertEquals(.5,model.predict(highOffset)!!.second,1e-6)
        val up=model.predict(highOffset.copy(quickVertical=-.2))!!
        val down=model.predict(highOffset.copy(quickVertical=.2))!!
        assertTrue(up.second<.3 && down.second>.7)
        val pipeline=GazePipeline()
        val first=pipeline.process(1000,1000,EyeObservation(EyeState.OPEN,highOffset, .2,.2),model,false)
        var last=first
        for(t in 1050L..2000L step 50) last=pipeline.process(t,t,EyeObservation(EyeState.OPEN,highOffset.copy(quickVertical=.2),.2,.2),model,false)
        assertTrue(last.point!!.second-first.point!!.second>.2)
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
