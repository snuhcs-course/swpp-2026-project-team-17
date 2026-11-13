package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class RecognitionLossPolicyTest {
    private fun feed(policy: RecognitionLossPolicy, from: Long, through: Long,
        eligible: Boolean = true): List<Long> = (from..through step 100).filter {
        policy.update(it, it, missing = true, eligible = eligible)
    }

    @Test fun waitsFullTwentySecondsAndShowsOnlyOnce() {
        val policy = RecognitionLossPolicy()
        assertTrue(feed(policy, 0, 19_900).isEmpty())
        assertTrue(policy.update(20_000, 20_000, true, true))
        assertTrue(feed(policy, 20_100, 80_000).isEmpty())
    }
    @Test fun aSingleRecoveredFrameRestartsTheWholeInterval() {
        val policy = RecognitionLossPolicy()
        feed(policy, 0, 19_900)
        assertFalse(policy.update(20_000, 20_000, false, true))
        assertTrue(feed(policy, 20_100, 40_000).isEmpty())
        assertTrue(policy.update(40_100, 40_100, true, true))
    }
    @Test fun ineligibleTimeNeverAccumulates() {
        val policy = RecognitionLossPolicy()
        assertTrue(feed(policy, 0, 50_000, eligible = false).isEmpty())
        assertEquals(listOf(70_100L), feed(policy, 50_100, 70_100))
    }
    @Test fun suspensionPreservesOncePerEpisodeEvenOnAnotherScreen() {
        val policy = RecognitionLossPolicy()
        assertEquals(listOf(20_000L), feed(policy, 0, 20_000))
        policy.suspend()
        assertTrue(feed(policy, 20_100, 80_000).isEmpty())
        policy.update(80_100, 80_100, false, false)
        assertEquals(listOf(100_200L), feed(policy, 80_200, 100_200))
    }
    @Test fun cameraSilenceAndDuplicateResultsCannotReachTheDeadline() {
        val policy = RecognitionLossPolicy()
        feed(policy, 0, 19_900)
        assertFalse(policy.update(20_000, 19_900, true, true))
        assertFalse(policy.update(20_200, 19_900, true, true))
        assertFalse(policy.update(25_000, null, true, true))
        assertEquals(listOf(45_100L), feed(policy, 25_100, 45_100))
    }
    @Test fun aFrameGapRestartsTimeWithoutRepeatingAnAlreadyShownCard() {
        val policy = RecognitionLossPolicy()
        feed(policy, 0, 19_900)
        assertEquals(listOf(45_000L), feed(policy, 25_000, 45_000))
        assertTrue(feed(policy, 50_000, 80_000).isEmpty())
    }
    @Test fun delayCanBeChangedInOnePlace() {
        val policy = RecognitionLossPolicy(delayMs = 500)
        assertEquals(listOf(500L), feed(policy, 0, 2_000))
        policy.reset()
        assertEquals(listOf(2_600L), feed(policy, 2_100, 2_600))
    }
}
