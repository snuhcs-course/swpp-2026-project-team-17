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
    private fun record(
        outcome: EvaluationOutcome = EvaluationOutcome.PROCESSED, quality: TrackingQuality? = TrackingQuality.TRACKING,
        raw: Pair<Double, Double>? = .5 to .5, point: Pair<Double, Double>? = .5 to .5, observation: EyeObservation? = null,
        latency: Long? = 40_000_000,
    ) = EvaluationAttemptRecord(3, 4, outcome, 1_200, 1_215, EvaluationDetector.FACE, observation, quality, raw, point,
        false, 11_000_000, 1_500_000, latency, outcome == EvaluationOutcome.PROCESSED)

    @Test fun rawFilteredAndDisplayClampedCoordinatesStaySeparate() {
        val r = row(record(raw = 1.31 to -0.2, point = 1.2 to -0.1))
        assertEquals("1.31", r["raw_x_norm"]); assertEquals("-0.2", r["raw_y_norm"])
        assertEquals("1.2", r["point_x_norm"]); assertEquals("-0.1", r["point_y_norm"])
        assertEquals(PointerDisplay.MAX.toString(), r["display_x_norm"]); assertEquals(PointerDisplay.MIN.toString(), r["display_y_norm"])
        // An off-screen final point is still a valid sample: it keeps its full spatial error.
        assertEquals("true", r["valid"]); assertEquals("", r["invalid_reason"])
    }

    @Test fun pointerClampMatchesTheOverlayRule() {
        assertEquals(.015 to .985, PointerDisplay.clamp(-3.0 to 7.0))
        assertEquals(.4 to .6, PointerDisplay.clamp(.4 to .6))
    }

    @Test fun missingValuesAreEmptyNeverZero() {
        val lost = row(record(quality = TrackingQuality.LOST, raw = null, point = null, observation = EyeObservation(EyeState.LOST, null, 0.0, 0.0)))
        assertEquals("false", lost["valid"]); assertEquals("lost", lost["invalid_reason"])
        listOf("raw_x_norm", "point_x_norm", "display_x_norm", "f_x", "f_head_pitch").forEach { assertEquals(it, "", lost[it]) }
        val error = row(record(outcome = EvaluationOutcome.PROCESSING_ERROR, quality = null, raw = null, point = null, latency = null))
        assertEquals("processing_error", error["invalid_reason"]); assertEquals("", error["latency_ms"]); assertEquals("", error["quality"])
        val stale = row(record(outcome = EvaluationOutcome.STALE_DELIVERY, quality = null, raw = null, point = null))
        assertEquals("stale_delivery", stale["invalid_reason"]); assertEquals("40.000", stale["latency_ms"])
    }

    @Test fun featuresRoundTripExactlyIncludingNaN() {
        val f = EyeFeatures(0.1 / 3, -0.02, .5, .51, .3, .03, -.02, .035, -.021, .01, -.02, 1e-7, Double.NaN, .02, -.01)
        val r = row(record(observation = EyeObservation(EyeState.OPEN, f, .21, .22)))
        assertEquals((0.1 / 3).toString(), r["f_x"]); assertEquals("1.0E-7", r["f_roll"]); assertEquals("NaN", r["f_distance_cm"])
        assertEquals((0.1 / 3), r.getValue("f_x").toDouble(), 0.0)
        assertEquals("open", r["eye_state"]); assertEquals("0.21", r["left_open"])
    }

    @Test fun completedSessionWritesConsistentFilesCountersAndChecksums() {
        val dir = File(tmp.root, "P00/eval-a")
        val s = SyntheticEvaluation.session(dir)
        s.open()
        SyntheticEvaluation.simulate(s, EvaluationProtocol.TOTAL_MS, 50, EvaluationEndReason.COMPLETED)
        val meta = JSONObject(File(dir, "metadata.json").readText())
        assertEquals(2, meta.getInt("schema_version"))
        assertEquals("complete", meta.getString("final_state")); assertEquals("completed", meta.getString("end_reason"))
        assertEquals(32, meta.getJSONObject("protocol").getInt("presentations_completed"))
        assertEquals(BuildConfig.EVAL_PROTOCOL_SHA256, meta.getJSONObject("protocol").getString("sha256"))
        val attempts = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        val events = EvaluationReplay.readCsv(File(dir, "events.csv"))
        val counters = meta.getJSONObject("counters"); val logging = meta.getJSONObject("logging")
        assertEquals(attempts.size.toLong(), counters.getLong("attempts_accepted"))
        assertEquals(attempts.size.toLong(), logging.getLong("attempt_rows_written"))
        assertEquals(events.size.toLong(), logging.getLong("event_rows_written"))
        assertEquals(0L, logging.getLong("rows_lost_total")); assertEquals(0L, counters.getLong("attempts_unrecorded"))
        assertEquals(attempts.count { it["valid"] == "true" }.toLong(), counters.getLong("valid_attempts"))
        assertTrue(counters.getLong("frames_cadence_skipped") > 0)
        val outcomes = counters.getJSONObject("attempts_by_outcome")
        assertEquals(1, outcomes.getInt("stale_delivery")); assertEquals(1, outcomes.getInt("discarded_epoch"))
        // Attempts and resets share one contiguous main-thread order, starting with the run_start reset.
        val mainSeq = (attempts.mapNotNull { it["main_seq"]?.takeIf(String::isNotEmpty)?.toLong() } + events.map { it.getValue("main_seq").toLong() }).sorted()
        assertEquals((0L until mainSeq.size).toList(), mainSeq)
        assertEquals("run_start", events.first()["detail"]); assertEquals("0", events.first()["main_seq"])
        assertEquals(listOf("run_start", "stale_delivery", "watchdog_no_result"), events.map { it["detail"] })
        // Every row is assigned by acceptance time, and its case agrees with the protocol.
        attempts.forEach { a ->
            val p = s.run.presentationAt(a.getValue("accepted_uptime_ms").toLong())!!
            assertEquals(p.caseId, a["case_id"]); assertEquals(s.run.phaseAt(a.getValue("accepted_uptime_ms").toLong())!!.id, a["phase"])
        }
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
        a.observed(EvaluationDetector.FACE, EyeObservation(EyeState.LOST, null, 0.0, 0.0), 9_000_000, 5_012_000_000)
        a.reachedMain(5_020_000_000)
        val sample = GazePipeline().process(SyntheticEvaluation.START_MS + 1_100, SyntheticEvaluation.START_MS + 1_121,
            EyeObservation(EyeState.LOST, null, 0.0, 0.0), s.predictor, false)
        a.processed(SyntheticEvaluation.START_MS + 1_121, sample, false, 5_021_500_000)
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 2_000)
        val r = EvaluationReplay.readCsv(File(dir, "attempts.csv")).single()
        assertEquals("21.500", r["latency_ms"]); assertEquals("8.000", r["delivery_ms"]); assertEquals("9.000", r["landmarker_ms"])
        assertEquals("lost", r["invalid_reason"]); assertEquals("true", r["published"])
    }

    @Test fun interruptedRunIsIncompleteAndCountsInFlightAttemptsAsLost() {
        val dir = File(tmp.root, "P00/eval-b")
        var scheduled: (() -> Unit)? = null
        val model = SyntheticEvaluation.model()
        val s = EvaluationSession("eval-b", EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, 0, SyntheticEvaluation.GEOMETRY), dir,
            SyntheticEvaluation.info(EvaluationMode.BLIND_MEASUREMENT, model), model, "{}\n", { it.run() },
            { _, block -> scheduled = block }, { 0L }, { _, _ -> })
        s.open()
        assertEquals("incomplete", JSONObject(File(dir, "metadata.json").readText()).getString("final_state"))
        assertEquals("in_progress_or_interrupted", JSONObject(File(dir, "metadata.json").readText()).getString("end_reason"))
        assertFalse(File(dir, EvaluationChecksums.FILE).exists())
        val done = s.beginAttempt(10, 10)!!; done.reachedMain(20); done.discarded(EvaluationOutcome.DISCARDED_EPOCH, 20)
        val pending = s.beginAttempt(20, 20)!!
        s.finish(EvaluationEndReason.BACKGROUNDED, 5_000)
        assertNull("no attempt may start after finish", s.beginAttempt(30, 30))
        s.pipelineReset(5_001, "evaluation_end") // ignored once finishing
        scheduled!!.invoke() // grace period elapsed with one attempt still in flight
        pending.reachedMain(40); pending.discarded(EvaluationOutcome.DISCARDED_CLOSING, 40) // too late: not written
        val meta = JSONObject(File(dir, "metadata.json").readText())
        assertEquals("incomplete", meta.getString("final_state")); assertEquals("backgrounded", meta.getString("end_reason"))
        assertEquals(2, meta.getJSONObject("protocol").getInt("presentations_completed"))
        assertEquals(1L, meta.getJSONObject("counters").getLong("attempts_unrecorded"))
        assertEquals(1L, meta.getJSONObject("logging").getLong("rows_lost_total"))
        assertEquals(1, EvaluationReplay.readCsv(File(dir, "attempts.csv")).size)
        assertTrue(EvaluationReplay.readCsv(File(dir, "events.csv")).isEmpty())
    }

    @Test fun anAttemptIsRecordedOnlyOnce() {
        val dir = File(tmp.root, "once")
        val s = SyntheticEvaluation.session(dir); s.open()
        val a = s.beginAttempt(SyntheticEvaluation.START_MS, 0)!!
        a.failed(); a.failed(); a.discarded(EvaluationOutcome.DISCARDED_EPOCH, 0)
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
        s.pipelineReset(SyntheticEvaluation.START_MS, "run_start")
        val a = s.beginAttempt(SyntheticEvaluation.START_MS + 1, 0)!!; a.failed()
        s.finish(EvaluationEndReason.BACK, SyntheticEvaluation.START_MS + 10)
        val logging = JSONObject(File(dir, "metadata.json").readText()).getJSONObject("logging")
        assertEquals(2L, logging.getLong("rows_dropped_queue_full")); assertEquals(2L, logging.getLong("rows_lost_total"))
    }

    @Test fun snapshotFreezesSavedCalibrationAndItsParameters() {
        val dir = File(tmp.root, "snap")
        SyntheticEvaluation.session(dir).open()
        val snap = JSONObject(File(dir, "model_snapshot.json").readText())
        assertEquals(SyntheticEvaluation.savedCalibration(), snap.getString("saved_calibration_json"))
        assertEquals(SyntheticEvaluation.model().fitError, snap.getJSONObject("parameters").getDouble("fit_error"), 0.0)
        assertEquals(3, snap.getJSONObject("parameters").getJSONArray("wx").length())
    }

    @Test fun sessionsAreFiledByParticipantAndNeverShareADirectory() {
        val root = tmp.root
        val a = EvaluationLog.sessionDir(root, "P01", EvaluationLog.newSessionId(0))
        val b = EvaluationLog.sessionDir(root, "P01", EvaluationLog.newSessionId(0))
        assertNotEquals(a, b)
        assertEquals(File(File(File(root, "evaluation"), "iter3"), "P01"), a.parentFile)
        assertTrue(EvaluationLog.newSessionId(0).matches(Regex("eval-19700101T000000Z-[0-9a-f]{8}")))
        assertTrue(validEvaluationIdentifier("P01")); assertFalse(validEvaluationIdentifier("../P01")); assertFalse(validEvaluationIdentifier(""))
    }
}
