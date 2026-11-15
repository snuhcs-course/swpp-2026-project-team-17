package dev.gazedot

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Local evaluation logging, schema v4 (calibration comparison). Adapted from the Iteration 3 schema v3 logger: adds
 * blocks, instructed head positions, preparation and countdown phases, the pipeline's own qualities and discard
 * outcomes, and the observation inputs replay needs (eye state, openness, handling time). Records numerical pipeline
 * inputs, outputs, timing and validity for every processing attempt of a session, plus every pipeline reset. Never
 * camera frames, images, landmarks, audio or screenshots.
 * Files stay in app-private storage: filesDir/evaluation/calibration-comparison/<participant>/<session>/.
 */
const val EVALUATION_SCHEMA_VERSION = 4

/** How a processing attempt ended. Only PROCESSED attempts went through GazePipeline.process. */
enum class EvaluationOutcome(val id: String) {
    PROCESSED("processed"),
    /** The result reached the main thread while the service was closing; the service drops it. */
    DISCARDED_CLOSING("discarded_closing"),
    /** A pipeline reset happened after the frame was accepted; the service drops results of an older epoch. */
    DISCARDED_EPOCH("discarded_epoch"),
    /** Handled more than 250 ms after acceptance; the service drops it and resets the pipeline. */
    DISCARDED_DELAYED("discarded_delayed"),
    /** Accepted before a block started and handled after it; dropped before smoothing so the block starts empty. */
    DISCARDED_BLOCK_START("discarded_block_start"),
    /** The active model was no longer the session's model; the session ends. */
    DISCARDED_MODEL_CHANGED("discarded_model_changed"),
    /** An exception in the camera worker; the service stops itself afterwards. */
    PROCESSING_ERROR("processing_error"),
}

enum class EvaluationDetector(val id: String) {
    NOT_READY("landmarker_not_ready"), NO_FACE("no_face"), FACE("face_detected"),
}

enum class EvaluationEndReason(val id: String) {
    COMPLETED("completed"), BACK("back"), BACKGROUNDED("backgrounded"),
    CONFIGURATION_CHANGED("configuration_changed"), SERVICE_STOPPED("service_stopped"),
    MODEL_CHANGED("model_changed"), LOGGING_FAILED("logging_failed"),
}

/** The pipeline quality names logged in schema v4 (GazePipeline's TrackingQuality, lower case). */
val TrackingQuality.id get() = name.lowercase(Locale.ROOT)

data class EvaluationSessionInfo(
    val participantId: String,
    val sessionLabel: String,
    val protocolSha256: String,
    /** Static metadata sections built by the caller (build, app, device, display, calibration, conditions, ...). */
    val sections: Map<String, Any?>,
)

/** One finished attempt. Missing values stay null and are written as empty cells, never 0. */
data class EvaluationAttemptRecord(
    val seq: Long, val mainSeq: Long?, val outcome: EvaluationOutcome,
    val acceptedUptimeMs: Long, val handledUptimeMs: Long?, val resultUptimeMs: Long?,
    val detector: EvaluationDetector?, val observation: EyeObservation?, val featureRejection: String?,
    val pipelineQuality: TrackingQuality?, val predictionRejection: String?,
    /** GazeModel linear output before its [0, 1] clamp (diagnostic), GazePipeline rawPoint (predict) and point (smoothed). */
    val linear: Pair<Double, Double>?, val predicted: Pair<Double, Double>?, val smoothed: Pair<Double, Double>?,
    /** Screen px where the overlay drew the smoothed point; null when the pointer was hidden. */
    val rendered: Pair<Double, Double>?,
    val landmarkerNanos: Long?, val deliveryNanos: Long?, val latencyNanos: Long?, val published: Boolean,
) {
    val features: EyeFeatures? get() = observation?.features
    val quality: String? get() = if (outcome != EvaluationOutcome.PROCESSED) null else pipelineQuality?.id
    val valid get() = outcome == EvaluationOutcome.PROCESSED && pipelineQuality == TrackingQuality.TRACKING &&
        smoothed?.let { it.first.isFinite() && it.second.isFinite() } == true
    /** The actual reason no valid sample exists: the attempt outcome, or else the pipeline quality. */
    val invalidReason get() = when {
        valid -> null
        outcome != EvaluationOutcome.PROCESSED -> outcome.id
        else -> quality ?: "unspecified"
    }
}

