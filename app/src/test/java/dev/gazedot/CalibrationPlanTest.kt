package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

/** Candidate B: ordinary, slightly higher and slightly lower head positions, one pass each. */
class CalibrationPlanTest {
    @Test fun identifiesCandidateB() {
        assertEquals("calibration_b_three_pass_positions", CalibrationPlan.ID)
        val h = CalibrationHarness()
        h.runToEnd()
        assertEquals("calibration_b_three_pass_positions", h.outcomes.single().calibrationId)
    }

    @Test fun passesUseOrdinaryHigherAndLowerPositions() {
        assertEquals(listOf(HeadPosition.ORDINARY, HeadPosition.HIGHER, HeadPosition.LOWER), CalibrationPlan.positions)
        assertEquals(listOf("ordinary", "higher", "lower"), CalibrationPlan.positions.map { it.id })
        val h = CalibrationHarness()
        h.runToEnd()
        val byPass = h.outcomes.single().anchors.groupBy({ it.pass }, { it.position })
        assertEquals(mapOf(1 to List(9) { HeadPosition.ORDINARY }, 2 to List(9) { HeadPosition.HIGHER },
            3 to List(9) { HeadPosition.LOWER }), byPass)
    }

    @Test fun requiredPositionIsShownThroughoutEachPass() {
        val h = CalibrationHarness()
        val collecting = mutableListOf<Pair<Int, String>>()
        val blink = mutableListOf<Pair<Int, CalibrationScreen>>()
        fun record() {
            val screen = h.controller.screen(h.now) ?: return
            when (h.controller.currentBreak) {
                null -> collecting += h.controller.pass to screen.position
                CalibrationBreak.BLINK -> blink += h.controller.pass to screen
                CalibrationBreak.PASS -> Unit
            }
        }
        record()
        h.runToEnd(::record)
        assertEquals(setOf(1, 2, 3), collecting.map { it.first }.toSet())
        val shown = mapOf(1 to "자세: 기본 위치 · 정면을 보고 그대로 유지",
            2 to "자세: 조금 높은 위치 · 정면을 보고 그대로 유지", 3 to "자세: 조금 낮은 위치 · 정면을 보고 그대로 유지")
        collecting.forEach { (pass, position) -> assertEquals(shown.getValue(pass), position) }
        // The mid-pass blink break keeps the current pass's position.
        assertEquals(setOf(1, 2, 3), blink.map { it.first }.toSet())
        blink.forEach { (pass, screen) ->
            assertEquals(shown.getValue(pass), screen.position)
            assertEquals(CalibrationPlan.positions[pass - 1].holdInstruction, screen.footer.first())
        }
    }

    @Test fun movementIsRequestedOnlyDuringInterPassBreaks() {
        val h = CalibrationHarness()
        val passBreaks = linkedMapOf<Int, CalibrationScreen>()
        val others = mutableListOf<CalibrationScreen>()
        fun record() {
            val screen = h.controller.screen(h.now) ?: return
            if (h.controller.currentBreak == CalibrationBreak.PASS) passBreaks.putIfAbsent(h.controller.pass, screen)
            else others += screen
        }
        record()
        h.runToEnd(::record)
        assertEquals(listOf(2, 3), passBreaks.keys.toList())
        val toHigher = passBreaks.getValue(2)
        assertEquals("1/3회차 완료 · 잠시 쉬어가기", toHigher.title)
        assertEquals("다음 자세: 조금 높은 위치 · 정면을 보고 그대로 유지", toHigher.position)
        assertEquals(listOf("머리를 조금 위로 옮겨 주세요", "기본 위치보다 살짝 높게 · 정면 유지",
            "3초 후 2/3회차를 시작합니다"), toHigher.center)
        val toLower = passBreaks.getValue(3)
        assertEquals("2/3회차 완료 · 잠시 쉬어가기", toLower.title)
        assertEquals("다음 자세: 조금 낮은 위치 · 정면을 보고 그대로 유지", toLower.position)
        assertEquals(listOf("머리를 조금 아래로 옮겨 주세요", "기본 위치보다 살짝 낮게 · 정면 유지",
            "3초 후 3/3회차를 시작합니다"), toLower.center)
        (passBreaks.values).forEach {
            assertEquals("기기는 움직이지 마세요 · 눈은 편하게 깜빡이세요", it.footer.first())
            assertNull(it.target)
        }
        // While targets are collected, and during blink breaks, the participant is never asked to move.
        assertTrue(others.isNotEmpty())
        assertTrue(others.none { s -> (listOf(s.title, s.position) + s.center + s.footer).any { it.contains("옮겨") } })
    }

    @Test fun genuineFeaturesFromEachPositionReachTheFit() {
        // Simulated higher and lower heads: the face moves in the image and its scale changes between passes.
        val features = { pass: Int, t: Pair<Double, Double> ->
            when (CalibrationPlan.positions[pass - 1]) {
                HeadPosition.ORDINARY -> eyeFor(t)
                HeadPosition.HIGHER -> eyeFor(t).copy(y = eyeFor(t).y + .01, faceY = .42, scale = .26)
                HeadPosition.LOWER -> eyeFor(t).copy(y = eyeFor(t).y - .01, faceY = .58, scale = .24)
            }
        }
        val h = CalibrationHarness(features = features)
        h.runToEnd()
        val outcome = h.outcomes.single()
        assertEquals(outcome.anchors.map { features(it.pass, CalibrationRules.TARGETS[it.point - 1]) },
            h.fits.single().map { it.eye })
        assertEquals(listOf(.5, .42, .58), outcome.anchors.map { it.anchor.eye.faceY }.distinct())
    }
}
