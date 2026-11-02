package dev.gazedot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EvaluationProtocolTest {
    @get:Rule val tmp = TemporaryFolder()
    private val manifestFile = File(SyntheticEvaluation.repoRoot(), "docs/evaluation/iteration3/protocol.json")
    private val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))

    @Test fun kotlinProtocolEqualsManifestCases() {
        assertEquals(EvaluationProtocol.ID, manifest.getString("protocol_id"))
        assertEquals(EvaluationProtocol.VERSION, manifest.getString("protocol_version"))
        assertEquals(EvaluationProtocol.PIPELINE_VARIANT, manifest.getJSONObject("pipeline").getString("variant"))
        assertTrue(manifest.getJSONObject("pipeline").getString("source_base").startsWith(EvaluationProtocol.SOURCE_BASE))
        val timing = manifest.getJSONObject("timing")
        assertEquals(EvaluationProtocol.TARGET_MS, timing.getLong("presentation_ms"))
        assertEquals(EvaluationProtocol.SETTLING_MS, timing.getLong("settling_ms"))
        assertEquals(EvaluationProtocol.MEASUREMENT_MS, timing.getLong("measurement_ms"))
        assertEquals(EvaluationProtocol.TOTAL_MS, timing.getLong("total_ms"))
        assertEquals(80_000L, EvaluationProtocol.TOTAL_MS)
        val grid = timing.getJSONArray("grid_norm")
        assertEquals(EvaluationProtocol.GRID, (0 until grid.length()).map { grid.getDouble(it) })
        val cases = manifest.getJSONArray("cases")
        assertEquals(32, cases.length())
        EvaluationProtocol.presentations.forEachIndexed { i, p ->
            val c = cases.getJSONObject(i)
            assertEquals(p.caseId, c.getString("case_id"))
            assertEquals(p.trialIndex, c.getInt("trial_index"))
            assertEquals(p.pass, c.getInt("pass_number"))
            assertEquals(p.indexInPass, c.getInt("index_in_pass"))
            assertEquals(p.target.id, c.getString("target_id"))
            assertEquals(p.target.x, c.getDouble("target_x_norm"), 0.0)
            assertEquals(p.target.y, c.getDouble("target_y_norm"), 0.0)
            assertEquals(i * 2500L, c.getLong("onset_ms"))
            assertEquals(i * 2500L + 1000, c.getLong("measurement_start_ms"))
            assertEquals(i * 2500L + 2500, c.getLong("end_ms"))
        }
    }

    @Test fun buildRecordsHashOfThisManifestWithLineEndingsNormalized() {
        val normalized = manifestFile.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        assertEquals(EvaluationChecksums.sha256(normalized), BuildConfig.EVAL_PROTOCOL_SHA256)
    }

    @Test fun predeclaredThresholdsAreUnchanged() {
        val criteria = manifest.getJSONObject("performance_criteria")
        val success = criteria.getJSONObject("successful_presentation")
        assertEquals(0.10, success.getDouble("max_mean_error_norm"), 0.0)
        assertEquals(0.80, success.getDouble("min_valid_coverage"), 0.0)
        assertEquals(10, success.getInt("min_valid_samples"))
        assertTrue(success.getBoolean("requires_complete_presentation"))
        val session = criteria.getJSONArray("session_criteria")
        val expected = listOf(
            Triple("successful_presentation_rate", ">=", 0.80), Triple("p95_presentation_error", "<=", 0.20),
            Triple("mean_jitter_rms", "<=", 0.05), Triple("tracking_availability", ">=", 0.90),
            Triple("valid_sample_rate", ">=", 10.0), Triple("p95_processing_latency", "<=", 200.0),
        )
        assertEquals(expected, (0 until session.length()).map { i ->
            session.getJSONObject(i).let { Triple(it.getString("id"), it.getString("comparison"), it.getDouble("threshold")) }
        })
        val sufficiency = manifest.getJSONObject("data_sufficiency")
        assertEquals(10, sufficiency.getInt("min_valid_samples"))
        assertEquals(0.5, sufficiency.getDouble("min_valid_coverage"), 0.0)
        assertEquals(0.8, sufficiency.getDouble("min_eligible_fraction_for_session_spatial_criteria"), 0.0)
    }

    @Test fun manifestDescribesTheCandidateStagesHonestly() {
        val coordinates = manifest.getJSONObject("coordinates")
        assertTrue(coordinates.getString("smoothed_output").contains("not an unclamped raw output"))
        assertTrue(coordinates.getString("predicted_output").contains("clamped"))
        assertFalse(coordinates.has("raw_output")); assertFalse(coordinates.has("final_output"))
        val history = manifest.getJSONArray("version_history")
        assertEquals("1.0.0", history.getJSONObject(0).getString("version")); assertEquals(2, history.getJSONObject(0).getInt("log_schema"))
        assertEquals("2.0.0", history.getJSONObject(1).getString("version"))
        assertEquals(EVALUATION_SCHEMA_VERSION, history.getJSONObject(1).getInt("log_schema"))
    }

    @Test fun serpentineFirstPassAndReversedSecondPassWithUniqueCases() {
        assertEquals(listOf("r0c0", "r0c1", "r0c2", "r0c3", "r1c3", "r1c2", "r1c1", "r1c0",
            "r2c0", "r2c1", "r2c2", "r2c3", "r3c3", "r3c2", "r3c1", "r3c0"), EvaluationProtocol.firstPass.map { it.id })
        assertEquals(EvaluationProtocol.firstPass.reversed(), EvaluationProtocol.secondPass)
        assertEquals(32, EvaluationProtocol.presentations.map { it.caseId }.toSet().size)
        assertEquals(16, EvaluationProtocol.presentations.map { it.target }.toSet().size)
    }

    @Test fun phaseBoundariesFollowAcceptanceTime() {
        val run = EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, 10_000, SyntheticEvaluation.GEOMETRY)
        assertNull(run.presentationAt(9_999)); assertNull(run.phaseAt(9_999))
        assertEquals(0, run.presentationAt(10_000)!!.trialIndex)
        assertEquals(EvaluationPhase.SETTLING, run.phaseAt(10_000))
        assertEquals(EvaluationPhase.SETTLING, run.phaseAt(10_999))
        assertEquals(EvaluationPhase.MEASUREMENT, run.phaseAt(11_000))
        assertEquals(EvaluationPhase.MEASUREMENT, run.phaseAt(12_499))
        assertEquals(1, run.presentationAt(12_500)!!.trialIndex)
        assertEquals(EvaluationPhase.SETTLING, run.phaseAt(12_500))
        assertEquals(31, run.presentationAt(89_999)!!.trialIndex)
        assertNull(run.presentationAt(90_000)); assertFalse(run.covers(90_000)); assertEquals(90_000L, run.endMs)
        assertFalse(run.isFinished(89_999)); assertTrue(run.isFinished(90_000))
    }

    @Test fun delayedResultsKeepTheWindowOfTheirAcceptanceTime() {
        val dir = File(tmp.root, "late")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s)
        d.reset(SyntheticEvaluation.START_MS - 100, "run_start")
        val start = SyntheticEvaluation.START_MS
        val (ex, ey) = SyntheticEvaluation.eye(.2, .2)
        // Accepted 1 ms before the end of presentation 0's measurement window, handled in presentation 1.
        d.handle(d.accept(start + 2_499, EyeFeatures(ex, ey, .5, .5, .3)), start + 2_700)
        // Accepted in the last settling millisecond, handled in the measurement window.
        d.handle(d.accept(start + 3_499, EyeFeatures(ex, ey, .5, .5, .3)), start + 3_560)
        s.finish(EvaluationEndReason.BACK, start + 4_000)
        val rows = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        assertEquals(listOf("pass1-r0c0/measurement", "pass1-r0c1/settling"), rows.map { "${it["case_id"]}/${it["phase"]}" })
        assertEquals((start + 2_700).toString(), rows[0]["result_uptime_ms"])
    }

    @Test fun framesAcceptedOutsideTheRunAreNeverItsAttempts() {
        val dir = File(tmp.root, "edges")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s)
        val start = SyntheticEvaluation.START_MS
        d.reset(start - 100, "run_start")
        val f = EyeFeatures(0.0, 0.0, .5, .5, .3)
        assertNull("pre-run results are dropped before smoothing", d.handle(d.accept(start - 1, f), start + 20))
        assertNull(d.smoother.value)
        d.handle(d.accept(start, f), start + 20)
        val after = d.accept(s.run.endMs, f)
        assertNull(after.attempt)
        assertNotNull("after the planned end the app behaves normally again", d.handle(after, s.run.endMs + 20))
        s.finish(EvaluationEndReason.COMPLETED, s.run.endMs)
        val rows = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        assertEquals(listOf(start.toString()), rows.map { it["accepted_uptime_ms"] })
        assertTrue(EvaluationReplay.replay(dir).ok)
    }

    @Test fun targetsCalibrationAndPredictionShareOneBasisIncludingViewOffsets() {
        // An evaluation view below a 96 px inset, as when the window does not draw behind the status bar.
        val g = EvaluationGeometry(1080, 2400, 0, 96, 1080, 2304)
        assertEquals(216.0 to 384.0, g.toView(.2, .2))   // y: 0.2 * 2400 - 96
        assertEquals(864.0 to 1824.0, g.toView(.8, .8))  // y: 0.8 * 2400 - 96
        // The calibration screen normalizes a view point as (location + view coordinate) / basis: that returns the target.
        for (t in EvaluationProtocol.targets) {
            val (vx, vy) = g.toView(t.x, t.y)
            assertEquals(t.x, (g.viewLeft + vx) / g.displayWidth, 1e-12)
            assertEquals(t.y, (g.viewTop + vy) / g.displayHeight, 1e-12)
        }
        assertTrue(g.containsAllTargets(44.0))
        assertFalse(EvaluationGeometry(1080, 2400, 0, 0, 1080, 1000).containsAllTargets(44.0))
        assertFalse(EvaluationGeometry(1080, 2400, 900, 0, 180, 2400).containsAllTargets(0.0))
        // A model calibrated on this basis predicts each target from the features of looking at it, up to the shrinkage
        // of the candidate's ridge penalty (0.03), which pulls predictions slightly toward the screen centre.
        val model = SyntheticEvaluation.model()
        for (t in EvaluationProtocol.targets) {
            val (ex, ey) = SyntheticEvaluation.eye(t.x, t.y)
            val p = model.predict(EyeFeatures(ex, ey, .5, .5, .3))!!
            assertEquals(t.x, p.first, .005); assertEquals(t.y, p.second, .005)
            assertTrue("shrinkage is toward the centre", Math.abs(p.first - .5) <= Math.abs(t.x - .5) + 1e-12)
        }
        // The overlay draws a point at origin + p * view size (float arithmetic), the basis position when it covers the basis.
        assertEquals(.6 * 1080, SyntheticEvaluation.rendered(.6 to .4).first, 1e-3)
        assertEquals(.4 * 2400, SyntheticEvaluation.rendered(.6 to .4).second, 1e-3)
    }
}