object EvaluationCsv {
    val FEATURES = listOf("f_x", "f_y", "f_face_x", "f_face_y", "f_scale")
    val ATTEMPT_HEADER = listOf(
        "schema_version", "session_id", "attempt_seq", "main_seq", "outcome",
        "accepted_uptime_ms", "handled_uptime_ms", "result_uptime_ms", "session_elapsed_ms",
        "block_index", "head_position", "phase", "block_elapsed_ms",
        "pass_number", "trial_index", "index_in_pass", "case_id", "target_id", "target_x_norm", "target_y_norm",
        "detector", "eye_state", "left_open", "right_open", "feature_rejection",
    ) + FEATURES + listOf(
        "quality", "prediction_rejection", "valid", "invalid_reason",
        "linear_x_norm", "linear_y_norm", "predicted_x_norm", "predicted_y_norm", "smoothed_x_norm", "smoothed_y_norm",
        "pointer_shown", "rendered_x_px", "rendered_y_px", "landmarker_ms", "delivery_ms", "latency_ms", "published",
    )
    val EVENT_HEADER = listOf("schema_version", "session_id", "main_seq", "event_uptime_ms", "session_elapsed_ms",
        "block_index", "phase", "event", "detail")

    fun line(values: List<String>) = values.joinToString(",") { escape(it) }
    fun escape(v: String) =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Double.toString round-trips exactly, including NaN/Infinity inside a present feature vector. */
    private fun exact(v: Double?) = v?.toString() ?: ""
    private fun millis(nanos: Long?) = nanos?.let { String.format(Locale.ROOT, "%.3f", it / 1e6) } ?: ""

    fun attempt(sessionId: String, schedule: EvaluationSchedule, r: EvaluationAttemptRecord): List<String> {
        val at = schedule.locate(r.acceptedUptimeMs)
        val p = at?.presentation
        val o = r.observation
        val f = o?.features
        val featureCells = if (f == null) List(FEATURES.size) { "" } else listOf(f.x, f.y, f.faceX, f.faceY, f.scale).map { exact(it) }
        return listOf(
            EVALUATION_SCHEMA_VERSION.toString(), sessionId, r.seq.toString(), r.mainSeq?.toString() ?: "", r.outcome.id,
            r.acceptedUptimeMs.toString(), r.handledUptimeMs?.toString() ?: "", r.resultUptimeMs?.toString() ?: "",
            (r.acceptedUptimeMs - schedule.startMs).toString(),
            at?.let { (it.blockIndex + 1).toString() } ?: "", at?.position?.id ?: "", at?.phase?.id ?: "",
            at?.blockElapsedMs?.toString() ?: "",
            p?.pass?.toString() ?: "", p?.trialIndex?.toString() ?: "", p?.indexInPass?.toString() ?: "",
            at?.caseId ?: "", p?.target?.id ?: "", exact(p?.target?.x), exact(p?.target?.y),
            r.detector?.id ?: "", o?.state?.name ?: "", exact(o?.leftOpen), exact(o?.rightOpen), r.featureRejection ?: "",
        ) + featureCells + listOf(
            r.quality ?: "", r.predictionRejection ?: "", r.valid.toString(), r.invalidReason ?: "",
            exact(r.linear?.first), exact(r.linear?.second), exact(r.predicted?.first), exact(r.predicted?.second),
            exact(r.smoothed?.first), exact(r.smoothed?.second),
            (r.rendered != null).toString(), exact(r.rendered?.first), exact(r.rendered?.second),
            millis(r.landmarkerNanos), millis(r.deliveryNanos), millis(r.latencyNanos), r.published.toString(),
        )
    }

    fun event(sessionId: String, schedule: EvaluationSchedule, mainSeq: Long, uptimeMs: Long, event: String, detail: String): List<String> {
        val at = schedule.locate(uptimeMs)
        return listOf(EVALUATION_SCHEMA_VERSION.toString(), sessionId, mainSeq.toString(), uptimeMs.toString(),
            (uptimeMs - schedule.startMs).toString(), at?.let { (it.blockIndex + 1).toString() } ?: "", at?.phase?.id ?: "",
            event, detail)
    }
}

