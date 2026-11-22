package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class ITrackerIntegrationTest {
    private fun feature(x:Double,y:Double)=EyeFeatures(x*6-3,-y*12,.5,.5,.3,source=GazeSource.ITRACKER)
    private val targets=listOf(.5 to .5,.18 to .18,.82 to .18,.82 to .82,.18 to .82)
    @Test fun fivePointsMapFrozenCnnCentimetresToScreenWithoutHeadTraining() {
        val model=GazeModel.fit(targets.map{Anchor(feature(it.first,it.second),it.first,it.second)})!!
        val p=model.predict(feature(.35,.65))!!
        assertEquals(.35,p.first,.01);assertEquals(.65,p.second,.01)
        assertNull(model.headCorrection)
        assertNotNull(model.predict(feature(.35,.65).copy(faceX=.66,scale=.42)))
        assertNull(model.predict(feature(.35,.65).copy(source=GazeSource.LANDMARK)))
    }
    @Test fun cannotMixOldLandmarkCoordinatesAndCnnOutputs() {
        val anchors=targets.map{Anchor(feature(it.first,it.second),it.first,it.second)}
        assertNull(GazeModel.fit(anchors.dropLast(1)+anchors.last().copy(eye=anchors.last().eye.copy(source=GazeSource.LANDMARK))))
        assertEquals(GazeSource.ITRACKER,medianFeatures(anchors.map{it.eye}).source)
    }
    @Test fun faceGridIsRowMajorAndUsesTheSameFaceCrop() {
        val g=ITrackerGeometry.grid(CropBox(100.0,200.0,200.0,200.0),500,1000)
        assertEquals(50,g.count{it==1f})
        assertEquals(1f,g[5*25+5],0f);assertEquals(1f,g[9*25+14],0f)
        assertEquals(0f,g[4*25+5],0f);assertEquals(0f,g[5*25+15],0f)
        assertFalse(ITrackerGeometry.usable(CropBox(-100.0,0.0,200.0,200.0),500,1000))
    }
    @Test fun eyeClosureStillBlocksCnnCoordinatesAndActions() {
        val model=GazeModel.fit(targets.map{Anchor(feature(it.first,it.second),it.first,it.second)})!!
        val pipe=GazePipeline()
        pipe.process(1000,1000,EyeObservation(EyeState.OPEN,feature(.5,.5),.2,.2),model,false)
        val wink=pipe.process(1100,1100,EyeObservation(EyeState.LEFT_WINK,null,.02,.2),model,false)
        assertNull(wink.features);assertNull(wink.calibrationFeatures(1100,0));assertTrue(wink.actions.isEmpty())
    }
}
