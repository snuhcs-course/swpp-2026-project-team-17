package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class ScreenCalibrationTest {
    @Test fun futureFrameCannotLockOutNormalCameraFrames() {
        val c=ScreenCalibration(0);val target=.5 to .5
        c.observe(sample(999999,.5,.5),650,target,"missing")
        c.observe(sample(700,.5,.5),700,target,"missing")
        assertEquals(1,c.count)
    }
    private fun sample(t:Long,x:Double,y:Double)=GazeSample(t,TrackingQuality.OUT_OF_RANGE,
        EyeFeatures((x-.5)*.3,(y-.5)*.2,.5,.5,.3,head=null),null,null,0f,emptyList())
    private fun complete(c:ScreenCalibration,start:Long=0):Long {
        var t=start
        repeat(9) {
            val target=c.targets[c.index];t+=650
            repeat(24) { c.observe(sample(t,target.first,target.second),t,target,"missing");t+=50 }
        }
        return t
    }
    @Test fun fullNinePointFlowWorksWithoutHeadPoseAndReachesAllDirections() {
        val c=ScreenCalibration(0);complete(c)
        assertEquals(9,c.completedCount)
        val result=c.review!!
        assertNotNull(result.report,result.model)
        for((x,y) in c.targets) {
            val point=result.model!!.predict(sample(0,x,y).features!!)!!
            assertEquals(x,point.first,.015);assertEquals(y,point.second,.015)
        }
        assertTrue(result.rms!!<.01)
    }
    @Test fun duplicateStaleAndBlinkFramesDoNotBecomeSamplesOrEraseShortProgress() {
        val c=ScreenCalibration(0);val target=.5 to .5
        for(t in 650L..1000L step 50) c.observe(sample(t,.5,.5),t,target,"missing")
        assertEquals(8,c.count)
        c.observe(sample(1000,.5,.5),1050,target,"missing")
        assertEquals(8,c.count)
        c.observe(GazeSample(1100,TrackingQuality.CLOSED,null,null,null,0f,emptyList()),1100,target,"blink")
        assertEquals(8,c.count)
        c.observe(sample(1200,.5,.5),1200,target,"missing")
        assertEquals(8,c.count)
        c.observe(sample(1350,.5,.5),1350,target,"missing")
        assertEquals(9,c.count)
        c.observe(sample(1400,.5,.5),2200,target,"stale")
        assertEquals(9,c.count)
        c.observe(null,3000,target,"lost")
        assertEquals(0,c.count)
    }
    @Test fun retryReplacesOnlySelectedTargetAndKeepsOtherEight() {
        val c=ScreenCalibration(0);var t=complete(c);val before=c.fixations()
        c.retry(3,t);assertNull(c.review);assertEquals(8,c.completedCount)
        val target=c.targets[3];t+=650
        repeat(24) { c.observe(sample(t,target.first,target.second),t,target,"missing");t+=50 }
        assertNotNull(c.review!!.model);assertEquals(9,c.completedCount)
        for(i in before.indices) if(i!=3) assertSame(before[i],c.fixations()[i])
        assertNotSame(before[3],c.fixations()[3])
    }
    @Test fun stationaryEyesNeverProduceAFabricatedMapping() {
        val c=ScreenCalibration(0);var t=0L
        repeat(9) {
            val target=c.targets[c.index];t+=650
            repeat(24) { c.observe(sample(t,.5,.5),t,target,"missing");t+=50 }
        }
        assertNull(c.review!!.model);assertTrue(c.review!!.report.contains("범위"))
        assertEquals(9,c.completedCount)
    }
    @Test fun aSingleOutlierDoesNotDominateTargetMedian() {
        val c=ScreenCalibration(0);var t=0L
        repeat(9) {
            val target=c.targets[c.index];t+=650
            repeat(24) { i ->
                val f=sample(t,target.first,target.second)
                c.observe(if(i==4) f.copy(features=f.features!!.copy(x=3.0,y=-3.0)) else f,t,target,"missing")
                t+=50
            }
        }
        assertNotNull(c.review!!.model);assertTrue(c.review!!.rms!!<.01)
    }
}
