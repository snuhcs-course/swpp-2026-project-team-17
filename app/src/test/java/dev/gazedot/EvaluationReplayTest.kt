package dev.gazedot

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Replay of SYNTHETIC sessions written by the real logger; see EvaluationReplay for scope. */
class EvaluationReplayTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun recorded(name: String, end: EvaluationEndReason = EvaluationEndReason.COMPLETED, stopAtMs: Long? = null,
                         mode: EvaluationMode = EvaluationMode.BLIND_MEASUREMENT): File {
        val dir = File(tmp.root, name)
        SyntheticEvaluation.session(dir, mode).also { it.open(); SyntheticEvaluation.simulate(it, end, stopAtMs) }
        return dir
    }
    private fun short(name: String) = recorded(name, EvaluationEndReason.BACK, 12_000)

    @Test fun replayReproducesEveryProcessedAttemptOfAllThreeBlocksExactly() {
        val r = EvaluationReplay.replay(recorded("full"))
        assertTrue(r.errors.toString() + r.mismatches.take(3), r.ok)
        assertTrue(r.parametersMatch)
        assertEquals(CalibrationPlan.ID, r.calibrationId)
        assertTrue(r.processedReplayed > 3_000)
        // session_start, three block starts, three delayed results, three stalls and three epoch resets.
        assertEquals(13, r.resetsApplied)
        assertEquals(mapOf("discarded_block_start" to 3, "discarded_delayed" to 3, "discarded_epoch" to 3), r.notProcessed)
    }

    @Test fun liveModeLogsWhereThePointerWasDrawn() {
        val dir = recorded("live", EvaluationEndReason.BACK, 12_000, EvaluationMode.LIVE_PREVIEW)
        assertTrue(EvaluationReplay.replay(dir).ok)
        val valid = EvaluationReplay.readCsv(File(dir, "attempts.csv")).filter { it["valid"] == "true" }
        assertTrue(valid.isNotEmpty() && valid.all { it["pointer_shown"] == "true" && it.getValue("rendered_x_px").isNotEmpty() })
        val blind = EvaluationReplay.readCsv(File(short("blind"), "attempts.csv"))
        assertTrue(blind.all { it["pointer_shown"] == "false" && it.getValue("rendered_x_px").isEmpty() })
    }

    @Test fun replayDependsOnLoggedResets() {
        val dir = recorded("context")
        val events = File(dir, "events.csv")
        val lines = events.readLines()
        // Dropping a block-start reset changes the smoothing context, so later points no longer reproduce.
        val i = lines.indexOfFirst { it.endsWith(",pipeline_reset,block_start") }
        events.writeText(lines.filterIndexed { j, _ -> j != i }.joinToString("\n", postfix = "\n"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok)
        assertTrue(r.errors.any { "main_seq" in it })
        assertTrue(r.mismatches.any { it.field.startsWith("smoothed") })
    }

    @Test fun replayDetectsAlteredFeatures() {
        val dir = short("tampered")
        val file = File(dir, "attempts.csv")
        val rows = file.readLines().toMutableList()
        val header = EvaluationCsv.ATTEMPT_HEADER
        val fx = header.indexOf("f_x"); val valid = header.indexOf("valid")
        val i = rows.indexOfFirst { it.split(",").getOrNull(valid) == "true" }
        val cells = rows[i].split(",").toMutableList()
        cells[fx] = (cells[fx].toDouble() + 0.01).toString()
        rows[i] = cells.joinToString(","); file.writeText(rows.joinToString("\n", postfix = "\n"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok)
        assertTrue(r.mismatches.any { it.field == "linear_x_norm" })
    }

    @Test fun replayDetectsAnAlteredMeasuredCoordinateEvenByOneUlp() {
        val dir = short("ulp")
        val file = File(dir, "attempts.csv")
        val header = EvaluationCsv.ATTEMPT_HEADER
        val sx = header.indexOf("smoothed_x_norm"); val phase = header.indexOf("phase"); val valid = header.indexOf("valid")
        val rows = file.readLines().toMutableList()
        val i = rows.indexOfFirst { it.split(",").let { c -> c.getOrNull(valid) == "true" && c.getOrNull(phase) == "measurement" } }
        val cells = rows[i].split(",").toMutableList()
        cells[sx] = Math.nextUp(cells[sx].toDouble()).toString()
        rows[i] = cells.joinToString(","); file.writeText(rows.joinToString("\n", postfix = "\n"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertEquals(listOf("smoothed_x_norm"), r.mismatches.map { it.field })
    }

    @Test fun replayRejectsAChangedModelSnapshot() {
        val dir = short("model")
        val snap = File(dir, "model_snapshot.json")
        val fit = SyntheticEvaluation.model().fitError.toString()
        snap.writeText(snap.readText().replace("\"fit_error\": $fit", "\"fit_error\": 0.5"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.parametersMatch); assertFalse(r.ok)
        assertTrue(r.parameterDifferences.single().startsWith("fit_error"))
    }

    @Test fun replayRejectsChangedOrReorderedCalibrationInputs() {
        val dir = short("inputs")
        val snap = File(dir, "model_snapshot.json")
        snap.writeText(snap.readText().replaceFirst("\"x\": 0.5,", "\"x\": 0.51,"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertTrue(r.errors.any { "calibration_inputs_sha256" in it })
        val relabelled = short("labels")
        val s2 = File(relabelled, "model_snapshot.json")
        s2.writeText(s2.readText().replaceFirst("\"pass\": 1,", "\"pass\": 2,"))
        val r2 = EvaluationReplay.replay(relabelled)
        assertFalse(r2.ok); assertTrue(r2.errors.any { "labelled pass 1..3" in it })
        // A pass relabelled with another calibration position (the fit inputs themselves unchanged).
        val moved = short("positions")
        val s3 = File(moved, "model_snapshot.json")
        s3.writeText(s3.readText().replaceFirst("\"calibration_position\": \"higher\"", "\"calibration_position\": \"lower\""))
        val r3 = EvaluationReplay.replay(moved)
        assertFalse(r3.ok); assertTrue(r3.errors.any { "calibration positions by pass" in it })
    }

    @Test fun replayRefusesAnotherCandidateOrPipeline() {
        for ((name, other) in listOf("sibling" to "calibration_a_three_pass_static", "nine" to "calibration_nine_point")) {
            val dir = short("candidate-$name")
            File(dir, "model_snapshot.json").let { it.writeText(it.readText().replace("\"calibration_id\": \"${CalibrationPlan.ID}\"",
                "\"calibration_id\": \"$other\"")) }
            assertTrue(name, EvaluationReplay.replay(dir).errors.any { "not this build's candidate" in it })
        }
        val variant = short("variant")
        File(variant, "metadata.json").let { it.writeText(it.readText().replace("\"variant\": \"${EvaluationProtocol.PIPELINE_VARIANT}\"",
            "\"variant\": \"home_screen_candidate\"")) }
        assertTrue(EvaluationReplay.replay(variant).errors.any { "pipeline variant" in it })
    }

    @Test fun replayRequiresTheSessionStartReset() {
        val dir = File(tmp.root, "nostart")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s)
        d.handle(d.accept(SyntheticEvaluation.START_MS + 10, EyeObservation(EyeState.LOST, null, 0.0, 0.0), EvaluationDetector.NO_FACE),
            SyntheticEvaluation.START_MS + 30)
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 100)
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertTrue(r.errors.any { "session_start" in it })
    }

    @Test fun aModelChangeEndsTheReplayableContext() {
        val dir = File(tmp.root, "changed")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s)
        val start = SyntheticEvaluation.START_MS
        d.evaluationReset(start - 100, EvaluationSession.RESET_SESSION_START)
        val f = SyntheticEvaluation.open(EyeFeatures(0.0, 0.0, .5, .5, .3))
        d.handle(d.accept(start + 10, f), start + 30)
        d.handle(d.accept(start + 80, f), start + 100, active = SyntheticEvaluation.model()) // another model object
        d.handle(d.accept(start + 150, f), start + 170) // cannot be replayed: processed with an unknown history
        s.finish(EvaluationEndReason.MODEL_CHANGED, start + 200)
        val rows = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        assertEquals(listOf("processed", "discarded_model_changed", "processed"), rows.map { it["outcome"] })
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertTrue(r.errors.any { "after a model change" in it })
    }

    @Test fun replayReportIsMachineReadable() {
        val report = EvaluationReplay.json(EvaluationReplay.replay(short("report")))
        val o = org.json.JSONObject(report)
        assertTrue(o.getBoolean("ok")); assertEquals("SYNTHETIC", o.getString("participant_id"))
        assertEquals(CalibrationPlan.ID, o.getString("calibration_id"))
        assertTrue(o.getString("scope").contains("GazePipeline.process")); assertTrue(o.getString("tolerance").startsWith("none"))
    }
}

/**
 * Replays real exported sessions when requested, e.g.
 * `gradlew :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/calibration-comparison/data/raw`.
 * Skipped otherwise. Writes one replay_report.json per session under gaze.replay.out.
 */
class EvaluationReplayRunner {
    @Test fun replayRecordedSessions() {
        val path = System.getProperty("gaze.replay.session")
        assumeTrue("set -PgazeReplaySession=<session or raw-data directory> to replay recorded sessions", !path.isNullOrBlank())
        val dirs = EvaluationReplay.discover(File(path!!))
        assertTrue("no session directories under $path", dirs.isNotEmpty())
        val out = File(System.getProperty("gaze.replay.out") ?: "build/gaze-replay")
        val failed = dirs.mapNotNull { dir ->
            val report = EvaluationReplay.replay(dir)
            File(File(File(out, report.calibrationId.ifEmpty { "unknown" }), report.participantId), report.sessionId).apply { mkdirs() }
                .resolve("replay_report.json").writeText(EvaluationReplay.json(report), Charsets.UTF_8)
            if (report.ok) null else "${report.participantId}/${report.sessionId}"
        }
        assertTrue("replay mismatches in $failed; see $out", failed.isEmpty())
    }
}

/**
 * Writes SYNTHETIC sessions in the phone's export layout for the end-to-end tool check, e.g.
 * `gradlew :app:testDebugUnitTest --tests dev.gazedot.SyntheticSessionWriter -PgazeSyntheticOut=<temporary dir>`.
 * Skipped otherwise. Never point it at docs/calibration-comparison/data.
 */
class SyntheticSessionWriter {
    @Test fun writeSyntheticExport() {
        val path = System.getProperty("gaze.synthetic.out")
        assumeTrue("set -PgazeSyntheticOut=<temporary directory> to write synthetic sessions", !path.isNullOrBlank())
        val root = File(path!!)
        val data = File(SyntheticEvaluation.repoRoot(), "docs/calibration-comparison/data").canonicalFile
        assertFalse("synthetic sessions must never be written into the repository's evaluation data", root.canonicalPath.startsWith(data.path))
        val sessions = File(root, "files/evaluation/calibration-comparison/SYNTHETIC")
        SyntheticEvaluation.session(File(sessions, "eval-synthetic-complete"), id = "eval-synthetic-complete", label = "B1")
            .also { it.open(); SyntheticEvaluation.simulate(it, EvaluationEndReason.COMPLETED) }
        SyntheticEvaluation.session(File(sessions, "eval-synthetic-back"), id = "eval-synthetic-back", label = "B2", order = EvaluationOrder.LHN)
            .also { it.open(); SyntheticEvaluation.simulate(it, EvaluationEndReason.BACK, stopAtMs = 120_000, seed = 11) }
    }
}
