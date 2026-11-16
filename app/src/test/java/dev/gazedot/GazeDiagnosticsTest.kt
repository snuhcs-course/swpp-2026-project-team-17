package dev.gazedot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GazeDiagnosticsTest {
    /** Face height per pass (ordinary, higher, lower), as a three-position calibration produces it. */
    private val faceY = listOf(.50, .42, .64)

    private fun threePositionOutcome(): CalibrationOutcome {
        val harness = CalibrationHarness(features = { pass, target -> eyeFor(target).copy(faceY = faceY[pass - 1]) })
        harness.runToEnd()
        return harness.outcomes.single()
    }

    @Test
    fun calibrationLinesListEveryAnchorExactlyAndEachPassRelativeToTheModelReference() {
        val outcome = threePositionOutcome()
        assertNotNull(outcome.model)
        val model = outcome.model!!
        val lines = GazeDiagnostics.calibrationLines(outcome, saved = true)

        assertTrue(lines[0], lines[0].startsWith("calibration finished id=${CalibrationPlan.ID} failure=none model=accepted saved=true anchors=27/27"))
        val anchorLines = lines.filter { it.startsWith("anchor ") }
        assertEquals(27, anchorLines.size)
        outcome.anchors.zip(anchorLines).forEach { (a, line) ->
            val e = a.anchor.eye
            assertTrue(line, line.startsWith("anchor pass=${a.pass} point=${a.point} pos=${a.position.id} fx=${e.x} fy=${e.y} " +
                "face_x=${e.faceX} face_y=${e.faceY} scale=${e.scale} tx=${a.anchor.x} ty=${a.anchor.y}"))
        }
        // The mean of all anchors is exactly the reference the fitted model's validity check uses.
        val mean = outcome.anchors.map { it.anchor.eye.faceY }.average()
        assertEquals(model.parameters().referenceFaceY, mean, 0.0)
        val lower = lines.single { it.startsWith("pass 3 ") }
        assertTrue(lower, lower.startsWith("pass 3 pos=lower n=9 "))
        assertTrue(lower, "face_y_minus_mean=${faceY[2] - mean} " in lower)
        assertTrue(lines.last(), lines.last().startsWith("model fit_error=${model.fitError} "))
    }

    @Test
    fun aRejectedFitIsReportedWithItsAnchorsAndNoModel() {
        val harness = CalibrationHarness(fitter = { null })
        harness.runToEnd()
        val lines = GazeDiagnostics.calibrationLines(harness.outcomes.single(), saved = false)
        assertTrue(lines[0], lines[0].startsWith("calibration finished id=${CalibrationPlan.ID} failure=FIT_REJECTED model=none saved=false anchors=27/27"))
        assertEquals(27, lines.count { it.startsWith("anchor ") })
        assertFalse(lines.any { it.startsWith("model ") })
    }

    @Test
    fun liveLineNamesTheUnchangedValidityRejectionAndTheFaceOffset() {
        val model = threePositionOutcome().model!!
        val reference = model.parameters()
        val lowerFace = eyeFor(.5 to .5).copy(faceY = reference.referenceFaceY + .15)
        assertEquals("face_y_shift_gt_0.12", model.rejection(lowerFace))
        assertEquals(null, model.predict(lowerFace))
        val sample = GazeSample(1_000L, TrackingQuality.OUT_OF_RANGE, lowerFace, null, null, 0f, emptyList())
        val line = GazeDiagnostics.liveLine(sample, 12L, EyeState.OPEN, model, pointerVisible = false, actionsEnabled = false,
            collecting = false, evaluation = null, interactionAllowed = false, listening = true)
        assertTrue(line, line.startsWith("live q=OUT_OF_RANGE eye=OPEN age_ms=12 model=yes rejection=face_y_shift_gt_0.12 "))
        assertTrue(line, " dfy=${lowerFace.faceY - reference.referenceFaceY} " in line)
        assertTrue(line, line.endsWith("point=no pointer_visible=false collecting=false evaluation=none actions=false " +
            "interaction_allowed=false screen_listening=true"))

        val noModel = GazeDiagnostics.liveLine(sample, 12L, EyeState.OPEN, null, false, false, false, null, false, true)
        assertTrue(noModel, "model=no rejection=no_model point=no" in noModel)
    }

    @Test
    fun liveAndTutorialLinesAreRateLimitedPerSource() {
        assertTrue(GazeDiagnostics.due("test-source", 5_000L))
        assertFalse(GazeDiagnostics.due("test-source", 5_999L))
        assertTrue(GazeDiagnostics.due("other-source", 5_999L))
        assertTrue(GazeDiagnostics.due("test-source", 6_000L))
    }
}
