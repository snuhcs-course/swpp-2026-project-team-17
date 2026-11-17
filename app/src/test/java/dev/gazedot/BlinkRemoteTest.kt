package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class BlinkRemoteTest {
    private fun blink(r:BlinkRemote,start:Long,duration:Long):Boolean {
        r.update(EyeState.OPEN,start,start)
        var t=start+100
        while(t<start+100+duration){r.update(EyeState.CLOSED,t,t);t+=100}
        return r.update(EyeState.OPEN,start+100+duration,start+100+duration)
    }
    @Test fun cyclesAllCandidatesAndCancelThenWraps() {
        val r=BlinkRemote(5)
        repeat(5){assertFalse(blink(r,it*1000L+100,200));assertEquals((it+1)%5,r.index)}
    }
    @Test fun sustainedClosureConfirmsOnlyOnReopening() {
        val r=BlinkRemote(5);assertTrue(blink(r,100,1100));assertEquals(0,r.index)
        assertFalse(r.update(EyeState.OPEN,1400,1400))
    }
    @Test fun winkLostAndStaleDoNotConfirm() {
        for(state in listOf(EyeState.LEFT_WINK,EyeState.RIGHT_WINK,EyeState.LOST,EyeState.UNCERTAIN)) {
            val r=BlinkRemote(4);r.update(EyeState.OPEN,100,100);r.update(state,200,200)
            assertFalse(r.update(EyeState.OPEN,300,300));assertEquals(0,r.index)
        }
        val r=BlinkRemote(4);r.update(EyeState.OPEN,100,100);r.update(EyeState.CLOSED,200,200)
        assertFalse(r.update(EyeState.OPEN,1300,1600));assertEquals(0,r.index)
    }
    @Test fun openingPanelWithEyesClosedDoesNotSelect() {
        val r=BlinkRemote(4);r.update(EyeState.CLOSED,100,100)
        assertFalse(r.update(EyeState.OPEN,200,200));assertEquals(0,r.index)
    }
}
