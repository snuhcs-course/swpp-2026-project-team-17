package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class BinocularWinkTest {
    private fun features(x: Double,y: Double): EyeFeatures {
        val lx=(x-.5)*.24+.03; val ly=(y-.5)*.19-.02
        val rx=(x-.5)*.36-.04; val ry=(y-.5)*.28+.01
        return EyeFeatures((lx+rx)/2,(ly+ry)/2,.5,.5,.3,lx,ly,rx,ry)
    }
    private fun model()=GazeModel.fit(listOf(.15,.5,.85).flatMap { x ->
        listOf(.15,.5,.85).map { y -> Anchor(features(x,y),x,y) }
    })!!
    @Test fun differentEyeOffsetsAndScalesAreCalibratedSeparately() {
        val p=model().predict(features(.32,.71))!!
        assertEquals(.32,p.first,.01); assertEquals(.71,p.second,.01)
        assertNull(model().predict(features(.32,.71).copy(leftX=Double.NaN)))
    }
    @Test fun winkOutliersNeverMovePointerOrEnterCalibrationAndGestureSurvives() {
        for (wink in listOf(EyeState.LEFT_WINK,EyeState.RIGHT_WINK)) {
            val pipeline=GazePipeline(); val model=model()
            fun frame(at:Long,state:EyeState,f:EyeFeatures)=pipeline.process(at,at,EyeObservation(state,f,.2,.2),model,true)
            val before=frame(1000,EyeState.OPEN,features(.4,.4)).point!!
            for(at in listOf(1050L,1100L,1150L)) {
                val s=frame(at,wink,features(.95,.05))
                assertEquals(before,s.point);assertNull(s.rawPoint);assertNull(s.calibrationFeatures(at,0))
                assertFalse(s.actions.any { it is EyeAction.Click || it is EyeAction.Scroll })
            }
            val reopened=frame(1200,EyeState.OPEN,features(.95,.05))
            assertEquals(before,reopened.point)
            assertTrue(reopened.actions.any { it is EyeAction.ScrollMode && it.enabled })
            assertNull(reopened.toScreenGaze()?.takeIf { it.valid })
            frame(1250,EyeState.OPEN,features(.4,.4))
            frame(1300,EyeState.OPEN,features(.4,.4))
            assertEquals(before,frame(1350,EyeState.OPEN,features(.4,.4)).point)
        }
    }
    @Test fun prolongedClosureAndFaceLossDiscardHeldPoint() {
        val p=GazePipeline();val m=model()
        fun f(at:Long,state:EyeState)=p.process(at,at,EyeObservation(state,features(.5,.5),.2,.2),m,false)
        f(1000,EyeState.OPEN)
        for(at in 1050L..2350L step 50) f(at,EyeState.LEFT_WINK)
        assertNull(f(2400,EyeState.LEFT_WINK).point)
        assertNull(f(2450,EyeState.LOST).point)
    }
}
