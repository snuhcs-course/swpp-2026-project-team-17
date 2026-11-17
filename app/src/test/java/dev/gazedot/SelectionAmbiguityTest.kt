package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class SelectionAmbiguityTest {
    private val a=SelectionCandidate("a",.1,.1,.4,.4)
    @Test fun clearInteriorSkipsWhileEdgeAndMissAsk() {
        assertTrue(SelectionAmbiguity.clearHit(listOf(a),.25,.25))
        assertFalse(SelectionAmbiguity.clearHit(listOf(a),.11,.25))
        assertFalse(SelectionAmbiguity.clearHit(listOf(a),.5,.5))
    }
    @Test fun overlappingPeersAskButParentDoesNotObscureClearChild() {
        val other=SelectionCandidate("b",.2,.2,.5,.5)
        assertFalse(SelectionAmbiguity.clearHit(listOf(a,other),.3,.3))
        assertTrue(SelectionAmbiguity.clearHit(listOf(a,SelectionCandidate("parent",0.0,0.0,1.0,1.0)),.25,.25))
    }
    @Test fun actionFreshnessAllows400msButRejects501ms() {
        val d=TutorialDwell()
        for(t in 0L..900L step 100) assertFalse(d.update(true,t,t+400))
        assertTrue(d.update(true,1000,1400))
        assertFalse(d.update(true,1100,1601));assertEquals(0f,d.progress)
        assertTrue(ScreenGaze(.5,.5,100).fresh(600))
        assertFalse(ScreenGaze(.5,.5,100).fresh(601))
    }
}
