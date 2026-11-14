package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

/** Candidate A: three passes in one head position. */
class CalibrationPlanTest {
    @Test fun identifiesCandidateA() {
        assertEquals("calibration_a_three_pass_static", CalibrationPlan.ID)
        val h = CalibrationHarness()
        h.runToEnd()
        assertEquals("calibration_a_three_pass_static", h.outcomes.single().calibrationId)
    }

    @Test fun everyPassUsesTheSameHeadPosition() {
        assertEquals(List(3) { HeadPosition.SAME }, CalibrationPlan.positions)
        val h = CalibrationHarness()
        h.runToEnd()
        assertTrue(h.outcomes.single().anchors.all { it.position == HeadPosition.SAME })
    }

    @Test fun everyScreenAsksToKeepTheSamePosition() {
        val h = CalibrationHarness()
        val screens = mutableListOf<Pair<CalibrationBreak?, CalibrationScreen>>()
        fun record() { h.controller.screen(h.now)?.let { screens += h.controller.currentBreak to it } }
        record()
        h.runToEnd(::record)
        screens.forEach { (kind, s) ->
            val prefix = if (kind == CalibrationBreak.PASS) "다음 자세: " else "자세: "
            assertEquals(prefix + "처음 자세 그대로 · 정면, 같은 거리", s.position)
        }
        val passBreaks = screens.filter { it.first == CalibrationBreak.PASS }.map { it.second }
        assertTrue(passBreaks.isNotEmpty())
        passBreaks.forEach {
            assertTrue(it.center.contains("같은 자세와 거리를 그대로 유지해 주세요"))
            assertTrue(it.center.contains("눈을 편하게 깜빡여 주세요"))
            assertEquals("머리와 기기는 그대로 유지해 주세요", it.footer.first())
        }
        screens.filter { it.first == CalibrationBreak.BLINK }.forEach {
            assertEquals("머리와 기기는 그대로 유지해 주세요", it.second.footer.first())
        }
        // Candidate A never asks for a change of head position.
        assertTrue(screens.none { (_, s) -> (listOf(s.title, s.position) + s.center + s.footer).any { it.contains("옮겨") } })
    }
}
