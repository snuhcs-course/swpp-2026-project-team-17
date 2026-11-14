package dev.gazedot

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Three-pass scheduling, shared by both calibration candidates. Per-point rules are the original ones. */
class CalibrationControllerTest {
    private val targets = CalibrationRules.TARGETS
    private val waiting = "눈 검출 대기 · 눈을 뜨고 얼굴을 카메라로 향해 주세요"
    private val moved = "머리가 움직였습니다 · 같은 점에서 다시 측정합니다"

    @After fun resetTrackerState() {
        TrackerState.cancelCalibration()
        TrackerState.model = null
    }

    @Test fun originalTargetsAndPerPointRulesAreKept() {
        assertEquals(listOf(.5 to .5, .15 to .15, .5 to .15, .85 to .15,
            .85 to .5, .85 to .85, .5 to .85, .15 to .85, .15 to .5), targets)
        assertEquals(1_000L, CalibrationRules.SETTLING_MS)
        assertEquals(22, CalibrationRules.MIN_SAMPLES)
        assertEquals(2_500L, CalibrationRules.MIN_POINT_MS)
        assertEquals(9_000L, CalibrationRules.POINT_TIMEOUT_MS)
        assertEquals(.045, CalibrationRules.MAX_FACE_SHIFT, 0.0)
        assertEquals(4, CalibrationRules.BLINK_BREAK_AFTER_POINTS)
        assertEquals(2_000L, CalibrationRules.BLINK_BREAK_MS)
        assertEquals(3, CalibrationRules.PASSES)
        assertEquals(3_000L, CalibrationRules.PASS_BREAK_MS)
        assertEquals(27, CalibrationRules.TOTAL_ANCHORS)
    }

    @Test fun collectsThreePassesOfNineSeparateAnchorsAndFitsOnce() {
        val h = CalibrationHarness()
        h.runToEnd()
        val outcome = h.outcomes.single()
        assertNull(outcome.failure)
        assertNotNull(outcome.model)
        assertEquals(CalibrationPlan.ID, outcome.calibrationId)
        assertEquals(27, outcome.anchors.size)
        assertEquals(mapOf(1 to 9, 2 to 9, 3 to 9), outcome.anchors.groupingBy { it.pass }.eachCount())
        assertEquals(1, h.fits.size)
        // All 27 anchors go to the fit, in collection order; nothing is averaged back into nine.
        assertEquals(outcome.anchors.map { it.anchor }, h.fits.single())
        assertTrue(h.controller.closed)
        repeat(300) { h.step() }
        assertEquals(1, h.fits.size)
        assertEquals(1, h.outcomes.size)
    }

    @Test fun targetOrderAndLabelsRepeatAcrossPassBoundaries() {
        val h = CalibrationHarness()
        val shown = mutableListOf<Pair<String, Pair<Double, Double>>>()
        fun record() {
            val screen = h.controller.screen(h.now) ?: return
            val target = screen.target ?: return
            if (shown.lastOrNull()?.first != screen.title) shown += screen.title to target
        }
        record()
        h.runToEnd(::record)
        val expected = (1..3).flatMap { p -> targets.mapIndexed { i, t -> "시선 보정 · $p/3회차 · ${i + 1}/9" to t } }
        assertEquals(expected, shown)
        val anchors = h.outcomes.single().anchors
        assertEquals((1..3).flatMap { p -> (1..9).map { p to it } }, anchors.map { it.pass to it.point })
        assertEquals(targets + targets + targets, anchors.map { it.anchor.x to it.anchor.y })
        assertEquals((1..3).flatMap { p -> List(9) { CalibrationPlan.positions[p - 1] } }, anchors.map { it.position })
    }

    @Test fun noFitOrOutcomeAfterNineOrEighteenAnchors() {
        val h = CalibrationHarness()
        for (n in listOf(9, 18)) {
            h.completeAnchors(n)
            assertEquals(n, h.controller.anchors.size)
            assertTrue(h.fits.isEmpty())
            assertTrue(h.outcomes.isEmpty())
            assertFalse(h.controller.closed)
            assertEquals(CalibrationBreak.PASS, h.controller.currentBreak)
            assertEquals(n / 9 + 1, h.controller.pass)
            assertEquals(1, h.controller.point)
        }
        h.runToEnd()
        assertEquals(27, h.fits.single().size)
    }

