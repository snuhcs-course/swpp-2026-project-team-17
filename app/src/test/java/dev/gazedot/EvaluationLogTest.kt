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
    private val schedule = EvaluationSchedule(EvaluationMode.BLIND_MEASUREMENT, EvaluationOrder.NHL, 0, SyntheticEvaluation.GEOMETRY)
        .also { it.markReady(0, 0) }
    private fun row(r: EvaluationAttemptRecord) = EvaluationCsv.ATTEMPT_HEADER.zip(EvaluationCsv.attempt("s", schedule, r)).toMap()
    private val features = EyeFeatures(.01, -.02, .5, .51, .3)
    private fun record(
        outcome: EvaluationOutcome = EvaluationOutcome.PROCESSED, observation: EyeObservation? = SyntheticEvaluation.open(features),
        quality: TrackingQuality? = TrackingQuality.TRACKING,
        linear: Pair<Double, Double>? = .5 to .5, predicted: Pair<Double, Double>? = .5 to .5,
        smoothed: Pair<Double, Double>? = .5 to .5, rendered: Pair<Double, Double>? = null,
        featureRejection: String? = null, predictionRejection: String? = null, latency: Long? = 40_000_000, accepted: Long = 4_200,
    ) = EvaluationAttemptRecord(3, 4, outcome, accepted, accepted + 14, accepted + 15, EvaluationDetector.FACE, observation,
        featureRejection, quality, predictionRejection, linear, predicted, smoothed, rendered, 11_000_000, 1_500_000, latency,
        outcome == EvaluationOutcome.PROCESSED)

    @Test fun linearPredictedSmoothedAndRenderedStagesStaySeparate() {
        val r = row(record(linear = 1.31 to -0.2, predicted = 1.0 to 0.0, smoothed = .93 to .04, rendered = 1004.4 to 96.0))
        assertEquals("1.31", r["linear_x_norm"]); assertEquals("-0.2", r["linear_y_norm"])
        assertEquals("1.0", r["predicted_x_norm"]); assertEquals("0.0", r["predicted_y_norm"])
        assertEquals("0.93", r["smoothed_x_norm"]); assertEquals("0.04", r["smoothed_y_norm"])
        assertEquals("true", r["pointer_shown"]); assertEquals("1004.4", r["rendered_x_px"])
        assertEquals("true", r["valid"]); assertEquals("tracking", r["quality"]); assertEquals("", r["invalid_reason"])
        // Ready at 0, so the block starts at 3,000 and acceptance at 4,200 is 1,200 ms into presentation 0 (measurement).
        assertEquals("1", r["block_index"]); assertEquals("neutral", r["head_position"]); assertEquals("measurement", r["phase"])
        assertEquals("neutral-pass1-r0c0", r["case_id"]); assertEquals("1200", r["block_elapsed_ms"])
        assertEquals("OPEN", r["eye_state"]); assertEquals("0.26", r["left_open"]); assertEquals("4214", r["handled_uptime_ms"])
        val hidden = row(record())
        assertEquals("false", hidden["pointer_shown"]); assertEquals("", hidden["rendered_x_px"])
    }

    @Test fun qualityAndReasonsAreThePipelinesOwnOutcomes() {
        val closed = row(record(observation = EyeObservation(EyeState.CLOSED, null, .05, .06), quality = TrackingQuality.CLOSED,
            linear = null, predicted = null, smoothed = null, featureRejection = "eye_33_133_aperture_ratio_lt_0.10"))
        assertEquals("closed", closed["quality"]); assertEquals("closed", closed["invalid_reason"])
        assertEquals("eye_33_133_aperture_ratio_lt_0.10", closed["feature_rejection"]); assertEquals("false", closed["valid"])
        assertEquals("CLOSED", closed["eye_state"]); assertEquals("", closed["f_x"])
        val rejected = row(record(quality = TrackingQuality.OUT_OF_RANGE, linear = null, predicted = null, smoothed = null,
            predictionRejection = "face_x_shift_gt_0.12"))
        assertEquals("out_of_range", rejected["quality"]); assertEquals("face_x_shift_gt_0.12", rejected["prediction_rejection"])
        assertEquals("0.01", rejected["f_x"])
        val stale = row(record(quality = TrackingQuality.STALE, linear = null, predicted = null, smoothed = null))
        assertEquals("stale", stale["invalid_reason"])
        for (outcome in EvaluationOutcome.entries - EvaluationOutcome.PROCESSED) {
            val r = row(record(outcome = outcome, quality = null, linear = null, predicted = null, smoothed = null))
            assertEquals("", r["quality"]); assertEquals(outcome.id, r["invalid_reason"]); assertEquals("false", r["valid"])
        }
        assertEquals(listOf("processed", "discarded_closing", "discarded_epoch", "discarded_delayed", "discarded_block_start",
            "discarded_model_changed", "processing_error"), EvaluationOutcome.entries.map { it.id })
        assertEquals(listOf("tracking", "closed", "uncertain", "lost", "out_of_range", "stale"), TrackingQuality.entries.map { it.id })
    }

    @Test fun preparationAndCountdownRowsCarryTheirBlockButNoCase() {
        val s = EvaluationSchedule(EvaluationMode.BLIND_MEASUREMENT, EvaluationOrder.LHN, 1_000, SyntheticEvaluation.GEOMETRY)
        val prep = EvaluationCsv.ATTEMPT_HEADER.zip(EvaluationCsv.attempt("s", s, record(accepted = 2_000))).toMap()
        assertEquals(listOf("1", "lower", "preparation", "", "", ""),
            listOf(prep["block_index"], prep["head_position"], prep["phase"], prep["block_elapsed_ms"], prep["case_id"], prep["target_x_norm"]))
        s.markReady(0, 5_000)
        val countdown = EvaluationCsv.ATTEMPT_HEADER.zip(EvaluationCsv.attempt("s", s, record(accepted = 6_000))).toMap()
        assertEquals(listOf("countdown", "-2000", ""), listOf(countdown["phase"], countdown["block_elapsed_ms"], countdown["case_id"]))
        val before = EvaluationCsv.ATTEMPT_HEADER.zip(EvaluationCsv.attempt("s", s, record(accepted = 900))).toMap()
        assertEquals(listOf("", "", "-100"), listOf(before["block_index"], before["phase"], before["session_elapsed_ms"]))
    }

    @Test fun missingValuesAreEmptyNeverZero() {
        val lost = row(record(observation = EyeObservation(EyeState.LOST, null, 0.0, 0.0), quality = TrackingQuality.LOST,
            linear = null, predicted = null, smoothed = null))
        listOf("linear_x_norm", "predicted_x_norm", "smoothed_x_norm", "smoothed_y_norm", "f_x", "f_scale", "rendered_x_px")
            .forEach { assertEquals(it, "", lost[it]) }
        val error = row(record(outcome = EvaluationOutcome.PROCESSING_ERROR, observation = null, quality = null,
            linear = null, predicted = null, smoothed = null, latency = null))
        assertEquals("processing_error", error["invalid_reason"]); assertEquals("", error["latency_ms"])
        assertEquals("", error["quality"]); assertEquals("", error["eye_state"]); assertEquals("", error["left_open"])
    }

    @Test fun featuresRoundTripExactlyIncludingNaN() {
        val f = EyeFeatures(0.1 / 3, -0.02, .5, Double.NaN, 1e-7)
        val r = row(record(observation = SyntheticEvaluation.open(f)))
        assertEquals((0.1 / 3).toString(), r["f_x"]); assertEquals("1.0E-7", r["f_scale"]); assertEquals("NaN", r["f_face_y"])
        assertEquals((0.1 / 3), r.getValue("f_x").toDouble(), 0.0)
    }

    @Test fun completedSessionWritesConsistentFilesBlocksCountersAndChecksums() {
        val dir = File(tmp.root, "P00/eval-a")
        val s = SyntheticEvaluation.session(dir, order = EvaluationOrder.LHN)
        s.open()
        SyntheticEvaluation.simulate(s, EvaluationEndReason.COMPLETED)
        val meta = JSONObject(File(dir, "metadata.json").readText())
        assertEquals(4, meta.getInt("schema_version")); assertEquals(EvaluationSession.RECORD_TYPE, meta.getString("record_type"))
        assertEquals("complete", meta.getString("final_state")); assertEquals("completed", meta.getString("end_reason"))
        val protocol = meta.getJSONObject("protocol")
        assertEquals(EvaluationProtocol.VERSION, protocol.getString("version")); assertEquals(BuildConfig.EVAL_PROTOCOL_SHA256, protocol.getString("sha256"))
        assertEquals("lhn", protocol.getString("block_order")); assertEquals(3, protocol.getInt("blocks_completed"))
        assertEquals(96, protocol.getInt("presentations_completed"))
        val blocks = meta.getJSONArray("blocks")
        assertEquals(listOf("lower", "higher", "neutral"), (0 until 3).map { blocks.getJSONObject(it).getString("head_position") })
        for (k in 0 until 3) {
            val b = blocks.getJSONObject(k)
            assertEquals("complete", b.getString("state")); assertEquals(32, b.getInt("presentations_completed"))
            assertEquals(b.getLong("ready_uptime_ms") + 3_000, b.getLong("block_start_uptime_ms"))
            assertEquals(b.getLong("block_start_uptime_ms") + 80_000, b.getLong("planned_block_end_uptime_ms"))
            assertTrue("reset at or just after the start", b.getLong("block_reset_uptime_ms") - b.getLong("block_start_uptime_ms") in 0..66)
            assertEquals(b.getLong("ready_uptime_ms") - b.getLong("preparation_start_uptime_ms"), b.getLong("preparation_ms"))
            if (k > 0) assertEquals(blocks.getJSONObject(k - 1).getLong("planned_block_end_uptime_ms"), b.getLong("preparation_start_uptime_ms"))
        }
        assertEquals(blocks.getJSONObject(2).getLong("planned_block_end_uptime_ms"), meta.getLong("end_uptime_ms"))
        assertEquals(CalibrationPlan.ID, meta.getJSONObject("calibration").getString("calibration_id"))
        val attempts = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        val events = EvaluationReplay.readCsv(File(dir, "events.csv"))
        val counters = meta.getJSONObject("counters"); val logging = meta.getJSONObject("logging")
        assertEquals(attempts.size.toLong(), counters.getLong("attempts_accepted"))
        assertEquals(attempts.size.toLong(), logging.getLong("attempt_rows_written"))
        assertEquals(events.size.toLong(), logging.getLong("event_rows_written"))
        assertEquals(0L, logging.getLong("rows_lost_total")); assertEquals(0L, counters.getLong("attempts_unrecorded"))
        assertEquals(attempts.count { it["valid"] == "true" }.toLong(), counters.getLong("valid_attempts"))
        assertTrue(counters.getLong("frames_cadence_skipped") > 0)
        assertEquals(1L, counters.getLong("unlogged_results_dropped_pre_session")); assertEquals(0L, counters.getLong("unlogged_results_dropped_in_session"))
        assertEquals(3L, counters.getLong("camera_errors"))
        val outcomes = counters.getJSONObject("attempts_by_outcome")
        assertEquals(3L, outcomes.getLong("discarded_block_start")); assertEquals(3L, outcomes.getLong("discarded_delayed"))
        assertEquals(3L, outcomes.getLong("discarded_epoch"))
        val resets = counters.getJSONObject("pipeline_resets_by_reason")
        assertEquals(1L, resets.getLong("session_start")); assertEquals(3L, resets.getLong("block_start"))
        assertEquals(3L, resets.getLong("delayed_result")); assertEquals(6L, resets.getLong("watchdog_no_result"))
        val phases = counters.getJSONObject("attempts_by_phase")
        assertTrue(phases.getLong("preparation") > 0 && phases.getLong("countdown") > 0 && phases.getLong("measurement") > 0)
        assertEquals(0L, phases.getLong("outside"))
        val invalid = counters.getJSONObject("invalid_by_reason")
        assertTrue(invalid.getLong("closed") > 0); assertTrue(invalid.getLong("lost") > 0); assertTrue(invalid.getLong("out_of_range") > 0)
        // Attempts and events share one contiguous main-thread order, starting with the session_start reset.
        val mainSeq = (attempts.mapNotNull { it["main_seq"]?.takeIf(String::isNotEmpty)?.toLong() } + events.map { it.getValue("main_seq").toLong() }).sorted()
        assertEquals((0L until mainSeq.size).toList(), mainSeq)
        assertEquals("pipeline_reset/session_start", events.first().let { "${it["event"]}/${it["detail"]}" })
        assertEquals("0", events.first()["main_seq"])
        assertEquals(3, events.count { it["event"] == "ready" }); assertEquals(3, events.count { it["event"] == "preparation_shown" })
        // Every row is assigned by acceptance time, and its block, position and case agree with the timeline.
        attempts.forEach { a ->
            val at = s.schedule.locate(a.getValue("accepted_uptime_ms").toLong())!!
            assertEquals(at.phase.id, a["phase"]); assertEquals((at.blockIndex + 1).toString(), a["block_index"])
            assertEquals(at.position.id, a["head_position"]); assertEquals(at.caseId ?: "", a["case_id"])
        }
        assertEquals(96, attempts.mapNotNull { it["case_id"]?.takeIf(String::isNotEmpty) }.toSet().size)
        // The block-start drop only ever affects countdown attempts, which no block metric uses.
        assertTrue(attempts.filter { it["outcome"] == "discarded_block_start" }.all { it["phase"] == "countdown" })
        // Real face positions per instructed position are kept, never normalized.
        val faceY = attempts.filter { it["valid"] == "true" && it["phase"] == "measurement" }.groupBy { it.getValue("head_position") }
            .mapValues { (_, rows) -> rows.map { it.getValue("f_face_y").toDouble() }.average() }
        assertTrue(faceY.getValue("higher") < faceY.getValue("neutral") && faceY.getValue("neutral") < faceY.getValue("lower"))
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
        a.observed(EvaluationDetector.NO_FACE, null, EyeObservation(EyeState.LOST, null, 0.0, 0.0), 9_000_000, 5_012_000_000)
        a.reachedMain(5_020_000_000)
        a.processed(SyntheticEvaluation.START_MS + 1_120, SyntheticEvaluation.START_MS + 1_121, TrackingQuality.LOST, null, null, null, null, null, 5_021_500_000)
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 2_000)
        val r = EvaluationReplay.readCsv(File(dir, "attempts.csv")).single()
        assertEquals("21.500", r["latency_ms"]); assertEquals("8.000", r["delivery_ms"]); assertEquals("9.000", r["landmarker_ms"])
        assertEquals("lost", r["invalid_reason"]); assertEquals("true", r["published"]); assertEquals("no_face", r["detector"])
        assertEquals("preparation", r["phase"])
    }

    @Test fun interruptedSessionIsIncompleteAndCountsInFlightAttemptsAsLost() {
        val dir = File(tmp.root, "P00/eval-b")
        var scheduled: (() -> Unit)? = null
        val record = SyntheticEvaluation.record()
        val schedule = EvaluationSchedule(EvaluationMode.BLIND_MEASUREMENT, EvaluationOrder.NHL, 0, SyntheticEvaluation.GEOMETRY)
        val s = EvaluationSession("eval-b", schedule, dir, SyntheticEvaluation.info(EvaluationMode.BLIND_MEASUREMENT, record), record.model,
            "{}\n", { it.run() }, { _, block -> scheduled = block }, { 0L }, { _, _ -> })
        s.open()
        assertEquals("incomplete", JSONObject(File(dir, "metadata.json").readText()).getString("final_state"))
        assertEquals("in_progress_or_interrupted", JSONObject(File(dir, "metadata.json").readText()).getString("end_reason"))
        assertFalse(File(dir, EvaluationChecksums.FILE).exists())
        assertTrue(s.markReady(0, 1_000))
        val done = s.beginAttempt(10, 10)!!; done.reachedMain(20); done.discarded(EvaluationOutcome.DISCARDED_CLOSING, null, 20)
        val pending = s.beginAttempt(20, 20)!!
        s.finish(EvaluationEndReason.BACKGROUNDED, 4_000 + 5_100)
        assertNull("no attempt may start after finish", s.beginAttempt(30, 30))
        assertFalse("no Ready after finish", s.markReady(1, 90_000))
        // Until finalization a reset is still logged: the in-flight attempt is processed in its context.
        s.pipelineReset(9_101, "watchdog_no_result")
        scheduled!!.invoke() // grace period elapsed with one attempt still in flight
        s.pipelineReset(9_102, "watchdog_no_result") // after finalization: ignored
        pending.reachedMain(40); pending.discarded(EvaluationOutcome.DISCARDED_CLOSING, null, 40) // too late: not written
        val meta = JSONObject(File(dir, "metadata.json").readText())
        assertEquals("incomplete", meta.getString("final_state")); assertEquals("backgrounded", meta.getString("end_reason"))
        val blocks = meta.getJSONArray("blocks")
        assertEquals(listOf("incomplete", "not_started", "not_started"), (0 until 3).map { blocks.getJSONObject(it).getString("state") })
        assertEquals(2, blocks.getJSONObject(0).getInt("presentations_completed"))
        assertEquals(0, meta.getJSONObject("protocol").getInt("blocks_completed"))
        assertEquals(1L, meta.getJSONObject("counters").getLong("attempts_unrecorded"))
        assertEquals(1L, meta.getJSONObject("logging").getLong("rows_lost_total"))
        assertEquals(1, EvaluationReplay.readCsv(File(dir, "attempts.csv")).size)
        assertEquals(listOf("ready", "pipeline_reset"), EvaluationReplay.readCsv(File(dir, "events.csv")).map { it["event"] })
    }

    @Test fun anAttemptInFlightAtTheEndIsProcessedAfterResetsLoggedBeforeFinalization() {
        val dir = File(tmp.root, "drain")
        var grace: (() -> Unit)? = null
        val record = SyntheticEvaluation.record()
        val schedule = EvaluationSchedule(EvaluationMode.BLIND_MEASUREMENT, EvaluationOrder.NHL, SyntheticEvaluation.START_MS, SyntheticEvaluation.GEOMETRY)
        val s = EvaluationSession("eval-drain", schedule, dir, SyntheticEvaluation.info(EvaluationMode.BLIND_MEASUREMENT, record),
            record.model, EvaluationModelSnapshot.json(record, 1), { it.run() }, { _, block -> grace = block },
            { SyntheticEvaluation.WALL_MS }, { _, _ -> })
        s.open()
        val d = SyntheticEvaluation.Driver(s)
        d.evaluationReset(schedule.startMs - 100, EvaluationSession.RESET_SESSION_START)
        val (ex, ey) = SyntheticEvaluation.eye(.3, .3)
        val f = SyntheticEvaluation.open(EyeFeatures(ex, ey, .5, .5, .3))
        d.handle(d.accept(schedule.startMs + 10, f), schedule.startMs + 30)
        val last = d.accept(schedule.startMs + 500, f)
        s.finish(EvaluationEndReason.BACK, schedule.startMs + 510) // the last attempt is still in flight
        assertNotNull(grace); assertFalse(s.isFinalized)
        d.evaluationReset(schedule.startMs + 520, "watchdog_no_result") // still logged: its attempt depends on it
        val sample = d.handle(last, schedule.startMs + 540)            // recording the last attempt finalizes the session
        assertTrue(s.isFinalized)
        assertEquals(record.model.predict(f.features!!), sample!!.point) // the reset cleared the history
        val report = EvaluationReplay.replay(dir)
        assertTrue(report.errors.toString() + report.mismatches, report.ok)
        assertEquals(listOf("session_start", "watchdog_no_result"), EvaluationReplay.readCsv(File(dir, "events.csv")).map { it["detail"] })
        assertEquals(0L, JSONObject(File(dir, "metadata.json").readText()).getJSONObject("logging").getLong("rows_lost_total"))
    }

    @Test fun inSessionResultsThatWereNotLoggedAreCountedAsMissing() {
        val dir = File(tmp.root, "race")
        val s = SyntheticEvaluation.session(dir); s.open()
        assertFalse(s.admit(null, SyntheticEvaluation.START_MS - 1, SyntheticEvaluation.START_MS + 30) {})
        assertFalse(s.admit(null, SyntheticEvaluation.START_MS, SyntheticEvaluation.START_MS + 40) {})
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 100)
        val counters = JSONObject(File(dir, "metadata.json").readText()).getJSONObject("counters")
        assertEquals(1L, counters.getLong("unlogged_results_dropped_pre_session")); assertEquals(1L, counters.getLong("unlogged_results_dropped_in_session"))
    }

    @Test fun blockStartResetHappensOnceAndDropsOnlyResultsAcceptedBeforeIt() {
        val dir = File(tmp.root, "block")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s)
        d.evaluationReset(SyntheticEvaluation.START_MS - 100, EvaluationSession.RESET_SESSION_START)
        val f = SyntheticEvaluation.open(EyeFeatures(0.0, 0.0, .5, .5, .3))
        assertTrue(s.markReady(0, SyntheticEvaluation.START_MS + 500))
        val start = s.schedule.blockStartMs(0)!!
        assertNull("not due before the start", s.dueBlockReset(start - 1))
        d.handle(d.accept(start - 70, f), start - 50)             // countdown, processed before the start
        val crossing = d.accept(start - 20, f)                     // countdown, in flight at the start
        val first = d.accept(start + 46, f)                        // the block's first frame
        assertNull("dropped before smoothing", d.handle(crossing, start + 5))
        assertNotNull(d.handle(first, start + 60))
        assertNull("only once", s.dueBlockReset(start + 61))
        d.tick(start + 70)
        s.finish(EvaluationEndReason.BACK, start + 100)
        val rows = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        assertEquals(listOf("countdown/processed", "countdown/discarded_block_start", "settling/processed"),
            rows.map { "${it["phase"]}/${it["outcome"]}" })
        assertEquals(listOf("session_start", "block=1 head_position=neutral block_start_uptime_ms=$start", "block_start"),
            EvaluationReplay.readCsv(File(dir, "events.csv")).map { it["detail"] })
        // The block's first sample starts from an empty smoothing state: it equals the model's prediction.
        assertEquals(rows[2]["predicted_x_norm"], rows[2]["smoothed_x_norm"])
        assertTrue(EvaluationReplay.replay(dir).ok)
    }

    @Test fun anAttemptIsRecordedOnlyOnce() {
        val dir = File(tmp.root, "once")
        val s = SyntheticEvaluation.session(dir); s.open()
        val a = s.beginAttempt(SyntheticEvaluation.START_MS, 0)!!
        a.failed(); a.failed(); a.discarded(EvaluationOutcome.DISCARDED_CLOSING, null, 0)
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
        assertEquals(listOf("keep.txt"), dir.list()!!.toList())
        s.finish(EvaluationEndReason.LOGGING_FAILED, SyntheticEvaluation.START_MS)
        assertEquals(listOf("keep.txt"), dir.list()!!.toList())
    }

    @Test fun queueOverflowIsCountedAsLoggingLoss() {
        val dir = File(tmp.root, "overflow")
        val s = SyntheticEvaluation.session(dir, maxPendingRows = 0); s.open()
        s.pipelineReset(SyntheticEvaluation.START_MS, "session_start")
        val a = s.beginAttempt(SyntheticEvaluation.START_MS + 1, 0)!!; a.failed()
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 10)
        val logging = JSONObject(File(dir, "metadata.json").readText()).getJSONObject("logging")
        assertEquals(2L, logging.getLong("rows_dropped_queue_full")); assertEquals(2L, logging.getLong("rows_lost_total"))
    }

    @Test fun sessionsAreFiledByParticipantAndNeverShareADirectory() {
        val root = tmp.root
        val a = EvaluationLog.sessionDir(root, "ANNA", EvaluationLog.newSessionId(0))
        val b = EvaluationLog.sessionDir(root, "ANNA", EvaluationLog.newSessionId(0))
        assertNotEquals(a, b)
        assertEquals(File(File(File(root, "evaluation"), "calibration-comparison"), "ANNA"), a.parentFile)
        assertTrue(EvaluationLog.newSessionId(0).matches(Regex("eval-19700101T000000Z-[0-9a-f]{8}")))
    }
}
