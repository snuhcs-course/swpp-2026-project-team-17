package dev.gazedot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EvaluationProtocolTest {
    private val manifestFile = File(SyntheticEvaluation.repoRoot(), "docs/evaluation/iteration3/protocol.json")
    private val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))

    @Test fun kotlinProtocolEqualsManifestCases() {
        assertEquals(EvaluationProtocol.ID, manifest.getString("protocol_id"))
        assertEquals(EvaluationProtocol.VERSION, manifest.getString("protocol_version"))
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
    }

    @Test fun serpentineFirstPassAndReversedSecondPassWithUniqueCases() {
        assertEquals(listOf("r0c0", "r0c1", "r0c2", "r0c3", "r1c3", "r1c2", "r1c1", "r1c0",
            "r2c0", "r2c1", "r2c2", "r2c3", "r3c3", "r3c2", "r3c1", "r3c0"), EvaluationProtocol.firstPass.map { it.id })
        assertEquals(EvaluationProtocol.firstPass.reversed(), EvaluationProtocol.secondPass)
        assertEquals(32, EvaluationProtocol.presentations.map { it.caseId }.toSet().size)
        assertEquals(16, EvaluationProtocol.presentations.map { it.target }.toSet().size)
        val r1c3 = EvaluationProtocol.targets.single { it.id == "r1c3" }
        assertEquals(0.8, r1c3.x, 0.0); assertEquals(0.4, r1c3.y, 0.0)
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
        assertNull(run.presentationAt(90_000)); assertFalse(run.covers(90_000))
        assertFalse(run.isFinished(89_999)); assertTrue(run.isFinished(90_000))
        assertEquals(10_000L + 7 * 2500, run.onsetMs(EvaluationProtocol.presentations[7]))
    }

    @Test fun delayedResultsKeepTheTargetOfTheirAcceptanceTime() {
        val run = EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, 0, SyntheticEvaluation.GEOMETRY)
        val record = EvaluationAttemptRecord(0, 0, EvaluationOutcome.PROCESSED, 2_490, 2_700, EvaluationDetector.FACE,
            null, TrackingQuality.TRACKING, .2 to .2, .2 to .2, false, null, null, null, true)
        val row = EvaluationCsv.ATTEMPT_HEADER.zip(EvaluationCsv.attempt("s", run, record)).toMap()
        assertEquals("pass1-r0c0", row["case_id"]); assertEquals("measurement", row["phase"])
        assertEquals("2700", row["result_uptime_ms"])
    }

    @Test fun fullDisplayGeometryAndViewOffsets() {
        val g = EvaluationGeometry(1080, 2400, 0, 96, 1080, 2304)
        assertEquals(216.0 to 384.0, g.toView(.2, .2))
        assertEquals(864.0 to 1824.0, g.toView(.8, .8))
        // Inverse of the calibration basis: a view point normalized with its on-screen origin returns the target.
        val (vx, vy) = g.toView(.6, .4)
        val (nx, ny) = DisplayCoordinates(1080, 2400).normalize(vx + g.viewLeft, vy + g.viewTop)
        assertEquals(.6, nx, 1e-12); assertEquals(.4, ny, 1e-12)
        assertTrue(g.containsAllTargets(44.0))
        assertFalse(EvaluationGeometry(1080, 2400, 0, 0, 1080, 1000).containsAllTargets(44.0))
        assertFalse(EvaluationGeometry(1080, 2400, 900, 0, 180, 2400).containsAllTargets(0.0))
    }
}
