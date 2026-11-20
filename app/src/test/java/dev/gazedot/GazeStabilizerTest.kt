package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
class GazeStabilizerTest {
    @Test fun stationaryNoiseIsReduced() {
        val f=GazeStabilizer();f.update(.5 to .5,0)
        var rawError=0.0;var filteredError=0.0
        for(i in 1..100) {
            val x=.5+if(i%2==0) .025 else -.025
            val r=f.update(x to .5,i*50L)
            rawError+=abs(x-.5);filteredError+=abs(r.point.first-.5)
        }
        assertTrue(filteredError<rawError*.5)
    }
    @Test fun confirmedMoveReachesNewTargetWithoutLongLock() {
        val f=GazeStabilizer();f.update(.2 to .5,0)
        assertFalse(f.update(.8 to .5,100).confirmed)
        assertTrue(f.update(.79 to .5,200).confirmed)
        val r=f.update(.8 to .5,300)
        assertTrue(r.point.first>.75)
    }
    @Test fun alternatingLargeOutliersCannotMoveOrConfirm() {
        val f=GazeStabilizer();f.update(.5 to .5,0)
        for(i in 1..10) {
            val r=f.update((if(i%2==0) .9 else .1) to .5,i*100L)
            assertFalse(r.confirmed);assertEquals(.5,r.point.first,0.0)
        }
    }
    @Test fun resetDoesNotReusePreviousFixation() {
        val f=GazeStabilizer();f.update(.1 to .1,0);f.reset()
        assertEquals(.9 to .9,f.update(.9 to .9,100).point)
    }
    @Test fun missingPointerAlwaysHasAnExplanationOutsideDedicatedScreens() {
        for(phase in TrackingPhase.values()) {
            val state=TrackingStatus(phase,"reason")
            assertEquals("reason",trackingOverlayLabel(state,false,false,false,""))
            assertEquals("",trackingOverlayLabel(state,false,true,false,""))
        }
    }
}
