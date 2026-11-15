package dev.gazedot

import java.io.File
import java.util.Random

/**
 * SYNTHETIC test driver, not participant data. Drives the real EvaluationSession, GazeModel and GazePipeline the way
 * TrackingService's evaluation hooks and EvaluationActivity do (accept on the worker, handle on the main thread,
 * pipeline resets, Ready, block starts), without a camera. Features are generated so the synthetic 27-anchor
 * calibration maps them near each target.
 */
object SyntheticEvaluation {
    const val START_MS = 1_000_000L
    const val WALL_MS = 1_793_491_200_000L // 2026-11-01T00:00:00Z
    const val WIDTH = 1080
    const val HEIGHT = 2400
    val GEOMETRY = EvaluationGeometry(WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT)

    fun eye(x: Double, y: Double) = (x - .5) * .05 to (y - .5) * .04
    /** Face position of an instructed head position (higher = face higher in the image); within the model's range. */
    fun faceY(position: EvaluationPosition) = when (position) {
        EvaluationPosition.NEUTRAL -> .5; EvaluationPosition.HIGHER -> .47; EvaluationPosition.LOWER -> .53
    }

    /**
     * Three passes of the candidate's nine targets with ideal features plus a small deterministic per-pass offset,
     * labelled with this build's calibration positions. The numbers are the same in the sibling candidate's build (only
     * the position labels differ), so equivalent synthetic sessions have identical attempts and events in both.
     */
    fun anchors(): List<CalibrationAnchor> = (1..3).flatMap { pass ->
        CalibrationRules.TARGETS.mapIndexed { i, (tx, ty) ->
            val (ex, ey) = eye(tx, ty)
            CalibrationAnchor(pass, i + 1, CalibrationPlan.positions[pass - 1],
                Anchor(EyeFeatures(ex + (pass - 2) * 1e-4, ey - (pass - 2) * 1e-4, .5, .5, .3), tx, ty),
                movementRetries = if (pass == 2 && i == 2) 1 else 0, acceptedAtMs = START_MS - 70_000 + (pass * 9 + i) * 2_600L)
        }
    }
    fun model(anchors: List<CalibrationAnchor> = anchors()) = GazeModel.fit(anchors.map { it.anchor })!!
    fun record(model: GazeModel = model(), anchors: List<CalibrationAnchor> = anchors(), id: String = CalibrationPlan.ID) =
        CalibrationRecord(id, model, anchors, WIDTH, HEIGHT, 0, 0, 0, WIDTH, HEIGHT, START_MS - 80_000, START_MS - 5_000)

    fun info(mode: EvaluationMode, record: CalibrationRecord, participant: String = "SYNTHETIC", label: String = "S1") =
        EvaluationSessionInfo(participant, label, BuildConfig.EVAL_PROTOCOL_SHA256, linkedMapOf(
            "build" to mapOf("git_commit" to "synthetic-fixture", "git_dirty_at_build" to "false",
                "source_base" to EvaluationProtocol.SOURCE_BASE, "source_base_is_ancestor" to "true",
                "evaluation_reference" to EvaluationProtocol.EVALUATION_REFERENCE, "build_type" to "synthetic"),
            "app" to mapOf("application_id" to "dev.gazedot", "version_name" to "synthetic", "version_code" to 0L),
            "libraries" to mapOf("mediapipe_tasks_vision" to "synthetic", "camerax" to "synthetic", "face_landmarker_task_sha256" to "synthetic"),
            "device" to mapOf("manufacturer" to "synthetic", "model" to "fixture", "api_level" to 35, "android_release" to "15"),
            "display" to mapOf("basis" to "WindowManager.maximumWindowMetrics bounds (calibration basis)", "width_px" to WIDTH,
                "height_px" to HEIGHT, "rotation" to 0, "density" to 2.75,
                "evaluation_view" to mapOf("left_px" to 0, "top_px" to 0, "width_px" to WIDTH, "height_px" to HEIGHT),
                "calibration_view" to mapOf("left_px" to 0, "top_px" to 0, "width_px" to WIDTH, "height_px" to HEIGHT),
                "overlay_view" to mapOf("left_px" to 0, "top_px" to 0, "width_px" to WIDTH, "height_px" to HEIGHT)),
            "calibration" to linkedMapOf("calibration_id" to record.calibrationId,
                "calibration_positions_by_pass" to CalibrationPlan.positions.map { it.id }, "calibration_serial" to 1,
                "started_uptime_ms" to record.startedAtMs, "completed_uptime_ms" to record.completedAtMs,
                "duration_ms" to record.durationMs, "age_ms_at_session_start" to START_MS - record.completedAtMs,
                "movement_retries" to record.movementRetries, "anchors" to record.anchors.size, "passes" to 3, "points_per_pass" to 9,
                "calibration_inputs_sha256" to EvaluationModelSnapshot.inputsSha256(record.anchors), "fit_error" to record.model.fitError,
                "fit_error_note" to "calibration diagnostic (RMS residual of the fit inputs); not a measured gaze accuracy",
                "verified_snapshot_refit" to true, "evaluation_sessions_on_this_calibration_before" to 0,
                "snapshot_file" to EvaluationSession.SNAPSHOT_FILE),
            "predictor" to mapOf("kind" to "GazeModel", "calibration_id" to record.calibrationId),
            "conditions" to mapOf("viewing_distance_cm" to 40.0, "lighting" to "synthetic", "glasses" to "none",
                "head_position_markers" to "synthetic markers", "notes" to "synthetic fixture",
                "head_positions" to "instructed (neutral, slightly higher, slightly lower), not measured; face_x/face_y/scale are logged per attempt",
                "declared" to "synthetic"),
            "pipeline" to mapOf("variant" to EvaluationProtocol.PIPELINE_VARIANT, "frame_interval_min_ms" to TrackingService.FRAME_INTERVAL_MS,
                "watchdog_no_result_ms" to TrackingService.WATCHDOG_NO_RESULT_MS, "pointer_visible" to mode.showsPointer),
        ))

