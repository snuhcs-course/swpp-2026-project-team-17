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
                         end: EvaluationEndReason = EvaluationEndReason.COMPLETED): File {
        val dir = File(tmp.root, name)
        SyntheticEvaluation.session(dir).also { it.open(); SyntheticEvaluation.simulate(it, durationMs, 50, end) }
        return dir
    }

    @Test fun replayReproducesEveryProcessedAttemptExactly() {
        val r = EvaluationReplay.replay(recorded("full"))
        assertTrue(r.errors.toString(), r.ok)
        assertTrue(r.parametersMatch)
        assertTrue(r.processedReplayed > 1_000)
        assertEquals(3, r.resetsApplied)
        assertEquals(mapOf("discarded_epoch" to 1, "stale_delivery" to 1), r.notProcessed)
        assertTrue(r.mismatches.isEmpty())
    }

    @Test fun replayDependsOnLoggedResetsAndFilterContext() {
        val dir = recorded("context")
        val events = File(dir, "events.csv")
        val lines = events.readLines()
        // Dropping the watchdog reset changes the filter context, so later points no longer reproduce.
        events.writeText((lines.filterNot { it.contains("watchdog_no_result") }).joinToString("\n", postfix = "\n"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok)
        assertTrue(r.errors.any { "main_seq" in it } || r.mismatches.isNotEmpty())
    }

    @Test fun replayDetectsAlteredInputs() {
        val dir = recorded("tampered")
        val file = File(dir, "attempts.csv")
        val rows = file.readLines().toMutableList()
        val header = EvaluationReplay.readCsv(file).let { EvaluationCsv.ATTEMPT_HEADER }
        val fx = header.indexOf("f_x")
        val i = rows.indexOfFirst { it.split(",").getOrNull(header.indexOf("valid")) == "true" && it.split(",")[fx].isNotEmpty() }
        val cells = rows[i].split(",").toMutableList()
        cells[fx] = (cells[fx].toDouble() + 0.01).toString()
        rows[i] = cells.joinToString(","); file.writeText(rows.joinToString("\n", postfix = "\n"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok)
        assertTrue(r.mismatches.any { it.field == "raw_x_norm" })
    }

    @Test fun replayRejectsAChangedModelSnapshot() {
        val dir = recorded("model", durationMs = 7_600, end = EvaluationEndReason.BACK)
        val snap = File(dir, "model_snapshot.json")
        val text = snap.readText()
        val fit = SyntheticEvaluation.model().fitError.toString()
        snap.writeText(text.replace("\"fit_error\": $fit", "\"fit_error\": 0.5"))
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.parametersMatch); assertFalse(r.ok)
    }

    @Test fun replayRequiresTheRunStartReset() {
        val dir = File(tmp.root, "nostart")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s, s.predictor)
        val p = d.accept(SyntheticEvaluation.START_MS + 10, EyeObservation(EyeState.LOST, null, 0.0, 0.0))!!
        d.handle(p, SyntheticEvaluation.START_MS + 30)
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 100)
        val r = EvaluationReplay.replay(dir)
        assertFalse(r.ok); assertTrue(r.errors.any { "run_start" in it })
    }

    @Test fun replayReportIsMachineReadable() {
        val report = EvaluationReplay.json(EvaluationReplay.replay(recorded("report", 7_600, EvaluationEndReason.BACK)))
        val o = org.json.JSONObject(report)
        assertTrue(o.getBoolean("ok")); assertEquals("SYNTHETIC", o.getString("participant_id"))
        assertTrue(o.getString("scope").contains("not camera"))
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