/** Minimal deterministic JSON writer (insertion order kept); org.json formatting differs between JVM and Android. */
object EvaluationJson {
    fun write(v: Any?, indent: String = ""): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long -> v.toString()
        is Float -> write(v.toDouble(), indent)
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) ->
            "$indent  ${quote(k.toString())}: ${write(x, "$indent  ")}"
        }
        is List<*> -> if (v.any { it is Map<*, *> }) {
            v.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${write(it, "$indent  ")}" }
        } else v.joinToString(", ", "[", "]") { write(it, indent) }
        else -> quote(v.toString())
    }
    fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
}

object EvaluationChecksums {
    const val FILE = "SHA256SUMS"
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    /** sha256sum-compatible lines ("<hex>  <name>"), sorted by name. */
    fun manifest(dir: File, names: List<String>): String =
        names.sorted().joinToString("") { "${sha256(File(dir, it).readBytes())}  $it\n" }
}

/**
 * One processing attempt accepted by the cadence limiter during a session. Created on the camera worker with the
 * frame's acceptance time; recorded exactly once, from whichever thread finishes it.
 */
class EvaluationAttempt internal constructor(
    val session: EvaluationSession, val seq: Long, val acceptedUptimeMs: Long, private val acceptedNanos: Long,
) {
    @Volatile private var detector: EvaluationDetector? = null
    @Volatile private var featureRejection: String? = null
    @Volatile private var observation: EyeObservation? = null
    @Volatile private var landmarkerNanos: Long? = null
    @Volatile private var postedNanos: Long? = null
    private var mainSeq: Long? = null
    private var handledNanos: Long? = null
    private val recorded = AtomicBoolean(false)

    /** Camera worker: what detection and EyeObservations.observe produced, just before the result is posted. */
    fun observed(detector: EvaluationDetector, featureRejection: String?, observation: EyeObservation, landmarkerNanos: Long?, postedNanos: Long) {
        this.detector = detector; this.featureRejection = featureRejection; this.observation = observation
        this.landmarkerNanos = landmarkerNanos; this.postedNanos = postedNanos
    }

    /** Main thread, first statement of the result handler: fixes this attempt's place among resets. */
    fun reachedMain(handledNanos: Long) {
        mainSeq = session.nextMainSeq(); this.handledNanos = handledNanos
    }

    fun discarded(outcome: EvaluationOutcome, handledUptimeMs: Long?, resultUptimeMs: Long) =
        finish(outcome, handledUptimeMs, resultUptimeMs, null, null, null, null, null, null, handledNanos, published = false)

    fun processed(
        handledUptimeMs: Long, resultUptimeMs: Long, quality: TrackingQuality, predictionRejection: String?,
        linear: Pair<Double, Double>?, predicted: Pair<Double, Double>?, smoothed: Pair<Double, Double>?,
        rendered: Pair<Double, Double>?, publishedNanos: Long,
    ) = finish(EvaluationOutcome.PROCESSED, handledUptimeMs, resultUptimeMs, quality, predictionRejection, linear, predicted,
        smoothed, rendered, publishedNanos, published = true)

    fun failed() = finish(EvaluationOutcome.PROCESSING_ERROR, null, null, null, null, null, null, null, null, null, published = false)

    private fun finish(
        outcome: EvaluationOutcome, handledUptimeMs: Long?, resultUptimeMs: Long?, quality: TrackingQuality?,
        predictionRejection: String?, linear: Pair<Double, Double>?, predicted: Pair<Double, Double>?,
        smoothed: Pair<Double, Double>?, rendered: Pair<Double, Double>?, endNanos: Long?, published: Boolean,
    ) {
        if (!recorded.compareAndSet(false, true)) return
        val posted = postedNanos; val handled = handledNanos
        session.record(EvaluationAttemptRecord(
            seq, mainSeq, outcome, acceptedUptimeMs, handledUptimeMs, resultUptimeMs, detector, observation, featureRejection,
            quality, predictionRejection, linear, predicted, smoothed, rendered, landmarkerNanos,
            if (posted != null && handled != null) handled - posted else null,
            endNanos?.let { it - acceptedNanos }, published,
        ))
    }
}

/**
 * Logs one session to `dir/attempts.csv`, `dir/events.csv`, `dir/model_snapshot.json`, `dir/metadata.json` and, at
 * finalization, `dir/SHA256SUMS`. Counters are guarded by a short lock; all file I/O runs on [writer]. Metadata is
 * written as incomplete at open and replaced once at finalization, so an interrupted process leaves an incomplete
 * session behind.
 */