    fun session(dir: File, mode: EvaluationMode = EvaluationMode.BLIND_MEASUREMENT, id: String = "eval-synthetic-0001",
                order: EvaluationOrder = EvaluationOrder.NHL, maxPendingRows: Int = 8192, participant: String = "SYNTHETIC",
                label: String = "S1", record: CalibrationRecord = record(), onFailure: (String) -> Unit = {}): EvaluationSession {
        val schedule = EvaluationSchedule(mode, order, START_MS, GEOMETRY)
        return EvaluationSession(id, schedule, dir, info(mode, record, participant, label), record.model,
            EvaluationModelSnapshot.json(record, 1), { it.run() }, { _, block -> block() }, { WALL_MS }, { _, e -> onFailure(e) }, maxPendingRows)
    }

    /** DotOverlay.drawnAt for an overlay view at (0, 0) covering the basis. */
    fun rendered(p: Pair<Double, Double>) = (0 + (p.first.coerceIn(0.0, 1.0) * WIDTH - 0).toFloat()).toDouble() to
        (0 + (p.second.coerceIn(0.0, 1.0) * HEIGHT - 0).toFloat()).toDouble()

    fun open(features: EyeFeatures?) = if (features == null) EyeObservation(EyeState.LOST, null, 0.0, 0.0)
        else EyeObservation(EyeState.OPEN, features, .26, .25)

    /** Mirrors TrackingService.process, its main-thread handler and EvaluationActivity's tick for one session. */
    class Driver(val session: EvaluationSession) {
        val pipeline = GazePipeline()
        var epoch = 0L
            private set
        inner class Pending(val attempt: EvaluationAttempt?, val accepted: Long, val observation: EyeObservation, val epoch: Long)

        /** TrackingService.evaluationReset: logged, pipeline only. */
        fun evaluationReset(at: Long, reason: String) { session.pipelineReset(at, reason); pipeline.reset() }
        /** TrackingService.resetTracking: logged, new epoch, pipeline reset. */
        fun resetTracking(at: Long, reason: String) { session.pipelineReset(at, reason); epoch++; pipeline.reset() }
        /** EvaluationActivity's tick. */
        fun tick(at: Long) { session.dueBlockReset(at)?.let { evaluationReset(at, EvaluationSession.RESET_BLOCK_START) } }

        fun accept(at: Long, observation: EyeObservation, detector: EvaluationDetector = EvaluationDetector.FACE,
                   featureRejection: String? = null): Pending {
            val log = session.takeIf { it.covers(at) }
            log?.frameReceived()
            val a = log?.beginAttempt(at, at * 1_000_000L)
            a?.observed(detector, featureRejection, observation, if (detector == EvaluationDetector.NOT_READY) null else 11_500_000L,
                at * 1_000_000L + 12_000_000L)
            return Pending(a, at, observation, epoch)
        }

        /** The main-thread handler; [active] is TrackerState.model at that moment. Returns the sample when processed. */
        fun handle(p: Pending, at: Long, active: GazeModel? = session.model, closing: Boolean = false): GazeSample? {
            val handled = at * 1_000_000L + 250_000L
            val a = p.attempt
            a?.reachedMain(handled)
            if (closing) { a?.discarded(EvaluationOutcome.DISCARDED_CLOSING, null, at); return null }
            if (p.epoch != epoch) { a?.discarded(EvaluationOutcome.DISCARDED_EPOCH, null, at); return null }
            if (at - p.accepted > TrackingService.DELAYED_RESULT_MS) {
                a?.discarded(EvaluationOutcome.DISCARDED_DELAYED, at, at); resetTracking(at, "delayed_result"); return null
            }
            if (!session.admit(a, p.accepted, at) { reason -> evaluationReset(at, reason) }) return null
            val sample = pipeline.process(p.accepted, at, p.observation, active, false)
            if (a != null) {
                if (active == null || active !== session.model) a.discarded(EvaluationOutcome.DISCARDED_MODEL_CHANGED, at, at)
                else a.processed(at, at, sample.quality, sample.features?.let { active.rejection(it) },
                    sample.features?.let { active.linearOutput(it) }, sample.rawPoint, sample.point,
                    sample.point?.takeIf { session.schedule.mode.showsPointer }?.let(::rendered), handled + 400_000L)
            }
            return sample
        }
    }

