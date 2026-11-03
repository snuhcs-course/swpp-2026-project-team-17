package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class TutorialModeChoiceTest {
    @Test fun enteringPageWhileLookingAtAnOptionCannotSelectIt() {
        val choice = TutorialModeChoice()
        for (time in 0L..2_000L step 100) assertNull(choice.update(true, true, time, time))
        assertEquals(0f, choice.progress, 0f)
    }

    @Test fun bothOnAndOffRequireOneFreshSecondAfterLookingAtInstructions() {
        for (enabled in listOf(true, false)) {
            val choice = TutorialModeChoice()
            assertNull(choice.update(null, true, 0, 0))
            for (time in 100L..1_000L step 100) assertNull(choice.update(enabled, true, time, time))
            assertEquals(enabled, choice.update(enabled, true, 1_100, 1_100))
        }
    }

    @Test fun invalidOrStaleInstructionGazeCannotArmSelection() {
        val choice = TutorialModeChoice()
        choice.update(null, false, 0, 0)
        choice.update(null, true, 0, 300)
        choice.update(null, true, 500, 400)
        for (time in 500L..1_800L step 100) assertNull(choice.update(false, true, time, time))
        assertEquals(0f, choice.progress, 0f)
    }

    @Test fun movingBetweenOptionsDoesNotCombineTheirDwellTimes() {
        val choice = TutorialModeChoice()
        choice.update(null, true, 0, 0)
        for (time in 100L..800L step 100) choice.update(true, true, time, time)
        for (time in 900L..1_800L step 100) assertNull(choice.update(false, true, time, time))
        assertEquals(false, choice.update(false, true, 1_900, 1_900))
    }

    @Test fun eyeLossAndLeavingOptionsRestartDwell() {
        for (eyeLost in listOf(true, false)) {
            val choice = TutorialModeChoice()
            choice.update(null, true, 0, 0)
            for (time in 100L..800L step 100) choice.update(true, true, time, time)
            choice.update(if (eyeLost) true else null, !eyeLost, 900, 900)
            assertEquals(0f, choice.progress, 0f)
            for (time in 1_000L..1_900L step 100) assertNull(choice.update(true, true, time, time))
            assertEquals(true, choice.update(true, true, 2_000, 2_000))
        }
    }

    @Test fun missingFramesCannotCompleteSelectionAndResetRequiresRearming() {
        val choice = TutorialModeChoice()
        choice.update(null, true, 0, 0)
        for (time in 100L..800L step 100) choice.update(false, true, time, time)
        choice.expire(1_051)
        assertEquals(0f, choice.progress, 0f)
        assertNull(choice.update(false, true, 1_100, 1_100))
        choice.reset()
        for (time in 1_200L..2_500L step 100) assertNull(choice.update(false, true, time, time))
        assertEquals(0f, choice.progress, 0f)
    }
}
