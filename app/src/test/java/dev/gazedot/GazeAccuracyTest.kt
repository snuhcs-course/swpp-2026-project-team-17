package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class GazeAccuracyTest {
    @Test fun verticalAndHorizontalErrorsUseSamePhysicalPixelTolerance() {
        val target=.5 to .5
        assertTrue(GazeAccuracy.evaluate(listOf(.54 to .5),target,1000,2000).meets95Percent)
        assertFalse(GazeAccuracy.evaluate(listOf(.5 to .54),target,1000,2000).meets95Percent)
        assertTrue(GazeAccuracy.evaluate(listOf(.5 to .525),target,1000,2000).meets95Percent)
    }
    @Test fun perfectlyStableButBiasedPointerFailsAccuracy() {
        val result=GazeAccuracy.evaluate(List(100){.4 to .5},.5 to .5,1080,2340)
        assertEquals(0,result.hits);assertFalse(result.meets95Percent)
    }
    @Test fun missingFramesCannotInflatePassRate() {
        val good=List(95){.5 to .5}
        assertTrue(GazeAccuracy.evaluate(good+List(5){null},.5 to .5,1080,2340).meets95Percent)
        assertFalse(GazeAccuracy.evaluate(good+List(6){null},.5 to .5,1080,2340).meets95Percent)
        assertFalse(GazeAccuracy.evaluate(emptyList(),.5 to .5,1080,2340).meets95Percent)
    }
}