    @Test fun onlyTheFinalModelIsActivated() {
        val previous = GazeModel.fit(targets.map { Anchor(eyeFor(it), it.first, it.second) })!!
        TrackerState.model = previous
        val session = TrackerState.beginCalibration()
        val h = CalibrationHarness(onOutcome = { TrackerState.finishCalibration(session, it.model) })
        for (n in listOf(9, 18, 26)) {
            h.completeAnchors(n)
            assertSame(previous, TrackerState.model)
            assertTrue(TrackerState.collecting)
        }
        h.runToEnd()
        assertSame(h.outcomes.single().model, TrackerState.model)
        assertNotSame(previous, TrackerState.model)
        assertFalse(TrackerState.collecting)
    }

    @Test fun midPassBlinkBreaksAndTwoInterPassBreaks() {
        val h = CalibrationHarness()
        val starts = mutableListOf<Triple<CalibrationBreak, Int, Long>>()
        val titles = mutableListOf<String>()
        var previous: CalibrationBreak? = null
        h.runToEnd {
            val current = h.controller.currentBreak
            if (current != null && previous == null) {
                starts += Triple(current, h.controller.anchors.size, h.controller.breakEndsAt - h.now)
                val screen = h.controller.screen(h.now)!!
                assertNull(screen.target)
                titles += screen.title
                assertTrue(screen.center.last(), screen.center.last().startsWith(
                    if (current == CalibrationBreak.PASS) "3초 후 ${h.controller.pass}/3회차를 시작합니다" else "2초 후 나머지 5개를"))
            }
            previous = current
        }
        assertEquals(listOf(
            Triple(CalibrationBreak.BLINK, 4, 2_000L), Triple(CalibrationBreak.PASS, 9, 3_000L),
            Triple(CalibrationBreak.BLINK, 13, 2_000L), Triple(CalibrationBreak.PASS, 18, 3_000L),
            Triple(CalibrationBreak.BLINK, 22, 2_000L)), starts)
        assertEquals(listOf("1/3회차 · 4/9 완료 · 잠시 쉬어가기", "1/3회차 완료 · 잠시 쉬어가기",
            "2/3회차 · 4/9 완료 · 잠시 쉬어가기", "2/3회차 완료 · 잠시 쉬어가기",
            "3/3회차 · 4/9 완료 · 잠시 쉬어가기"), titles)
    }

    @Test fun framesDuringBreaksAreIgnoredEntirely() {
        val h = CalibrationHarness()
        for ((n, kind) in listOf(4 to CalibrationBreak.BLINK, 9 to CalibrationBreak.PASS)) {
            h.completeAnchors(n)
            val end = h.controller.breakEndsAt
            while (h.now + CalibrationHarness.FRAME_MS < end - 100) {
                h.step()
                assertEquals(kind, h.controller.currentBreak)
                assertEquals(0, h.controller.sampleCount)
                assertEquals(n, h.controller.anchors.size)
                assertNull(h.controller.screen(h.now)!!.target)
            }
            // A capture offered during the break was never consumed, so it is still new after the break.
            h.now = end - 100
            h.frame()
            h.now = end + 50
            h.frame(timestamp = end - 100)
            assertNull(h.controller.currentBreak)
            assertEquals("${h.controller.point} / 9 · 파란 점을 계속 바라보세요", h.controller.hint)
            assertEquals(0, h.controller.sampleCount) // settling restarted at end + 50
        }
    }

    @Test fun settlingClockRestartsWhenBreakEnds() {
        val h = CalibrationHarness()
        h.completeAnchors(4)
        val end = h.controller.breakEndsAt
        // The break is noticed 500 ms late by a tick, with no frames.
        h.now = end + 500
        assertTrue(h.controller.onTick())
        assertNull(h.controller.currentBreak)
        val restart = h.now
        while (h.now + CalibrationHarness.FRAME_MS < restart + 1_000) {
            h.now += CalibrationHarness.FRAME_MS
            h.frame()
            assertEquals(0, h.controller.sampleCount)
        }
        h.now = restart + 1_000
        h.frame()
        assertEquals(1, h.controller.sampleCount)
    }

