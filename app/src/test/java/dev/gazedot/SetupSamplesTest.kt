package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class SetupSamplesTest {
    private fun frame(t:Long)=FaceFrame(t,480,640,emptyList(),HeadPose(0.0,0.0,0.0,.5,.5,.03),
        15.0,14.0,.30,.28,.18,.23,.02,.03,.45,.65)
    @Test fun realBlinkAndDuplicateUiPollsDoNotEraseMeasurement() {
        val collector=SetupSamples();var profile:EyeProfile?=null
        for(i in 1..36){
            val t=i*80L
            val f=frame(t).let{if(i in 14..17) it.copy(leftBlink=.9,rightBlink=.9) else it}
            collector.add(f,t+50)?.let{profile=it}
            val count=collector.count
            assertNull(collector.add(f,t+70))
            assertEquals(count,collector.count)
        }
        assertNotNull(profile);assertEquals(15.0,profile!!.leftDiameter,0.0)
    }
    @Test fun setupLatencyDoesNotUseActionableGazeCutoff() {
        val c=SetupSamples();var profile:EyeProfile?=null
        for(i in 1..32){val t=i*90L;c.add(frame(t),t+350)?.let{profile=it}}
        assertNotNull(profile)
        val stale=SetupSamples();repeat(35){val t=(it+1)*80L;assertNull(stale.add(frame(t),t+751))}
        assertEquals(0,stale.count)
        val gaze=GazeSample(100,TrackingQuality.TRACKING,EyeFeatures(0.0,0.0,.5,.5,.3),null,null,0f,emptyList())
        assertNull(gaze.calibrationFeatures(450,0)) // Gaze safety remains 250ms.
    }
    @Test fun prolongedLossClearsOldSamples() {
        val c=SetupSamples();for(i in 1..20)c.add(frame(i*80L),i*80L)
        assertEquals(20,c.count);assertNull(c.add(null,3000));assertEquals(0,c.count)
        assertNull(c.add(frame(3100),3100));assertEquals(1,c.count)
    }
    @Test fun movementStartsANewPoseWindow() {
        val c=SetupSamples();for(i in 1..20)c.add(frame(i*80L),i*80L)
        val moved=frame(1700).copy(head=frame(1700).head.copy(yaw=7.0))
        assertNull(c.add(moved,1700));assertEquals(1,c.count)
    }
    @Test fun badIrisDataAndClosedEyesNeverAdvance() {
        for(bad in listOf(frame(1).copy(leftDiameter=2.0),frame(1).copy(leftBlink=.8),
            frame(1).copy(leftRatio=.9),frame(1).copy(leftOpen=.03))){
            val c=SetupSamples()
            for(i in 1..100)assertNull(c.add(bad.copy(at=i*100L),i*100L))
            assertEquals(0,c.count)
        }
    }
    @Test fun validCountDoesNotBypassDiameterStabilityOrDuration() {
        val c=SetupSamples()
        for(i in 1..100){val t=i*80L;assertNull(c.add(frame(t).copy(leftDiameter=if(i%2==0)15.0 else 21.0),t))}
        val rapid=SetupSamples();for(i in 1..35)assertNull(rapid.add(frame(i.toLong()),i.toLong()))
    }
    @Test fun restartRequiresNewSamplesAndRejectsDuplicates() {
        val c=SetupSamples();repeat(20){c.add(frame(100),100)};assertEquals(1,c.count)
        c.reset();assertEquals(0,c.count);assertNull(c.add(frame(100),100));assertEquals(1,c.count)
    }
}