    /**
     * A deterministic session from [START_MS]: the session_start reset, a result in flight at the session start, then
     * for each block a preparation of [preparationMs] (operator presses Ready), the countdown with a result in flight
     * across the block start, and the 80 s block. Blocks include a landmarker that is not ready, frames without a face,
     * closed eyes with a rejected aperture, a face shift the model rejects, gaze beyond the screen (model clamp active),
     * a camera error, a watchdog reset after a frame gap, a delayed result, a result of an older epoch and cadence skips.
     * Stops at [stopAtMs] (session-relative) with [end], or completes after the third block.
     */
    fun simulate(session: EvaluationSession, end: EvaluationEndReason, stopAtMs: Long? = null, frameMs: Long = 66,
                 preparationMs: List<Long> = listOf(2_000, 1_500, 1_200), seed: Long = 7): Driver {
        val random = Random(seed)
        val d = Driver(session)
        val s = session.schedule
        val stopAt = stopAtMs?.let { START_MS + it }
        d.evaluationReset(START_MS - EvaluationProtocol.SESSION_START_LEAD_MS, EvaluationSession.RESET_SESSION_START)
        d.handle(d.accept(START_MS - 40, open(EyeFeatures(0.0, 0.0, .5, .5, .3))), START_MS - 10) // accepted before the clock
        var t = START_MS + 3
        var k = 0
        var kb = 0 // frames since the current block's schedule started
        var block = 0
        var shownFor = -1
        fun stopped() = stopAt != null && t >= stopAt
        while (block < EvaluationProtocol.BLOCKS && !stopped()) {
            d.tick(t)
            val at = s.locate(t)!!
            if (at.blockIndex != block) block = at.blockIndex
            if (at.phase == EvaluationPhase.PREPARATION && shownFor != block) { session.preparationShown(block, t); shownFor = block }
            if (at.phase == EvaluationPhase.PREPARATION && t >= s.preparationStartMs(block)!! + preparationMs[block])
                session.markReady(block, t)
            k++
            val inBlock = at.presentation != null
            kb = if (inBlock) kb + 1 else 0
            val position = at.position
            val target = at.presentation?.target
            val bx = target?.x ?: .5; val by = target?.y ?: .5
            if (inBlock && kb in 70..83) { // a camera stall longer than the watchdog limit
                if (kb == 83) d.resetTracking(t - 40, "watchdog_no_result")
                t += frameMs; continue
            }
            if (k % 5 == 0) { session.frameReceived(); session.frameSkipped(false) }
            val offScreen = inBlock && kb in 20..24
            val (ex, ey) = eye(if (offScreen) 1.12 else bx, if (offScreen) -0.08 else by)
            val faceX = if (k % 41 == 0) .65 else .5 + random.nextGaussian() * .002
            val features = EyeFeatures(ex + random.nextGaussian() * .0012, ey + random.nextGaussian() * .0012, faceX,
                faceY(position) + random.nextGaussian() * .002, .3 + random.nextGaussian() * .001)
            val pending = when {
                k == 10 -> d.accept(t, EyeObservation(EyeState.LOST, null, 0.0, 0.0), EvaluationDetector.NOT_READY)
                k % 37 == 0 -> d.accept(t, EyeObservation(EyeState.LOST, null, 0.0, 0.0), EvaluationDetector.NO_FACE)
                k % 23 == 0 -> d.accept(t, EyeObservation(EyeState.CLOSED, null, .05, .06), EvaluationDetector.FACE,
                    "eye_33_133_aperture_ratio_lt_0.10")
                else -> d.accept(t, open(features))
            }
            val blockStart = s.blockStartMs(block)
            when {
                // In flight across the block start: accepted in the countdown, handled when the block's reset applies.
                blockStart != null && at.phase == EvaluationPhase.COUNTDOWN && blockStart - t in 1..frameMs ->
                    d.handle(pending, blockStart)
                inBlock && kb == 50 -> {
                    session.event(t + 2, EvaluationSession.EVENT_CAMERA_ERROR, "ERROR_CAMERA_IN_USE")
                    d.handle(pending, t + 16)
                }
                // Delayed: dropped and resets the pipeline; the stalled main thread delays the next frames too.
                inBlock && kb == 100 -> { d.handle(pending, t + 260); t += 4 * frameMs }
                inBlock && kb == 130 -> { d.resetTracking(t + 5, "watchdog_no_result"); d.handle(pending, t + 20) } // older epoch
                else -> d.handle(pending, t + 14 + (k % 7))
            }
            t += frameMs
            if (s.isFinished(t)) break
        }
        val endAt = if (end == EvaluationEndReason.COMPLETED) s.endMs!! else (stopAt ?: t)
        d.tick(endAt)
        session.finish(end, endAt)
        return d
    }

    fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "repository root not found from ${System.getProperty("user.dir")}" }
    }
}