    @Test fun timeoutClockRestartsWhenBreakEnds() {
        for (n in listOf(4, 9)) {
            val h = CalibrationHarness()
            h.completeAnchors(n)
            val end = h.controller.breakEndsAt
            h.now = end + 500
            assertTrue(h.controller.onTick())
            h.now = end + 500 + 9_000
            assertTrue(h.controller.onTick())
            assertTrue(h.outcomes.isEmpty())
            h.now += 1
            assertFalse(h.controller.onTick())
            val outcome = h.outcomes.single()
            assertEquals(CalibrationFailure.TIMEOUT, outcome.failure)
            assertEquals(n, outcome.anchors.size)
            assertNull(outcome.model)
            assertTrue(h.fits.isEmpty())
        }
    }

    @Test fun rejectsStaleFutureDuplicateAndNonTrackingSamples() {
        val h = CalibrationHarness()
        h.now += 1_000 // settled
        val f = eyeFor(targets[0])
        fun rejected() {
            assertEquals(waiting, h.controller.hint)
        }
        h.frame(f, timestamp = h.now - 251); rejected()
        h.frame(f, timestamp = h.now + 1); rejected()
        h.frame(null); rejected()
        h.controller.onFrame(null); rejected()
        for (q in listOf(TrackingQuality.CLOSED, TrackingQuality.LOST, TrackingQuality.UNCERTAIN, TrackingQuality.STALE)) {
            h.frame(f, quality = q); rejected()
        }
        assertEquals(0, h.controller.sampleCount)
        h.frame(f, timestamp = h.now - 250)
        assertEquals(1, h.controller.sampleCount)
        h.frame(f, timestamp = h.now - 250); rejected()
        assertEquals(1, h.controller.sampleCount)
        // The candidate's old-model rejection (OUT_OF_RANGE) does not block calibration samples.
        h.now += 66
        h.frame(f, quality = TrackingQuality.OUT_OF_RANGE)
        assertEquals(2, h.controller.sampleCount)
        h.frame(f, timestamp = h.now - 10); rejected()
        assertEquals(2, h.controller.sampleCount)
    }

    @Test fun pointNeedsSettlingTwentyTwoSamplesAndTwoAndAHalfSeconds() {
        val fast = CalibrationHarness()
        val start = fast.now
        for (t in 0L until 1_000L step 30) {
            fast.now = start + t; fast.frame()
            assertEquals(0, fast.controller.sampleCount)
        }
        // 22 samples arrive by 1,630 ms, but the point cannot complete before 2,500 ms.
        for (t in 1_000L until 2_500L step 30) {
            fast.now = start + t; fast.frame()
            assertTrue(fast.controller.anchors.isEmpty())
        }
        assertTrue(fast.controller.sampleCount >= 22)
        fast.now = start + 2_500
        fast.frame()
        assertEquals(1, fast.controller.anchors.size)

        val sparse = CalibrationHarness()
        sparse.now += 1_000
        repeat(21) { sparse.frame(); sparse.now += 200 }
        assertEquals(21, sparse.controller.sampleCount)
        assertTrue(sparse.controller.anchors.isEmpty())
        sparse.frame()
        assertEquals(1, sparse.controller.anchors.size)
    }

    @Test fun missingInputPausesSamplesWithoutRestartingThePoint() {
        val h = CalibrationHarness()
        val start = h.now
        h.runUntil { h.controller.sampleCount >= 10 }
        repeat(20) {
            h.now += 66; h.controller.onTick(); h.frame(quality = TrackingQuality.CLOSED)
            assertEquals(10, h.controller.sampleCount)
        }
        h.now += 66; h.controller.onFrame(null)
        assertEquals(waiting, h.controller.hint)
        h.now += 66; h.frame()
        assertEquals(11, h.controller.sampleCount)
        assertEquals(1, h.controller.point)
        // The gap did not restart the point's timeout clock.
        h.now = start + 9_000
        assertTrue(h.controller.onTick())
        h.now += 1
        assertFalse(h.controller.onTick())
        assertEquals(CalibrationFailure.TIMEOUT, h.outcomes.single().failure)
    }

    private fun assertMovementRetry(outlier: (EyeFeatures) -> EyeFeatures) {
        var frames = 0
        val h = CalibrationHarness(features = { _, t -> if (++frames == 20) outlier(eyeFor(t)) else eyeFor(t) })
        h.runUntil { h.controller.hint == moved }
        assertEquals(0, h.controller.sampleCount)
        assertTrue(h.controller.anchors.isEmpty())
        assertEquals(1 to 1, h.controller.pass to h.controller.point)
        val retryAt = h.now
        h.completeAnchors(1)
        assertTrue(h.now - retryAt >= 2_500)
        val anchor = h.controller.anchors.single()
        assertEquals(1 to 1, anchor.pass to anchor.point)
        assertEquals(eyeFor(targets[0]), anchor.anchor.eye)
    }

