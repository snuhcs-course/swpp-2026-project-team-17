package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class TelemetryWindowTest {
    @Test fun noSamplesIsNotReportedAsZeroErrorSuccess() {
        assertNull(TelemetryWindow().drain(10000))
    }
    @Test fun missingStaleAndClosedFramesRemainInDenominator() {
        val w=TelemetryWindow()
        w.add(1000,40,EyeState.OPEN,.5 to .5)
        w.add(1100,600,EyeState.OPEN,null)
        w.add(1200,50,EyeState.CLOSED,.5 to .5)
        val r=w.drain(1300)!!
        assertEquals(3L,r["frames"])
        assertEquals(2L,r["open_frames"])
        assertEquals(1L,r["stale_frames"])
        assertEquals(1L,r["missing_points"])
        assertEquals(600L,r["latency_p95_ms"])
        assertFalse(r.containsKey("accuracy"))
    }
    @Test fun gapsAndJumpsAreSeparateAndWindowResets() {
        val w=TelemetryWindow()
        w.add(1000,30,EyeState.OPEN,.2 to .2)
        w.add(2000,40,EyeState.OPEN,.8 to .8)
        val r=w.drain(2100)!!
        assertEquals(1000L,r["max_gap_ms"])
        assertEquals(1L,r["jump_steps"])
        assertNull(w.drain(2200))
        w.add(2300,10,EyeState.OPEN,.1 to .1)
        assertEquals(0L,w.drain(2400)!!["jump_steps"])
    }
    @Test fun invalidPointIsMissingAndDoesNotCreateAJump() {
        val w=TelemetryWindow()
        w.add(100,5,EyeState.OPEN,.1 to .1)
        w.add(200,5,EyeState.OPEN,Double.NaN to .1)
        w.add(300,5,EyeState.OPEN,.9 to .9)
        val r=w.drain(400)!!
        assertEquals(1L,r["missing_points"])
        assertEquals(0L,r["jump_steps"])
    }
}
