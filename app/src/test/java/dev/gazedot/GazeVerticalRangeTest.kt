package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class GazeVerticalRangeTest {
    private fun eye(x:Double,y:Double)=EyeFeatures((x-.5)*.3,(y-.5)*.2,.5,.5,.3)
    private val model=GazeModel.fit(listOf(.08,.5,.92).flatMap { x ->
        listOf(.08,.5,.92).map { y -> Anchor(eye(x,y),x,y) }
    })!!
    private fun observation(x:Double,y:Double)=EyeObservation(EyeState.OPEN,eye(x,y),.2,.2)

    @Test fun expandsBothDirectionsWithoutShiftingCenterOrHorizontalPosition() {
        for(y in listOf(.08,.5,.92)) {
            val sample=GazePipeline().process(1000,1000,observation(.3,y),model,false)
            val raw=model.predictForFiltering(eye(.3,y))!!
            assertEquals(raw,sample.rawPoint)
            assertEquals(raw.first,sample.point!!.first,1e-12)
            assertEquals(.5+(raw.second-.5)*1.1,sample.point.second,1e-12)
        }
        val center=GazePipeline().process(1000,1000,observation(.5,.5),model,false).point!!
        assertEquals(.5,center.second,1e-9)
    }

    @Test fun outputIsMonotonicAndCanReachBothPhysicalEdges() {
        val points=(0..100).map { i ->
            GazePipeline().process(1000,1000,observation(.5,i/100.0),model,false).point!!.second
        }
        assertEquals(0.0,points.first(),0.0);assertEquals(1.0,points.last(),0.0)
        assertTrue(points.all { it in 0.0..1.0 })
        assertTrue(points.zipWithNext().all { (a,b)->a<=b })
    }

    @Test fun targetLookupAndClickUseTheDisplayedExpandedCoordinate() {
        val pipeline=GazePipeline();val clicks=mutableListOf<EyeAction.Click>()
        var lookedUp:Pair<Double,Double>?=null
        var sample:GazeSample?=null
        for(t in 1000L..2600L step 100) {
            sample=pipeline.process(t,t,observation(.5,.88),model,true) {
                lookedUp=it;"bottom-target"
            }
            clicks+=sample.actions.filterIsInstance<EyeAction.Click>()
        }
        assertEquals(sample!!.point,lookedUp)
        assertTrue(clicks.isNotEmpty())
        assertEquals(sample.point!!.second,clicks.first().point.second,1e-9)
    }
}