    @Test fun movementWithinAPointRestartsThatPoint() {
        assertMovementRetry { it.copy(faceX = it.faceX + .05) }
        assertMovementRetry { it.copy(faceY = it.faceY - .05) }
    }

    @Test fun noExtraFilterOnScaleOrBetweenAnchors() {
        var frames = 0
        val h = CalibrationHarness(features = { pass, t ->
            // Scale jitter within a point and a large face shift between passes are both accepted.
            eyeFor(t).copy(faceY = .5 + (pass - 2) * .1, scale = if (++frames % 2 == 0) .25 else .3)
        })
        h.runToEnd { assertNotEquals(moved, h.controller.hint) }
        assertEquals(27, h.outcomes.single().anchors.size)
    }

    @Test fun fitInputsAreTheGenuinePerPassMedians() {
        val features = { pass: Int, t: Pair<Double, Double> ->
            val base = eyeFor(t)
            base.copy(x = base.x + pass * .001, faceX = .5 + pass * .01, faceY = .5 + (pass - 2) * .06,
                scale = .25 + (pass - 2) * .02)
        }
        val h = CalibrationHarness(features = features)
        h.runToEnd()
        val inputs = h.fits.single()
        assertEquals(27, inputs.size)
        inputs.forEachIndexed { i, a -> assertEquals(features(i / 9 + 1, targets[i % 9]), a.eye) }
        assertNotNull(h.outcomes.single().model)
    }

    @Test fun rejectedFitIsReportedOnceWithoutAModel() {
        val h = CalibrationHarness(features = { _, _ -> EyeFeatures(0.0, 0.0, .5, .5, .25) })
        h.runToEnd()
        val outcome = h.outcomes.single()
        assertEquals(CalibrationFailure.FIT_REJECTED, outcome.failure)
        assertNull(outcome.model)
        assertEquals(27, h.fits.single().size)
    }

    @Test fun timeoutInALaterPassFailsWithoutFitting() {
        val h = CalibrationHarness()
        h.completeAnchors(12)
        val since = h.now
        h.now = since + 9_000
        assertTrue(h.controller.onTick())
        h.now += 1
        assertFalse(h.controller.onTick())
        val outcome = h.outcomes.single()
        assertEquals(CalibrationFailure.TIMEOUT, outcome.failure)
        assertEquals(12, outcome.anchors.size)
        assertNull(outcome.model)
        assertTrue(h.fits.isEmpty())
        repeat(300) { h.step() }
        assertEquals(1, h.outcomes.size)
    }

    @Test fun cancellationPreventsLaterCompletion() {
        for (n in listOf(9, 20)) {
            val h = CalibrationHarness()
            h.completeAnchors(n)
            h.controller.close()
            assertNull(h.controller.screen(h.now))
            assertFalse(h.controller.onTick())
            repeat(1_000) { h.step() }
            assertTrue(h.controller.anchors.isEmpty())
            assertEquals(0, h.controller.sampleCount)
            assertTrue(h.fits.isEmpty())
            assertTrue(h.outcomes.isEmpty())
        }
    }

    @Test fun cancelledFailedOrSupersededSessionsKeepThePreviousModel() {
        val previous = GazeModel.fit(targets.map { Anchor(eyeFor(it), it.first, it.second) })!!
        val replacement = GazeModel.fit(targets.map { Anchor(eyeFor(it).copy(x = eyeFor(it).x * 2), it.first, it.second) })!!
        TrackerState.model = previous
        val failed = TrackerState.beginCalibration()
        assertFalse(TrackerState.finishCalibration(failed, null))
        assertSame(previous, TrackerState.model)
        val cancelled = TrackerState.beginCalibration()
        TrackerState.cancelCalibration()
        assertFalse(TrackerState.finishCalibration(cancelled, replacement))
        assertSame(previous, TrackerState.model)
        val superseded = TrackerState.beginCalibration()
        val current = TrackerState.beginCalibration()
        assertFalse(TrackerState.finishCalibration(superseded, replacement))
        assertSame(previous, TrackerState.model)
        assertTrue(TrackerState.finishCalibration(current, replacement))
        assertSame(replacement, TrackerState.model)
    }
}
