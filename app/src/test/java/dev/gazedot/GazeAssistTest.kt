package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class GazeAssistTest {
    private fun jitter(at: Long) = AssistPoint(if ((at / 100) % 2 == 0L) 450.0 else 550.0, 500.0)
    private fun update(detector: GazeAssistDetector, at: Long, point: AssistPoint? = jitter(at),
        progress: Float = 0f, interrupted: Boolean = false) =
        detector.update(point, at, at, 1_000.0, progress, interrupted)

    @Test fun repeatedLocalJitterSuggestsAnAreaOnlyAfterEnoughFreshEvidence() {
        val detector = GazeAssistDetector()
        for (at in 0L..1_500L step 100) assertNull(update(detector, at))
        val focus = update(detector, 1_600)!!
        assertEquals(17, focus.points.size)
        assertTrue(focus.center.x in 450.0..550.0)
        assertEquals(100.0, focus.spread, 0.0)
    }

    @Test fun steadyGazeAndSmallHarmlessNoiseDoNotOpenAssistance() {
        for (noise in listOf(0.0, 3.0)) {
            val detector = GazeAssistDetector()
            for (at in 0L..4_000L step 100) {
                val p = AssistPoint(500 + if ((at / 100) % 2 == 0L) noise else -noise, 500.0)
                assertNull(update(detector, at, p, ((at % 1_000) / 1_000f)))
            }
        }
    }

    @Test fun tinyJitterBetweenTwoRealButtonsCanBeDetectedBeforeDwellProgressRises() {
        val detector = GazeAssistDetector()
        var result: AssistFocus? = null
        for (at in 0L..1_600L step 100) {
            result = detector.update(AssistPoint(if ((at / 100) % 2 == 0L) 499.0 else 501.0, 500.0),
                at, at, 1_000.0, 0f, false, if ((at / 100) % 2 == 0L) "button:left" else "button:right")
            if (at < 1_600) assertNull(result)
        }
        assertNotNull(result)
    }

    @Test fun movingThroughDifferentButtonsWithoutRevisitingIsStillExploration() {
        val detector = GazeAssistDetector()
        for (at in 0L..3_000L step 100) {
            assertNull(detector.update(AssistPoint(400 + at / 20.0, 500.0), at, at, 1_000.0,
                0f, false, "button:${at / 300}"))
        }
    }

    @Test fun smoothReadingMovementAndBroadExplorationDoNotLookLikeLocalJitter() {
        val moving = GazeAssistDetector()
        val scanning = GazeAssistDetector()
        for (at in 0L..3_000L step 100) {
            assertNull(update(moving, at, AssistPoint(200 + at / 10.0, 500.0)))
            assertNull(update(scanning, at,
                AssistPoint(if ((at / 100) % 2 == 0L) 100.0 else 900.0, 500.0)))
        }
    }

    @Test fun successfulActionsAndScrollingPreventAutomaticSuggestions() {
        val detector = GazeAssistDetector()
        for (at in 0L..7_000L step 100) {
            // A completed click or active scroll is passed as an interruption by the camera-result adapter.
            assertNull(update(detector, at, interrupted = at % 1_000 == 0L))
        }
    }

    @Test fun eyeLossAndMissingFramesCannotBridgeAnUnsuccessfulAttempt() {
        for (missingFrame in listOf(false, true)) {
            val detector = GazeAssistDetector()
            for (at in 0L..800L step 100) update(detector, at)
            if (!missingFrame) update(detector, 900, null)
            for (at in 1_200L..2_700L step 100) assertNull(update(detector, at))
            assertNotNull(update(detector, 2_800))
        }
    }

    @Test fun staleFutureAndRepeatedFramesCannotAccumulateEvidence() {
        val detector = GazeAssistDetector()
        for (now in 0L..2_000L step 100) {
            assertNull(detector.update(jitter(now), now - 300, now, 1_000.0, 0f, false))
            assertNull(detector.update(jitter(now), now + 1, now, 1_000.0, 0f, false))
            assertNull(detector.update(jitter(now), 0, now, 1_000.0, 0f, false))
        }
    }

    @Test fun dismissalCooldownPreventsImmediateReopening() {
        val detector = GazeAssistDetector()
        for (at in 0L..1_500L step 100) update(detector, at)
        assertNotNull(update(detector, 1_600))
        for (at in 1_700L..7_100L step 100) assertNull(update(detector, at))
        assertNotNull(update(detector, 7_200))
    }

    @Test fun manualHelpUsesOnlyRecentSourceGazeAndResetDiscardsIt() {
        val detector = GazeAssistDetector()
        update(detector, 100)
        assertNotNull(detector.recentFocus(1_100, 1_000.0))
        assertNull(detector.recentFocus(3_101, 1_000.0))
        detector.reset()
        assertNull(detector.recentFocus(200, 1_000.0))
    }

    private fun focus(outliers: Boolean = false): AssistFocus {
        val points = List(10) { AssistPoint(if (it % 2 == 0) 450.0 else 550.0, 500.0) } +
            if (outliers) listOf(AssistPoint(9_000.0, 9_000.0)) else emptyList()
        return AssistFocus(points, AssistPoint(550.0, 500.0), 100.0, 1_000.0)
    }
    private val targets = listOf(
        AssistTarget("far-first", 850.0, 480.0, 890.0, 520.0),
        AssistTarget("left", 430.0, 480.0, 470.0, 520.0),
        AssistTarget("right", 620.0, 480.0, 660.0, 520.0),
        AssistTarget("nearest-last", 530.0, 480.0, 570.0, 520.0))

    @Test fun ranksNearbyButtonsInsteadOfTakingTheFirstThreeTreeEntries() {
        val expected = listOf("nearest-last", "left", "right")
        assertEquals(expected, NearbyGazeCandidates.rank(focus(), targets))
        assertEquals(expected, NearbyGazeCandidates.rank(focus(), targets.reversed()))
    }

    @Test fun isolatedOutlierDoesNotPromoteDistantButtons() {
        assertEquals(NearbyGazeCandidates.rank(focus(), targets),
            NearbyGazeCandidates.rank(focus(outliers = true), targets))
    }

    @Test fun neverPadsTheListWithDistantOrInvalidButtons() {
        assertTrue(NearbyGazeCandidates.rank(focus(), targets.take(1)).isEmpty())
        assertEquals(listOf("left"), NearbyGazeCandidates.rank(focus(), targets.take(2)))
        assertTrue(NearbyGazeCandidates.rank(focus(), listOf(
            AssistTarget("invalid", 550.0, 500.0, 500.0, 490.0))).isEmpty())
    }

    @Test fun proximityUsesButtonEdgesRatherThanOnlyTheirCenters() {
        val area = AssistFocus(listOf(AssistPoint(500.0, 500.0)), AssistPoint(500.0, 500.0), 0.0, 1_000.0)
        assertEquals(listOf("wide"), NearbyGazeCandidates.rank(area, listOf(
            AssistTarget("wide", 100.0, 490.0, 510.0, 510.0),
            AssistTarget("distant", 700.0, 490.0, 720.0, 510.0))))
    }
}