class EvaluationSession(
    val id: String,
    val schedule: EvaluationSchedule,
    val dir: File,
    private val info: EvaluationSessionInfo,
    /** The session's model; any other active model ends the session. */
    val model: GazeModel,
    private val modelSnapshotJson: String,
    private val writer: Executor,
    private val delay: (Long, () -> Unit) -> Unit,
    private val wallClockMs: () -> Long,
    private val onLoggingFailed: (EvaluationSession, String) -> Unit,
    private val maxPendingRows: Int = 4096,
) {
    private val startWallMs = wallClockMs()

    private val lock = Any()
    // Guarded by lock.
    private var finishing = false
    private var finalized = false
    private var inFlight = 0
    private var nextSeq = 0L
    private var nextMain = 0L
    private var recordedAttempts = 0L
    private var framesReceived = 0L
    private var framesCadenceSkipped = 0L
    private var framesIgnoredClosing = 0L
    private var validAttempts = 0L
    private val byOutcome = EvaluationOutcome.entries.associate { it.id to 0L }.toMutableMap()
    private val byPhase = (listOf("outside") + EvaluationPhase.entries.map { it.id }).associateWith { 0L }.toMutableMap()
    private val invalidByReason = sortedMapOf<String, Long>()
    private val byDetector = EvaluationDetector.entries.associate { it.id to 0L }.toMutableMap()
    private val resetsByReason = sortedMapOf<String, Long>()
    private var cameraErrors = 0L
    private var unloggedPreSession = 0L
    private var unloggedInSession = 0L
    private var attemptRowsEnqueued = 0L
    private var eventRowsEnqueued = 0L
    private var rowsDroppedQueueFull = 0L
    private var endReason: EvaluationEndReason? = null
    private var endUptimeMs: Long? = null
    private var endWallMs: Long? = null
    private val preparationShown = LongArray(EvaluationProtocol.BLOCKS) { -1L }
    private val blockReset = LongArray(EvaluationProtocol.BLOCKS) { -1L }
    private val pendingWrites = AtomicInteger()

    // Writer thread only.
    private var opened = false
    private var attemptsCsv: BufferedWriter? = null
    private var eventsCsv: BufferedWriter? = null
    private var broken = false
    private var attemptRowsWritten = 0L
    private var eventRowsWritten = 0L
    private var rowsDroppedWriteError = 0L

    val isFinalized get() = synchronized(lock) { finalized }

    /** Creates a new directory; an existing one is never reused or overwritten. */
    fun open() = writer.execute {
        try {
            if (dir.exists() || !dir.mkdirs()) throw IOException("cannot create new session directory ${dir.name}")
            opened = true
            File(dir, SNAPSHOT_FILE).writeText(modelSnapshotJson, Charsets.UTF_8)
            attemptsCsv = File(dir, ATTEMPTS_FILE).bufferedWriter(Charsets.UTF_8).apply {
                write(EvaluationCsv.line(EvaluationCsv.ATTEMPT_HEADER)); write("\n"); flush()
            }
            eventsCsv = File(dir, EVENTS_FILE).bufferedWriter(Charsets.UTF_8).apply {
                write(EvaluationCsv.line(EvaluationCsv.EVENT_HEADER)); write("\n"); flush()
            }
            writeMetadata(null)
        } catch (e: Exception) {
            broken = true
            onLoggingFailed(this, e.message ?: e.javaClass.simpleName)
        }
    }

    fun covers(now: Long) = schedule.covers(now)

    fun frameReceived() { synchronized(lock) { if (!finishing) framesReceived++ } }
    fun frameSkipped(closing: Boolean) {
        synchronized(lock) { if (!finishing) { if (closing) framesIgnoredClosing++ else framesCadenceSkipped++ } }
    }
    fun beginAttempt(acceptedUptimeMs: Long, acceptedNanos: Long): EvaluationAttempt? = synchronized(lock) {
        if (finishing) null else { inFlight++; EvaluationAttempt(this, nextSeq++, acceptedUptimeMs, acceptedNanos) }
    }

    /** Order of main-thread handling, shared by attempts and events; replay follows it. */
    fun nextMainSeq(): Long = synchronized(lock) { nextMain++ }

    /**
     * A reset of the gaze pipeline (GazePipeline.reset: smoothing and timestamp history). Logged until finalization,
     * not only until the session ends: attempts still in flight after the end are processed in the context it creates.
     */
    fun pipelineReset(uptimeMs: Long, reason: String) {
        synchronized(lock) {
            if (finalized) return
            resetsByReason[reason] = (resetsByReason[reason] ?: 0L) + 1
            enqueueEvent(uptimeMs, EVENT_PIPELINE_RESET, reason)
        }
    }

    /** A non-reset event (for example a camera error) recorded for diagnostics. */
    fun event(uptimeMs: Long, event: String, detail: String) {
        synchronized(lock) {
            if (finalized) return
            if (event == EVENT_CAMERA_ERROR) cameraErrors++
            enqueueEvent(uptimeMs, event, detail)
        }
    }

    /** The preparation screen of block [k] became visible (diagnostic; the schedule does not depend on it). */
    fun preparationShown(k: Int, uptimeMs: Long) {
        synchronized(lock) {
            if (finishing || preparationShown[k] >= 0) return
            preparationShown[k] = uptimeMs
            enqueueEvent(uptimeMs, EVENT_PREPARATION_SHOWN, "block=${k + 1} head_position=${schedule.position(k).id}")
        }
    }

    /** Ready pressed for block [k]; starts its countdown. False if not accepted (not in its preparation, or repeated). */
    fun markReady(k: Int, uptimeMs: Long): Boolean = synchronized(lock) {
        if (finishing || !schedule.markReady(k, uptimeMs)) return false
        enqueueEvent(uptimeMs, EVENT_READY, "block=${k + 1} head_position=${schedule.position(k).id} " +
            "block_start_uptime_ms=${schedule.blockStartMs(k)}")
        true
    }

    /**
     * Main thread. The block whose start has passed by [now] and whose pipeline reset has not been applied yet; marks
     * it applied. The caller then performs the reset (reason "block_start"), before handling anything else.
     */
    fun dueBlockReset(now: Long): Int? = synchronized(lock) {
        if (finishing) return null
        val k = schedule.startedBlockAt(now) ?: return null
        if (blockReset[k] >= 0) return null
        blockReset[k] = now
        k
    }

    /**
     * Main thread, for every result handled while this session is current, after the service's own checks and
     * before GazePipeline.process. Applies a due block-start reset through [reset], then decides whether the result
     * may be processed: results in flight across the session start or a block start are dropped before smoothing,
     * as the reference dropped results in flight at run start.
     */
    fun admit(attempt: EvaluationAttempt?, acceptedUptimeMs: Long, handledUptimeMs: Long, reset: (String) -> Unit): Boolean {
        dueBlockReset(handledUptimeMs)?.let { reset(RESET_BLOCK_START) }
        if (attempt == null) {
            val end = schedule.endMs
            if (acceptedUptimeMs >= schedule.startMs && end != null && acceptedUptimeMs >= end) return true // after the session
            unloggedResultDropped(acceptedUptimeMs, handledUptimeMs)
            return false
        }
        val k = schedule.startedBlockAt(handledUptimeMs) ?: return true
        if (acceptedUptimeMs < schedule.blockStartMs(k)!!) {
            attempt.discarded(EvaluationOutcome.DISCARDED_BLOCK_START, handledUptimeMs, handledUptimeMs)
            return false
        }
        return true
    }

    /**
     * A result handled while this session was current but not logged as one of its attempts, so dropped before
     * smoothing. Accepted before the session start it belongs to no phase; at or after it, an attempt is missing,
     * which the analyzer treats as an integrity problem.
     */
    fun unloggedResultDropped(acceptedUptimeMs: Long, handledUptimeMs: Long) {
        synchronized(lock) {
            if (finalized) return
            val preSession = acceptedUptimeMs < schedule.startMs
            if (preSession) unloggedPreSession++ else unloggedInSession++
            enqueueEvent(handledUptimeMs, EVENT_UNLOGGED_RESULT_DROPPED,
                "${if (preSession) "pre_session" else "in_session"} accepted_uptime_ms=$acceptedUptimeMs")
        }
    }

    // Caller holds lock.
    private fun enqueueEvent(uptimeMs: Long, event: String, detail: String) =
        enqueue(EvaluationCsv.line(EvaluationCsv.event(id, schedule, nextMain++, uptimeMs, event, detail)), event = true)

    internal fun record(r: EvaluationAttemptRecord) {
        val line = EvaluationCsv.line(EvaluationCsv.attempt(id, schedule, r))
        val phase = schedule.locate(r.acceptedUptimeMs)?.phase?.id ?: "outside"
        val lastInFlight = synchronized(lock) {
            // Too late: finalization already counted this attempt as unrecorded.
            if (finalized) return
            inFlight--
            recordedAttempts++
            byOutcome[r.outcome.id] = byOutcome.getValue(r.outcome.id) + 1
            byPhase[phase] = byPhase.getValue(phase) + 1
            r.detector?.let { byDetector[it.id] = byDetector.getValue(it.id) + 1 }
            val reason = r.invalidReason
            if (reason == null) validAttempts++ else invalidByReason[reason] = (invalidByReason[reason] ?: 0L) + 1
            enqueue(line, event = false)
            finishing && inFlight == 0
        }
        if (lastInFlight) finalizeNow()
    }

    // Caller holds lock.
    private fun enqueue(line: String, event: Boolean) {
        if (pendingWrites.get() >= maxPendingRows) { rowsDroppedQueueFull++; return }
        if (event) eventRowsEnqueued++ else attemptRowsEnqueued++
        pendingWrites.incrementAndGet()
        writer.execute { writeRow(line, event) }
    }

    /** Idempotent: only the first call sets the end reason. Waits briefly for in-flight attempts. */
    fun finish(reason: EvaluationEndReason, endUptimeMs: Long) {
        val wait = synchronized(lock) {
            if (finishing) return
            finishing = true; endReason = reason; this.endUptimeMs = endUptimeMs; endWallMs = wallClockMs()
            inFlight > 0
        }
        if (wait) delay(IN_FLIGHT_GRACE_MS) { finalizeNow() } else finalizeNow()
    }

    private fun finalizeNow() {
        val snapshot = synchronized(lock) {
            if (finalized) return
            finalized = true
            snapshot(attemptsUnrecorded = inFlight.toLong())
        }
        writer.execute {
            try { attemptsCsv?.close() } catch (e: IOException) { broken = true }
            try { eventsCsv?.close() } catch (e: IOException) { broken = true }
            attemptsCsv = null; eventsCsv = null
            // Never write into a directory this session did not create.
            if (opened) try {
                writeMetadata(snapshot)
                atomicWrite(File(dir, EvaluationChecksums.FILE),
                    EvaluationChecksums.manifest(dir, listOf(ATTEMPTS_FILE, EVENTS_FILE, METADATA_FILE, SNAPSHOT_FILE)))
            } catch (e: Exception) { broken = true }
        }
    }

    private fun writeRow(line: String, event: Boolean) {
        pendingWrites.decrementAndGet()
        val out = if (event) eventsCsv else attemptsCsv
        if (broken || out == null) { rowsDroppedWriteError++; return }
        try {
            out.write(line); out.write("\n"); out.flush()
            if (event) eventRowsWritten++ else attemptRowsWritten++
        } catch (e: IOException) {
            broken = true; rowsDroppedWriteError++
            onLoggingFailed(this, e.message ?: "write failed")
        }
    }

    private class Snapshot(
        val endReason: EvaluationEndReason?, val endUptimeMs: Long?, val endWallMs: Long?,
        val counters: Map<String, Any?>, val attemptRowsEnqueued: Long, val eventRowsEnqueued: Long,
        val rowsDroppedQueueFull: Long, val attemptsUnrecorded: Long,
        val preparationShown: List<Long?>, val blockReset: List<Long?>,
    )

    private fun snapshot(attemptsUnrecorded: Long) = Snapshot(endReason, endUptimeMs, endWallMs, mapOf(
        "frames_received" to framesReceived,
        "frames_cadence_skipped" to framesCadenceSkipped,
        "frames_ignored_service_closing" to framesIgnoredClosing,
        "attempts_accepted" to nextSeq,
        "attempts_recorded" to recordedAttempts,
        "attempts_unrecorded" to attemptsUnrecorded,
        "attempts_by_outcome" to byOutcome.toMap(),
        "attempts_by_phase" to byPhase.toMap(),
        "detector" to byDetector.toMap(),
        "valid_attempts" to validAttempts,
        "invalid_by_reason" to invalidByReason.toMap(),
        "pipeline_resets" to resetsByReason.values.sum(),
        "pipeline_resets_by_reason" to resetsByReason.toMap(),
        "camera_errors" to cameraErrors,
        "unlogged_results_dropped_pre_session" to unloggedPreSession,
        "unlogged_results_dropped_in_session" to unloggedInSession,
    ), attemptRowsEnqueued, eventRowsEnqueued, rowsDroppedQueueFull, attemptsUnrecorded,
        preparationShown.map { it.takeIf { v -> v >= 0 } }, blockReset.map { it.takeIf { v -> v >= 0 } })

    private fun blocksMetadata(s: Snapshot?): List<Map<String, Any?>> = (0 until EvaluationProtocol.BLOCKS).map { k ->
        val end = s?.endUptimeMs
        val ready = schedule.readyMs(k)
        val preparation = schedule.preparationStartMs(k)
        val completed = end?.let { schedule.presentationsCompleted(k, it) }
        linkedMapOf(
            "block_index" to k + 1,
            "head_position" to schedule.position(k).id,
            "case_id_prefix" to "${schedule.position(k).id}-",
            "preparation_start_uptime_ms" to preparation,
            "preparation_shown_uptime_ms" to s?.preparationShown?.get(k),
            "ready_uptime_ms" to ready,
            "preparation_ms" to if (ready != null && preparation != null) ready - preparation else null,
            "countdown_ms" to EvaluationProtocol.COUNTDOWN_MS,
            "block_start_uptime_ms" to schedule.blockStartMs(k),
            "planned_block_end_uptime_ms" to schedule.blockEndMs(k),
            "block_reset_uptime_ms" to s?.blockReset?.get(k),
            "presentations_planned" to EvaluationProtocol.presentations.size,
            "presentations_completed" to completed,
            "state" to when {
                s == null -> null
                completed == EvaluationProtocol.presentations.size -> "complete"
                schedule.blockStartMs(k) == null -> "not_started"
                else -> "incomplete"
            },
        )
    }

    /** Called on the writer thread. A null snapshot means the session is still in progress. */
    private fun writeMetadata(s: Snapshot?) {
        val reason = s?.endReason
        val endUptime = s?.endUptimeMs
        val blocks = blocksMetadata(s)
        val metadata = linkedMapOf<String, Any?>(
            "schema_version" to EVALUATION_SCHEMA_VERSION,
            "record_type" to RECORD_TYPE,
            "session_id" to id,
            "participant_id" to info.participantId,
            "session_label" to info.sessionLabel,
            "mode" to schedule.mode.id,
            "final_state" to if (reason == EvaluationEndReason.COMPLETED) "complete" else "incomplete",
            "end_reason" to (reason?.id ?: "in_progress_or_interrupted"),
            "start_wall_time_utc" to Instant.ofEpochMilli(startWallMs).toString(),
            "end_wall_time_utc" to s?.endWallMs?.let { Instant.ofEpochMilli(it).toString() },
            "start_uptime_ms" to schedule.startMs,
            "end_uptime_ms" to endUptime,
            "planned_end_uptime_ms" to schedule.endMs,
            "protocol" to linkedMapOf(
                "id" to EvaluationProtocol.ID,
                "version" to EvaluationProtocol.VERSION,
                "sha256" to info.protocolSha256,
                "sha256_basis" to "docs/calibration-comparison/protocol.json bytes with CRLF converted to LF",
                "grid_norm" to EvaluationProtocol.GRID,
                "presentation_ms" to EvaluationProtocol.TARGET_MS,
                "settling_ms" to EvaluationProtocol.SETTLING_MS,
                "measurement_ms" to EvaluationProtocol.MEASUREMENT_MS,
                "presentations_per_block" to EvaluationProtocol.presentations.size,
                "blocks_planned" to EvaluationProtocol.BLOCKS,
                "countdown_ms" to EvaluationProtocol.COUNTDOWN_MS,
                "session_start_lead_ms" to EvaluationProtocol.SESSION_START_LEAD_MS,
                "block_order" to schedule.order.id,
                "block_positions" to schedule.order.positions.map { it.id },
                "case_suffixes" to EvaluationProtocol.presentations.map { it.caseSuffix },
                "blocks_completed" to blocks.count { it["state"] == "complete" }.takeIf { s != null },
                "presentations_completed" to blocks.sumOf { (it["presentations_completed"] as Int?) ?: 0 }.takeIf { s != null },
            ),
            "blocks" to blocks,
        )
        metadata.putAll(info.sections)
        metadata["coordinates"] = mapOf(
            "frame" to "full-display normalized: px / calibration basis (WindowManager.maximumWindowMetrics bounds); shared by calibration anchors, targets and predictions",
            "linear" to "GazeModel linear output before the model's own [0, 1] clamp; diagnostic only, never used by the app",
            "predicted" to "GazePipeline rawPoint: GazeModel.predict output, clamped to [0, 1] per axis by the model",
            "smoothed" to "GazePipeline point (0.28 exponential smoothing of predicted) supplied to TrackerState and the overlay; primary measured coordinate; inherits the model clamp",
            "rendered" to "screen px where DotOverlay draws the smoothed point; empty when the pointer is hidden",
        )
        metadata["timing"] = mapOf(
            "accepted_uptime_ms" to "SystemClock.uptimeMillis when the camera worker accepted the frame; detectForVideo timestamp, GazePipeline timestamp and schedule key",
            "handled_uptime_ms" to "SystemClock.uptimeMillis passed to GazePipeline.process as its current time (main thread)",
            "result_uptime_ms" to "SystemClock.uptimeMillis when the main thread finished handling the result",
            "landmarker_ms" to "System.nanoTime duration of FaceLandmarker.detectForVideo only",
            "delivery_ms" to "System.nanoTime from posting the result on the worker to the main-thread handler starting",
            "latency_ms" to "System.nanoTime from frame acceptance to publication of the processed result, or to explicit discard",
        )
        metadata["counters"] = s?.counters
        metadata["logging"] = s?.let {
            mapOf(
                "attempt_rows_enqueued" to it.attemptRowsEnqueued,
                "attempt_rows_written" to attemptRowsWritten,
                "event_rows_enqueued" to it.eventRowsEnqueued,
                "event_rows_written" to eventRowsWritten,
                "rows_dropped_queue_full" to it.rowsDroppedQueueFull,
                "rows_dropped_write_error" to rowsDroppedWriteError,
                "rows_lost_total" to it.rowsDroppedQueueFull + rowsDroppedWriteError + it.attemptsUnrecorded,
                "write_error" to broken,
            )
        }
        metadata["files"] = mapOf(
            "attempts" to ATTEMPTS_FILE, "events" to EVENTS_FILE, "model_snapshot" to SNAPSHOT_FILE,
            "checksums" to EvaluationChecksums.FILE,
        )
        atomicWrite(File(dir, METADATA_FILE), EvaluationJson.write(metadata) + "\n")
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val RECORD_TYPE = "notouch_calibration_comparison_session"
        const val IN_FLIGHT_GRACE_MS = 1000L
        const val ATTEMPTS_FILE = "attempts.csv"
        const val EVENTS_FILE = "events.csv"
        const val METADATA_FILE = "metadata.json"
        const val SNAPSHOT_FILE = "model_snapshot.json"
        const val EVENT_PIPELINE_RESET = "pipeline_reset"
        const val EVENT_CAMERA_ERROR = "camera_error"
        const val EVENT_UNLOGGED_RESULT_DROPPED = "unlogged_result_dropped"
        const val EVENT_PREPARATION_SHOWN = "preparation_shown"
        const val EVENT_READY = "ready"
        const val RESET_SESSION_START = "session_start"
        const val RESET_BLOCK_START = "block_start"
    }
}

/** The session the camera path logs into. Written on the main thread, read on the camera worker. */
object EvaluationLog {
    @Volatile var current: EvaluationSession? = null
        private set
    @Volatile private var finished: EvaluationSession? = null
    /** The last ended session until it is finalized; its in-flight attempts still depend on later resets. */
    val draining get() = finished?.takeIf { !it.isFinalized }

    /** Null outside an evaluation session, so normal use is never logged. */
    fun sessionAt(now: Long) = current?.takeIf { it.covers(now) }
    fun start(session: EvaluationSession) { current = session }
    fun finish(reason: EvaluationEndReason, now: Long) {
        val session = current ?: return
        current = null
        finished = session
        session.finish(reason, now)
    }

    /** One low-priority thread for all evaluation file I/O. */
    val writer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "evaluation-log").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }

    fun root(filesDir: File) = File(File(filesDir, "evaluation"), "calibration-comparison")
    fun sessionDir(filesDir: File, participantId: String, sessionId: String) = File(File(root(filesDir), participantId), sessionId)

    private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    fun newSessionId(wallMs: Long) = "eval-${ID_TIME.format(Instant.ofEpochMilli(wallMs))}-${UUID.randomUUID().toString().take(8)}"
}
