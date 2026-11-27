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
        assertTrue(r.point.first>.65)
        var settled=r
        for(i in 4..12) settled=f.update(.8 to .5,i*100L)
        assertTrue(settled.point.first>.79)
    }
    @Test fun alternatingLargeOutliersCannotConfirmActions() {
        val f=GazeStabilizer();f.update(.5 to .5,0)
        for(i in 1..10) {
            val r=f.update((if(i%2==0) .9 else .1) to .5,i*100L)
            assertFalse(r.confirmed);assertTrue(abs(r.point.first-.5)<.17)
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

    @Test fun repeatedSmallNoiseDoesNotEnableFastFollow() {
        for (interval in listOf(33L, 66L)) {
            val f=GazeStabilizer(); f.update(.5 to .5,0)
            var total=0.0
            for (i in 1..120) {
                val raw=.5 + if ((i/2)%2==0) .04 else -.04
                val p=f.update(raw to .5, i*interval).point
                total+=abs(p.first-.5)
            }
            assertTrue("paired jitter must remain damped at $interval ms",total/120 < .02)
        }
    }
    @Test fun smallIntentionalMoveStillConverges() {
        val f=GazeStabilizer(); f.update(.5 to .5,0)
        var p=.5 to .5
        for(i in 1..12) p=f.update(.55 to .5,i*66L).point
        assertTrue(p.first>.545)
    }
    @Test fun diagonalInputsDoNotCreateAnAxisOnlyDetour() {
        val f=GazeStabilizer();f.update(.5 to .5,0)
        val p=f.update(.46 to .54,66).point
        assertEquals("both coordinates stay on the input segment",1.0,p.first+p.second,1e-9)
    }
    @Test fun stableResponseStillTracksAndSettlesAfterSustainedMotion() {
        val f=GazeStabilizer();f.update(.2 to .5,0)
        var p=.2 to .5
        for(i in 1..30) p=f.update((.2+i*.015) to .5,i*66L).point
        // v36 intentionally restores the pre-v33 stability response; do not demand
        // the removed fast-follow latency while checking that it never freezes.
        assertTrue("motion continues within the smoothing range",.65-p.first<.08)
        for (i in 31..42) p=f.update(.65 to .5,i*66L).point
        assertTrue("settles at the destination",abs(.65-p.first)<.006)
    }
    @Test fun corroboratedSmallMovementsKeepV14InterpolationAtOriginalCadence() {
        val f=GazeStabilizer(); f.update(.5 to .5,0)
        var legacy=.5
        for(i in 1..30) {
            val raw=if(i<10) .54 else if(i<20) .46 else .53
            legacy+=.28*(raw-legacy)
            assertEquals(legacy,f.update(raw to .5,i*66L).point.first,1e-9)
        }
    }
    @Test fun isolatedLargeErrorCannotMoveThePointerOrLeaveAFilterTail() {
        val f=GazeStabilizer();f.update(.5 to .5,0)
        assertEquals(.5 to .5,f.update(1.2 to -.2,66).point)
        assertEquals(.5 to .5,f.update(.5 to .5,132).point)
        val settled=f.update(.5 to .5,198)
        assertTrue(settled.confirmed)
        assertEquals(.5 to .5,settled.point)
    }
    @Test fun sustainedLargeMoveIsNotLockedToTheOldFixation() {
        val f=GazeStabilizer();f.update(.5 to .5,0)
        assertFalse(f.update(1.0 to 1.0,66).confirmed)
        assertTrue(f.update(1.0 to 1.0,132).confirmed)
        var p=.5 to .5
        for(i in 3..20) p=f.update(1.0 to 1.0,i*66L).point
        assertTrue(p.first>.995 && p.second>.995)
    }
    @Test fun everyCornerRemainsReachableWithNoDisplacementLock() {
        for (interval in listOf(33L,66L)) {
            val f=GazeStabilizer();f.update(.5 to .5,0)
            var time=0L
            for(target in listOf(0.0 to 0.0,1.0 to 0.0,1.0 to 1.0,0.0 to 1.0)) {
                var result=GazeStabilizer.Result(.5 to .5,false)
                repeat((1_500/interval).toInt()) { time+=interval;result=f.update(target,time) }
                assertEquals(target.first,result.point.first,.002)
                assertEquals(target.second,result.point.second,.002)
                assertTrue(result.confirmed)
            }
        }
    }
}
