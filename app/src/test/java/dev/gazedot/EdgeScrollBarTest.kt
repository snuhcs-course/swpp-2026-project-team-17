package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class EdgeScrollBarTest {
    @Test fun revealHoldRepeatAndExit() {
        val b=EdgeScrollBar()
        for(t in 1000L..1700L step 100){assertNull(b.update(t,t,EyeState.OPEN,.5 to .09));assertTrue(b.ownsInput)}
        assertEquals(ScrollDirection.UP,b.direction)
        assertEquals(ScrollDirection.UP,b.update(1800,1800,EyeState.OPEN,.5 to .09))
        assertNull(b.update(1900,1900,EyeState.OPEN,.5 to .5));assertFalse(b.ownsInput)
        assertNull(b.update(2400,2400,EyeState.OPEN,.5 to .5));assertNull(b.direction)
    }
    @Test fun closureStaleAndNewDirectionRequireFreshHold() {
        val b=EdgeScrollBar()
        b.update(1000,1000,EyeState.OPEN,.5 to .09)
        assertNull(b.update(1100,1601,EyeState.OPEN,.5 to .09));assertNull(b.direction)
        b.update(1700,1700,EyeState.OPEN,.5 to .09)
        b.update(1800,1800,EyeState.LEFT_WINK,null);assertFalse(b.ownsInput)
        assertNull(b.update(1900,1900,EyeState.OPEN,.5 to .91))
        for(t in 2000L..2600L step 100)assertNull(b.update(t,t,EyeState.OPEN,.5 to .91))
        assertEquals(ScrollDirection.DOWN,b.update(2700,2700,EyeState.OPEN,.5 to .91))
    }
    @Test fun barConsumesDwellAndWinkCannotToggleMode() {
        val e=InteractionEngine(useBars=true);val actions=mutableListOf<EyeAction>()
        for(t in 1000L..3000L step 100)actions+=e.update(t,EyeState.OPEN,.5 to .09)
        assertTrue(actions.any{it is EyeAction.Scroll});assertFalse(actions.any{it is EyeAction.Click})
        e.update(3100,EyeState.OPEN,.5 to .5)
        e.update(3200,EyeState.LEFT_WINK,null)
        assertFalse(e.update(3400,EyeState.OPEN,.5 to .5).any{it is EyeAction.ScrollMode})
        assertFalse(e.scrollMode)
    }
}
