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

    private fun recorded(name: String, durationMs: Long = EvaluationProtocol.TOTAL_MS,
                         end: EvaluationEndReason = EvaluationEndReason.COMPLETED,
                         mode: EvaluationMode = EvaluationMode.BLIND_MEASUREMENT): File {
        val dir = File(tmp.root, name)
        SyntheticEvaluation.session(dir, mode).also { it.open(); SyntheticEvaluation.simulate(it, durationMs, 66, end) }
        return dir
    }

    @Test fun replayReproducesEveryProcessedAttemptExactly() {
        val r = EvaluationReplay.replay(recorded("full"))
        assertTrue(r.errors.toString() + r.mismatches.take(3), r.ok)
        assertTrue(r.parametersMatch)
        assertTrue(r.processedReplayed > 1_000)
        assertEquals(3, r.resetsApplied) // run_start and two watchdog resets
        assertEquals(emptyMap<String, Int>(), r.notProcessed)
    }

    @Test fun liveModeLogsWhereThePointerWasDrawn() {
        val dir = recorded("live", 7_600, EvaluationEndReason.BACK, EvaluationMode.LIVE_PREVIEW)
        assertTrue(EvaluationReplay.replay(dir).ok)
        val valid = EvaluationReplay.readCsv(File(dir, "attempts.csv")).filter { it["valid"] == "true" }
        assertTrue(valid.all { it["pointer_shown"] == "true" && it.getValue("rendered_x_px").isNotEmpty() })
        val blind = EvaluationReplay.readCsv(File(recorded("blind", 7_600, EvaluationEndReason.BACK), "attempts.csv"))
        assertTrue(blind.all { it["pointer_shown"] == "false" && it.getValue("rendered_x_px").isEmpty() })
    }

    @Test fun replayDependsOnLoggedResets() {
        val dir = recorded("context")
        val events = File(dir, "events.csv")
        val lines = events.readLines()
        // Dropping the first watchdog reset changes the smoothing context, so later points no longer reproduce.
        val i = lines.indexOfFirst { it.contains("watchdog_no_result") }
        events.writeText(lines.filterIndexed { j, _ -> j != i }.joinToString("\n", postfix = "\n"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok)
        assertTrue(r.errors.any { "main_seq" in it })
        assertTrue(r.mismatches.any { it.field.startsWith("smoothed") })
    }

    @Test fun replayDetectsAlteredFeatures() {
        val dir = recorded("tampered")
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
        val dir = recorded("ulp", 7_600, EvaluationEndReason.BACK)
        val file = File(dir, "attempts.csv")
        val header = EvaluationCsv.ATTEMPT_HEADER
        val sx = header.indexOf("smoothed_x_norm"); val valid = header.indexOf("valid")
        val rows = file.readLines().toMutableList()
        val i = rows.indexOfFirst { it.split(",").getOrNull(valid) == "true" }
        val cells = rows[i].split(",").toMutableList()
        cells[sx] = Math.nextUp(cells[sx].toDouble()).toString()
        rows[i] = cells.joinToString(","); file.writeText(rows.joinToString("\n", postfix = "\n"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertEquals(listOf("smoothed_x_norm"), r.mismatches.map { it.field })
    }

    @Test fun replayRejectsAChangedModelSnapshot() {
        val dir = recorded("model", durationMs = 7_600, end = EvaluationEndReason.BACK)
        val snap = File(dir, "model_snapshot.json")
        val fit = SyntheticEvaluation.model().fitError.toString()
        snap.writeText(snap.readText().replace("\"fit_error\": $fit", "\"fit_error\": 0.5"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.parametersMatch); assertFalse(r.ok)
        assertTrue(r.parameterDifferences.single().startsWith("fit_error"))
    }

    @Test fun replayRejectsChangedCalibrationInputs() {
        val dir = recorded("inputs", durationMs = 7_600, end = EvaluationEndReason.BACK)
        val snap = File(dir, "model_snapshot.json")
        snap.writeText(snap.readText().replaceFirst("\"x\": 0.5", "\"x\": 0.51"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertTrue(r.errors.any { "calibration_inputs_sha256" in it })
    }

    @Test fun replayRequiresTheRunStartReset() {
        val dir = File(tmp.root, "nostart")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s)
        d.handle(d.accept(SyntheticEvaluation.START_MS + 10, null, EvaluationDetector.NO_FACE), SyntheticEvaluation.START_MS + 30)
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 100)
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertTrue(r.errors.any { "run_start" in it })
    }

    @Test fun aModelChangeEndsTheReplayableContext() {
        val dir = File(tmp.root, "changed")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s)
        val start = SyntheticEvaluation.START_MS
        d.reset(start - 100, "run_start")
        val f = EyeFeatures(0.0, 0.0, .5, .5, .3)
        d.handle(d.accept(start + 10, f), start + 30)
        d.handle(d.accept(start + 80, f), start + 100, active = SyntheticEvaluation.model()) // another model object
        d.handle(d.accept(start + 150, f), start + 170) // cannot be replayed: smoothed with an unknown history
        s.finish(EvaluationEndReason.MODEL_CHANGED, start + 200)
        val rows = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        assertEquals(listOf("processed", "discarded_model_changed", "processed"), rows.map { it["outcome"] })
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertTrue(r.errors.any { "after a model change" in it })
    }

    @Test fun replayRefusesOtherPipelineVariants() {
        val dir = recorded("variant", 7_600, EvaluationEndReason.BACK)
        val meta = File(dir, "metadata.json")
        meta.writeText(meta.readText().replace("\"variant\": \"home_screen_candidate\"", "\"variant\": \"integrated_main\""))
        assertTrue(EvaluationReplay.replay(dir).errors.any { "pipeline variant" in it })
    }

    @Test fun replayReportIsMachineReadable() {
        val report = EvaluationReplay.json(EvaluationReplay.replay(recorded("report", 7_600, EvaluationEndReason.BACK)))
        val o = org.json.JSONObject(report)
        assertTrue(o.getBoolean("ok")); assertEquals("SYNTHETIC", o.getString("participant_id"))
        assertTrue(o.getString("scope").contains("not camera")); assertTrue(o.getString("tolerance").startsWith("none"))
    }
}

/**
 * Replays real exported sessions when requested, e.g.
 * `gradlew :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/evaluation/iteration3/data/raw`.
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
            File(File(out, report.participantId), report.sessionId).apply { mkdirs() }
                .resolve("replay_report.json").writeText(EvaluationReplay.json(report), Charsets.UTF_8)
            if (report.ok) null else "${report.participantId}/${report.sessionId}"
        }
        assertTrue("replay mismatches in $failed; see $out", failed.isEmpty())
    }
}

/**
 * Writes SYNTHETIC sessions in the phone's export layout for the end-to-end tool check, e.g.
 * `gradlew :app:testDebugUnitTest --tests dev.gazedot.SyntheticSessionWriter -PgazeSyntheticOut=<temporary dir>`.
 * Skipped otherwise. Never point it at docs/evaluation/iteration3/data/raw.
 */
class SyntheticSessionWriter {
    @Test fun writeSyntheticExport() {
        val path = System.getProperty("gaze.synthetic.out")
        assumeTrue("set -PgazeSyntheticOut=<temporary directory> to write synthetic sessions", !path.isNullOrBlank())
        val root = File(path!!)
        val raw = File(SyntheticEvaluation.repoRoot(), "docs/evaluation/iteration3/data").canonicalFile
        assertFalse("synthetic sessions must never be written into the repository's evaluation data", root.canonicalPath.startsWith(raw.path))
        val iter3 = File(root, "files/evaluation/iter3/SYNTHETIC")
        SyntheticEvaluation.session(File(iter3, "eval-synthetic-complete"), id = "eval-synthetic-complete", label = "S1")
            .also { it.open(); SyntheticEvaluation.simulate(it, EvaluationProtocol.TOTAL_MS, 66, EvaluationEndReason.COMPLETED) }
        SyntheticEvaluation.session(File(iter3, "eval-synthetic-back"), id = "eval-synthetic-back", label = "S2")
            .also { it.open(); SyntheticEvaluation.simulate(it, 30_000, 66, EvaluationEndReason.BACK, seed = 11) }
    }
}
