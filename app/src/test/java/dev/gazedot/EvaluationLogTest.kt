package dev.gazedot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** All sessions here are SYNTHETIC, written by the real logger into temporary folders. */
class EvaluationLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val run = EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, 0, SyntheticEvaluation.GEOMETRY)
    private fun row(r: EvaluationAttemptRecord) = EvaluationCsv.ATTEMPT_HEADER.zip(EvaluationCsv.attempt("s", run, r)).toMap()
    private val features = EyeFeatures(.01, -.02, .5, .51, .3)
    private fun record(
        outcome: EvaluationOutcome = EvaluationOutcome.PROCESSED, features: EyeFeatures? = this.features,
        linear: Pair<Double, Double>? = .5 to .5, predicted: Pair<Double, Double>? = .5 to .5,
        smoothed: Pair<Double, Double>? = .5 to .5, rendered: Pair<Double, Double>? = null,
        featureRejection: String? = null, predictionRejection: String? = null, latency: Long? = 40_000_000,
    ) = EvaluationAttemptRecord(3, 4, outcome, 1_200, 1_215, EvaluationDetector.FACE, featureRejection, features,
        predictionRejection, linear, predicted, smoothed, rendered, 11_000_000, 1_500_000, latency, outcome == EvaluationOutcome.PROCESSED)

    @Test fun linearPredictedSmoothedAndRenderedStagesStaySeparate() {
        val r = row(record(linear = 1.31 to -0.2, predicted = 1.0 to 0.0, smoothed = .93 to .04, rendered = 1004.4 to 96.0))
        assertEquals("1.31", r["linear_x_norm"]); assertEquals("-0.2", r["linear_y_norm"])
        assertEquals("1.0", r["predicted_x_norm"]); assertEquals("0.0", r["predicted_y_norm"])
        assertEquals("0.93", r["smoothed_x_norm"]); assertEquals("0.04", r["smoothed_y_norm"])
        assertEquals("true", r["pointer_shown"]); assertEquals("1004.4", r["rendered_x_px"])
        assertEquals("true", r["valid"]); assertEquals("tracking", r["quality"]); assertEquals("", r["invalid_reason"])
        val hidden = row(record())
        assertEquals("false", hidden["pointer_shown"]); assertEquals("", hidden["rendered_x_px"])
    }

    @Test fun qualityAndReasonsAreTheCandidatesOwnOutcomes() {
        val noFeatures = row(record(features = null, linear = null, predicted = null, smoothed = null,
            featureRejection = "eye_33_133_aperture_ratio_lt_0.10"))
        assertEquals("no_features", noFeatures["quality"]); assertEquals("no_features", noFeatures["invalid_reason"])
        assertEquals("eye_33_133_aperture_ratio_lt_0.10", noFeatures["feature_rejection"]); assertEquals("false", noFeatures["valid"])
        val rejected = row(record(linear = null, predicted = null, smoothed = null, predictionRejection = "face_x_shift_gt_0.12"))
        assertEquals("prediction_rejected", rejected["quality"]); assertEquals("face_x_shift_gt_0.12", rejected["prediction_rejection"])
        assertEquals("0.01", rejected["f_x"])
        val closing = row(record(outcome = EvaluationOutcome.DISCARDED_CLOSING, linear = null, predicted = null, smoothed = null))
        assertEquals("", closing["quality"]); assertEquals("discarded_closing", closing["invalid_reason"])
        assertEquals(listOf("processed", "discarded_closing", "discarded_model_changed", "processing_error"), EvaluationOutcome.entries.map { it.id })
        assertEquals(listOf("tracking", "no_features", "prediction_rejected"), EvaluationQuality.entries.map { it.id })
    }

    @Test fun missingValuesAreEmptyNeverZero() {
        val lost = row(record(features = null, linear = null, predicted = null, smoothed = null))
        listOf("linear_x_norm", "predicted_x_norm", "smoothed_x_norm", "smoothed_y_norm", "f_x", "f_scale", "rendered_x_px")
            .forEach { assertEquals(it, "", lost[it]) }
        val error = row(record(outcome = EvaluationOutcome.PROCESSING_ERROR, features = null, linear = null, predicted = null,
            smoothed = null, latency = null))
        assertEquals("processing_error", error["invalid_reason"]); assertEquals("", error["latency_ms"]); assertEquals("", error["quality"])
    }

    @Test fun featuresRoundTripExactlyIncludingNaN() {
        val f = EyeFeatures(0.1 / 3, -0.02, .5, Double.NaN, 1e-7)
        val r = row(record(features = f))
        assertEquals((0.1 / 3).toString(), r["f_x"]); assertEquals("1.0E-7", r["f_scale"]); assertEquals("NaN", r["f_face_y"])
        assertEquals((0.1 / 3), r.getValue("f_x").toDouble(), 0.0)
    }

    @Test fun completedSessionWritesConsistentFilesCountersAndChecksums() {
        val dir = File(tmp.root, "P00/eval-a")
        val s = SyntheticEvaluation.session(dir)
        s.open()
        SyntheticEvaluation.simulate(s, EvaluationProtocol.TOTAL_MS, 66, EvaluationEndReason.COMPLETED)
        val meta = JSONObject(File(dir, "metadata.json").readText())
        assertEquals(3, meta.getInt("schema_version"))
        assertEquals("complete", meta.getString("final_state")); assertEquals("completed", meta.getString("end_reason"))
        assertEquals(32, meta.getJSONObject("protocol").getInt("presentations_completed"))
        assertEquals("2.0.0", meta.getJSONObject("protocol").getString("version"))
        assertEquals(BuildConfig.EVAL_PROTOCOL_SHA256, meta.getJSONObject("protocol").getString("sha256"))
        assertEquals(EvaluationProtocol.PIPELINE_VARIANT, meta.getJSONObject("pipeline").getString("variant"))
        val attempts = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        val events = EvaluationReplay.readCsv(File(dir, "events.csv"))
        val counters = meta.getJSONObject("counters"); val logging = meta.getJSONObject("logging")
        assertEquals(attempts.size.toLong(), counters.getLong("attempts_accepted"))
        assertEquals(attempts.size.toLong(), logging.getLong("attempt_rows_written"))
        assertEquals(events.size.toLong(), logging.getLong("event_rows_written"))
        assertEquals(0L, logging.getLong("rows_lost_total")); assertEquals(0L, counters.getLong("attempts_unrecorded"))
        assertEquals(attempts.count { it["valid"] == "true" }.toLong(), counters.getLong("valid_attempts"))
        assertTrue(counters.getLong("frames_cadence_skipped") > 0)
        assertEquals(1L, counters.getLong("unlogged_results_dropped_pre_run")); assertEquals(0L, counters.getLong("unlogged_results_dropped_in_run"))
        assertEquals(1L, counters.getLong("camera_errors"))
        val invalid = counters.getJSONObject("invalid_by_reason")
        assertTrue(invalid.getLong("no_features") > 0); assertTrue(invalid.getLong("prediction_rejected") > 0)
        // Attempts and events share one contiguous main-thread order, starting with the run_start reset.
        val mainSeq = (attempts.mapNotNull { it["main_seq"]?.takeIf(String::isNotEmpty)?.toLong() } + events.map { it.getValue("main_seq").toLong() }).sorted()
        assertEquals((0L until mainSeq.size).toList(), mainSeq)
        assertEquals("run_start", events.first()["detail"]); assertEquals("0", events.first()["main_seq"])
        assertEquals(listOf("smoothing_reset/run_start", "unlogged_result_dropped/pre_run accepted_uptime_ms=999960",
            "smoothing_reset/watchdog_no_result", "camera_error/ERROR_CAMERA_IN_USE", "smoothing_reset/watchdog_no_result"),
            events.map { "${it["event"]}/${it["detail"]}" })
        // Every row is assigned by acceptance time, and its case agrees with the protocol.
        attempts.forEach { a ->
            val p = s.run.presentationAt(a.getValue("accepted_uptime_ms").toLong())!!
            assertEquals(p.caseId, a["case_id"]); assertEquals(s.run.phaseAt(a.getValue("accepted_uptime_ms").toLong())!!.id, a["phase"])
        }
        // The model clamp is visible in the diagnostic stream, never in the measured coordinate.
        val clamped = attempts.filter { it["valid"] == "true" && it.getValue("linear_x_norm").toDouble() > 1.0 }
        assertTrue(clamped.isNotEmpty())
        assertTrue(clamped.all { it.getValue("predicted_x_norm") == "1.0" && it.getValue("smoothed_x_norm").toDouble() <= 1.0 })
        val sums = File(dir, EvaluationChecksums.FILE).readLines()
        assertEquals(listOf("attempts.csv", "events.csv", "metadata.json", "model_snapshot.json"), sums.map { it.substringAfter("  ") })
        sums.forEach { line ->
            val (hash, name) = line.split("  ")
            assertEquals(name, EvaluationChecksums.sha256(File(dir, name).readBytes()), hash)
        }
        assertFalse(File(dir, "metadata.json.tmp").exists())
    }

    @Test fun processingLatencyRunsFromAcceptanceToPublication() {
        val dir = File(tmp.root, "lat")
        val s = SyntheticEvaluation.session(dir); s.open()
        val a = s.beginAttempt(SyntheticEvaluation.START_MS + 1_100, 5_000_000_000)!!
        a.observed(EvaluationDetector.NO_FACE, null, null, 9_000_000, 5_012_000_000)
        a.reachedMain(5_020_000_000)
        a.processed(SyntheticEvaluation.START_MS + 1_121, null, null, null, null, null, 5_021_500_000)
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 2_000)
        val r = EvaluationReplay.readCsv(File(dir, "attempts.csv")).single()
        assertEquals("21.500", r["latency_ms"]); assertEquals("8.000", r["delivery_ms"]); assertEquals("9.000", r["landmarker_ms"])
        assertEquals("no_features", r["invalid_reason"]); assertEquals("true", r["published"]); assertEquals("no_face", r["detector"])
    }

    @Test fun interruptedRunIsIncompleteAndCountsInFlightAttemptsAsLost() {
        val dir = File(tmp.root, "P00/eval-b")
        var scheduled: (() -> Unit)? = null
        val record = SyntheticEvaluation.record()
        val s = EvaluationSession("eval-b", EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, 0, SyntheticEvaluation.GEOMETRY), dir,
            SyntheticEvaluation.info(EvaluationMode.BLIND_MEASUREMENT, record), record.model, "{}\n", { it.run() },
            { _, block -> scheduled = block }, { 0L }, { _, _ -> })
        s.open()
        assertEquals("incomplete", JSONObject(File(dir, "metadata.json").readText()).getString("final_state"))
        assertEquals("in_progress_or_interrupted", JSONObject(File(dir, "metadata.json").readText()).getString("end_reason"))
        assertFalse(File(dir, EvaluationChecksums.FILE).exists())
        val done = s.beginAttempt(10, 10)!!; done.reachedMain(20); done.discarded(EvaluationOutcome.DISCARDED_CLOSING, 20)
        val pending = s.beginAttempt(20, 20)!!
        s.finish(EvaluationEndReason.BACKGROUNDED, 5_000)
        assertNull("no attempt may start after finish", s.beginAttempt(30, 30))
        // Until finalization a reset is still logged: the in-flight attempt is smoothed in its context.
        s.smoothingReset(5_001, "watchdog_no_result")
        scheduled!!.invoke() // grace period elapsed with one attempt still in flight
        s.smoothingReset(5_002, "watchdog_no_result") // after finalization: ignored
        pending.reachedMain(40); pending.discarded(EvaluationOutcome.DISCARDED_CLOSING, 40) // too late: not written
        val meta = JSONObject(File(dir, "metadata.json").readText())
        assertEquals("incomplete", meta.getString("final_state")); assertEquals("backgrounded", meta.getString("end_reason"))
        assertEquals(2, meta.getJSONObject("protocol").getInt("presentations_completed"))
        assertEquals(1L, meta.getJSONObject("counters").getLong("attempts_unrecorded"))
        assertEquals(1L, meta.getJSONObject("logging").getLong("rows_lost_total"))
        assertEquals(1, EvaluationReplay.readCsv(File(dir, "attempts.csv")).size)
        assertEquals(listOf("5001"), EvaluationReplay.readCsv(File(dir, "events.csv")).map { it["event_uptime_ms"] })
    }

    @Test fun attemptInFlightAtTheEndIsSmoothedAfterResetsLoggedBeforeFinalization() {
        val dir = File(tmp.root, "drain")
        var grace: (() -> Unit)? = null
        val record = SyntheticEvaluation.record()
        val run = EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, SyntheticEvaluation.START_MS, SyntheticEvaluation.GEOMETRY)
        val s = EvaluationSession("eval-drain", run, dir, SyntheticEvaluation.info(EvaluationMode.BLIND_MEASUREMENT, record),
            record.model, EvaluationModelSnapshot.json(record, 1), { it.run() }, { _, block -> grace = block },
            { SyntheticEvaluation.WALL_MS }, { _, _ -> })
        s.open()
        val d = SyntheticEvaluation.Driver(s)
        d.reset(run.startMs - 100, "run_start")
        val (ex, ey) = SyntheticEvaluation.eye(.3, .3)
        d.handle(d.accept(run.startMs + 10, EyeFeatures(ex, ey, .5, .5, .3)), run.startMs + 30)
        val last = d.accept(run.endMs - 5, EyeFeatures(ex, ey, .5, .5, .3))
        s.finish(EvaluationEndReason.COMPLETED, run.endMs)    // the last attempt is still in flight
        assertNotNull(grace); assertFalse(s.isFinalized)
        d.reset(run.endMs + 300, "watchdog_no_result")        // still logged: its attempt depends on it
        val smoothed = d.handle(last, run.endMs + 900)          // recording the last attempt finalizes the session
        assertTrue(s.isFinalized)
        assertEquals(record.model.predict(EyeFeatures(ex, ey, .5, .5, .3)), smoothed) // the reset cleared the history
        val report = EvaluationReplay.replay(dir)
        assertTrue(report.errors.toString() + report.mismatches, report.ok)
        assertEquals(listOf("run_start", "watchdog_no_result"), EvaluationReplay.readCsv(File(dir, "events.csv")).map { it["detail"] })
        assertEquals(0L, JSONObject(File(dir, "metadata.json").readText()).getJSONObject("logging").getLong("rows_lost_total"))
    }

    @Test fun inRunResultsThatWereNotLoggedAreCountedAsMissing() {
        val dir = File(tmp.root, "race")
        val s = SyntheticEvaluation.session(dir); s.open()
        s.unloggedResultDropped(SyntheticEvaluation.START_MS - 1, SyntheticEvaluation.START_MS + 30)
        s.unloggedResultDropped(SyntheticEvaluation.START_MS, SyntheticEvaluation.START_MS + 40)
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 100)
        val counters = JSONObject(File(dir, "metadata.json").readText()).getJSONObject("counters")
        assertEquals(1L, counters.getLong("unlogged_results_dropped_pre_run")); assertEquals(1L, counters.getLong("unlogged_results_dropped_in_run"))
    }

    @Test fun anAttemptIsRecordedOnlyOnce() {
        val dir = File(tmp.root, "once")
        val s = SyntheticEvaluation.session(dir); s.open()
        val a = s.beginAttempt(SyntheticEvaluation.START_MS, 0)!!
        a.failed(); a.failed(); a.discarded(EvaluationOutcome.DISCARDED_CLOSING, 0)
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 10)
        val rows = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        assertEquals(listOf("processing_error"), rows.map { it["outcome"] }); assertEquals("", rows.single()["main_seq"])
    }

    @Test fun existingSessionDirectoryIsNeverReusedOrOverwritten() {
        val dir = File(tmp.root, "P00/eval-c").apply { mkdirs() }
        File(dir, "keep.txt").writeText("earlier data")
        var failure: String? = null
        val s = SyntheticEvaluation.session(dir) { failure = it }
        s.open()
        assertNotNull(failure)
        assertEquals("earlier data", File(dir, "keep.txt").readText())
        assertEquals(listOf("keep.txt"), dir.list()!!.toList())
        s.finish(EvaluationEndReason.LOGGING_FAILED, SyntheticEvaluation.START_MS)
        assertEquals(listOf("keep.txt"), dir.list()!!.toList())
    }

    @Test fun queueOverflowIsCountedAsLoggingLoss() {
        val dir = File(tmp.root, "overflow")
        val s = SyntheticEvaluation.session(dir, maxPendingRows = 0); s.open()
        s.smoothingReset(SyntheticEvaluation.START_MS, "run_start")
        val a = s.beginAttempt(SyntheticEvaluation.START_MS + 1, 0)!!; a.failed()
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 10)
        val logging = JSONObject(File(dir, "metadata.json").readText()).getJSONObject("logging")
        assertEquals(2L, logging.getLong("rows_dropped_queue_full")); assertEquals(2L, logging.getLong("rows_lost_total"))
    }

    @Test fun snapshotFreezesCalibrationInputsAndParameters() {
        val dir = File(tmp.root, "snap")
        SyntheticEvaluation.session(dir).open()
        val snap = JSONObject(File(dir, "model_snapshot.json").readText())
        assertEquals(9, snap.getJSONArray("anchors").length())
        assertEquals(SyntheticEvaluation.model().fitError, snap.getJSONObject("parameters").getDouble("fit_error"), 0.0)
        assertEquals(3, snap.getJSONObject("parameters").getJSONArray("wx").length())
        assertEquals(EvaluationModelSnapshot.inputsSha256(SyntheticEvaluation.anchors()), snap.getString("calibration_inputs_sha256"))
    }

    @Test fun sessionsAreFiledByParticipantAndNeverShareADirectory() {
        val root = tmp.root
        val a = EvaluationLog.sessionDir(root, "P01", EvaluationLog.newSessionId(0))
        val b = EvaluationLog.sessionDir(root, "P01", EvaluationLog.newSessionId(0))
        assertNotEquals(a, b)
        assertEquals(File(File(File(root, "evaluation"), "iter3"), "P01"), a.parentFile)
        assertTrue(EvaluationLog.newSessionId(0).matches(Regex("eval-19700101T000000Z-[0-9a-f]{8}")))
    }
}
