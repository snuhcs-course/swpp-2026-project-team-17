package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class EdgeScrollBarTest {
    @Test fun exactScreenEdgesWorkAndOldInsetDoesNotStealClicks() {
        for(y in listOf(0.0,1.0)) {
            val b=EdgeScrollBar()
            for(t in 1000L..1700L step 100) b.update(t,t,EyeState.OPEN,.5 to y)
            assertNotNull(b.update(1800,1800,EyeState.OPEN,.5 to y))
        }
        for(y in listOf(.09,.14,.86,.91,.5)) {
            val b=EdgeScrollBar()
            for(t in 1000L..2000L step 100) assertNull(b.update(t,t,EyeState.OPEN,.5 to y))
            assertFalse(b.ownsInput)
        }
    }
    @Test fun horizontalEdgesTriggerScrollWithoutClickAndCornersStayNeutral() {
        for((x,direction) in listOf(0.0 to ScrollDirection.LEFT,1.0 to ScrollDirection.RIGHT)) {
            val engine=InteractionEngine(useBars=true)
            val actions=(1000L..3000L step 100).flatMap { t -> engine.update(t,EyeState.OPEN,x to .5) }
            assertTrue(actions.any { it==EyeAction.Scroll(direction) })
            assertFalse(actions.any { it is EyeAction.Click })
        }
        for(x in listOf(0.0,1.0)) for(y in listOf(0.0,1.0)) {
            val b=EdgeScrollBar()
            for(t in 1000L..2000L step 100) assertNull(b.update(t,t,EyeState.OPEN,x to y))
            assertFalse(b.ownsInput)
        }
    }
    @Test fun revealHoldRepeatAndExit() {
        val b=EdgeScrollBar()
        for(t in 1000L..1700L step 100){assertNull(b.update(t,t,EyeState.OPEN,.5 to .02));assertTrue(b.ownsInput)}
        assertEquals(ScrollDirection.UP,b.direction)
        assertEquals(ScrollDirection.UP,b.update(1800,1800,EyeState.OPEN,.5 to .02))
        assertNull(b.update(1900,1900,EyeState.OPEN,.5 to .5));assertFalse(b.ownsInput)
        assertNull(b.update(2400,2400,EyeState.OPEN,.5 to .5));assertNull(b.direction)
    }
    @Test fun closureStaleAndNewDirectionRequireFreshHold() {
        val b=EdgeScrollBar()
        b.update(1000,1000,EyeState.OPEN,.5 to .02)
        assertNull(b.update(1100,1601,EyeState.OPEN,.5 to .02));assertNull(b.direction)
        b.update(1700,1700,EyeState.OPEN,.5 to .02)
        b.update(1800,1800,EyeState.LEFT_WINK,null);assertFalse(b.ownsInput)
        assertNull(b.update(1900,1900,EyeState.OPEN,.5 to .98))
        for(t in 2000L..2600L step 100)assertNull(b.update(t,t,EyeState.OPEN,.5 to .98))
        assertEquals(ScrollDirection.DOWN,b.update(2700,2700,EyeState.OPEN,.5 to .98))
    }
    @Test fun barConsumesDwellAndWinkCannotToggleMode() {
        val e=InteractionEngine(useBars=true);val actions=mutableListOf<EyeAction>()
        for(t in 1000L..3000L step 100)actions+=e.update(t,EyeState.OPEN,.5 to .02)
        assertTrue(actions.any{it is EyeAction.Scroll});assertFalse(actions.any{it is EyeAction.Click})
        e.update(3100,EyeState.OPEN,.5 to .5)
        e.update(3200,EyeState.LEFT_WINK,null)
        assertFalse(e.update(3400,EyeState.OPEN,.5 to .5).any{it is EyeAction.ScrollMode})
        assertFalse(e.scrollMode)
    }
}
