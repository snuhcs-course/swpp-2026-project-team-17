package dev.gazedot

import java.io.File
import java.util.Random

/**
 * SYNTHETIC test driver, not participant data. Drives the real EvaluationSession, GazeModel and GazeSmoother the
 * way TrackingService's evaluation hooks do (accept on the worker, handle on the main thread, smoothing resets),
 * without a camera. Features are generated so the synthetic calibration maps them near each target.
 */
object SyntheticEvaluation {
    const val START_MS = 1_000_000L
    const val WALL_MS = 1_793_491_200_000L // 2026-11-01T00:00:00Z
    const val WIDTH = 1080
    const val HEIGHT = 2400
    val GEOMETRY = EvaluationGeometry(WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT)

    fun eye(x: Double, y: Double) = (x - .5) * .05 to (y - .5) * .04
    /** The candidate calibration's nine target positions, in its order, with ideal features. */
    fun anchors(): List<Anchor> = listOf(.5 to .5, .15 to .15, .5 to .15, .85 to .15, .85 to .5, .85 to .85, .5 to .85, .15 to .85, .15 to .5)
        .map { (tx, ty) -> val (ex, ey) = eye(tx, ty); Anchor(EyeFeatures(ex, ey, .5, .5, .3), tx, ty) }
    fun model() = GazeModel.fit(anchors())!!
    fun record(model: GazeModel = model(), anchors: List<Anchor> = anchors()) =
        CalibrationRecord(model, anchors, WIDTH, HEIGHT, 0, 0, 0, WIDTH, HEIGHT, START_MS - 5_000)

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
                "overlay_view" to mapOf("left_px" to 0, "top_px" to 0, "width_px" to WIDTH, "height_px" to HEIGHT),
                "overlay_matches_basis" to true),
            "predictor" to mapOf("kind" to "GazeModel", "source" to EvaluationProtocol.PREDICTOR_SOURCE,
                "calibration_serial" to 1, "calibration_completed_uptime_ms" to record.completedAtMs,
                "calibration_age_ms_at_start" to START_MS - record.completedAtMs, "evaluation_runs_on_this_calibration_before" to 0,
                "verified_snapshot_refit" to true, "calibration_inputs_sha256" to EvaluationModelSnapshot.inputsSha256(record.anchors),
                "anchors" to record.anchors.size, "fit_error" to record.model.fitError, "snapshot_file" to EvaluationSession.SNAPSHOT_FILE),
            "conditions" to mapOf("viewing_distance_cm" to 40.0, "lighting" to "synthetic", "glasses" to "none", "notes" to "synthetic fixture",
                "declared" to "synthetic"),
            "pipeline" to mapOf("variant" to EvaluationProtocol.PIPELINE_VARIANT, "frame_interval_min_ms" to TrackingService.FRAME_INTERVAL_MS,
                "stale_result_rule" to "none; every delivered result is processed",
                "watchdog_no_result_ms" to TrackingService.WATCHDOG_NO_RESULT_MS,
                "model_clamp" to "GazeModel.predict clamps each axis to [0, 1]",
                "smoothing" to "GazeSmoother: s + 0.28 * (p - s) per axis; cleared by a null prediction and by the watchdog",
                "smoothing_reset_at_run_start" to true, "run_start_lead_ms" to EvaluationProtocol.RUN_START_LEAD_MS,
                "pointer_visible" to mode.showsPointer),
        ))

    fun session(dir: File, mode: EvaluationMode = EvaluationMode.BLIND_MEASUREMENT, id: String = "eval-synthetic-0001",
                maxPendingRows: Int = 4096, participant: String = "SYNTHETIC", label: String = "S1",
                onFailure: (String) -> Unit = {}): EvaluationSession {
        val record = record()
        val run = EvaluationRun(mode, START_MS, GEOMETRY)
        return EvaluationSession(id, run, dir, info(mode, record, participant, label), record.model,
            EvaluationModelSnapshot.json(record, 1), { it.run() }, { _, block -> block() }, { WALL_MS }, { _, e -> onFailure(e) }, maxPendingRows)
    }

    /** DotOverlay.drawnAt for an overlay view at (0, 0) covering the basis. */
    fun rendered(p: Pair<Double, Double>) = (0 + p.first.toFloat() * WIDTH).toDouble() to (0 + p.second.toFloat() * HEIGHT).toDouble()

    /** Mirrors TrackingService.process and its main-thread handler for one session. */
    class Driver(val session: EvaluationSession) {
        val smoother = GazeSmoother()
        inner class Pending(val attempt: EvaluationAttempt?, val accepted: Long, val features: EyeFeatures?)

        fun reset(at: Long, reason: String) { session.smoothingReset(at, reason); smoother.reset() }

        fun accept(at: Long, features: EyeFeatures?, detector: EvaluationDetector = EvaluationDetector.FACE,
                   featureRejection: String? = null): Pending {
            val log = session.takeIf { it.covers(at) }
            log?.frameReceived()
            val a = log?.beginAttempt(at, at * 1_000_000L)
            a?.observed(detector, featureRejection, features, if (detector == EvaluationDetector.NOT_READY) null else 11_500_000L,
                at * 1_000_000L + 12_000_000L)
            return Pending(a, at, features)
        }

        /** The main-thread handler; [active] is TrackerState.model at that moment. Returns the smoothed point. */
        fun handle(p: Pending, at: Long, active: GazeModel? = session.model, closing: Boolean = false): Pair<Double, Double>? {
            val handled = at * 1_000_000L + 250_000L
            val a = p.attempt
            a?.reachedMain(handled)
            if (closing) { a?.discarded(EvaluationOutcome.DISCARDED_CLOSING, at); return null }
            if (a == null && p.accepted < session.run.endMs) { session.unloggedResultDropped(p.accepted, at); return null }
            val position = if (p.features != null) active?.predict(p.features) else null
            val smoothed = smoother.update(position)
            if (a != null) {
                if (active == null || active !== session.model) a.discarded(EvaluationOutcome.DISCARDED_MODEL_CHANGED, at)
                else a.processed(at, p.features?.let { active.rejection(it) }, p.features?.let { active.linearOutput(it) }, position,
                    smoothed, smoothed?.takeIf { session.run.mode.showsPointer }?.let(::rendered), handled + 400_000L)
            }
            return smoothed
        }
    }

    /**
     * A deterministic run from [START_MS] for [durationMs], one frame every [frameMs], ending with [end]. Includes a
     * result in flight at run start, a landmarker that is not ready, frames without a face, a rejected eye aperture,
     * a face shift the model rejects, gaze beyond the screen (model clamp active), a camera error, a watchdog reset with a
     * result still in flight, a frame gap longer than the watchdog limit, and cadence skips.
     */
    fun simulate(session: EvaluationSession, durationMs: Long, frameMs: Long, end: EvaluationEndReason, seed: Long = 7): Driver {
        val random = Random(seed)
        val driver = Driver(session)
        driver.reset(START_MS - EvaluationProtocol.RUN_START_LEAD_MS, "run_start")
        // Accepted before the run clock started, delivered after: not an attempt of the run, dropped before smoothing.
        driver.handle(driver.accept(START_MS - 40, EyeFeatures(0.0, 0.0, .5, .5, .3)), START_MS - 10)
        var k = 0
        var t = START_MS + 3
        while (t < START_MS + durationMs) {
            k++
            if (k in 70..83) {
                if (k == 83) driver.reset(t - 40, "watchdog_no_result") // no result for > 800 ms
                t += frameMs; continue
            }
            if (k % 5 == 0) { session.frameReceived(); session.frameSkipped(false) }
            val target = session.run.presentationAt(t)!!.target
            val offScreen = k in 40..44
            val (ex, ey) = eye(if (offScreen) 1.12 else target.x, if (offScreen) -0.08 else target.y)
            val faceX = if (k % 41 == 0) .65 else .5 + random.nextGaussian() * .002
            val features = EyeFeatures(ex + random.nextGaussian() * .0012, ey + random.nextGaussian() * .0012, faceX,
                .5 + random.nextGaussian() * .002, .3 + random.nextGaussian() * .001)
            val pending = when {
                k == 10 -> driver.accept(t, null, EvaluationDetector.NOT_READY)
                k % 37 == 0 -> driver.accept(t, null, EvaluationDetector.NO_FACE)
                k % 23 == 0 -> driver.accept(t, null, EvaluationDetector.FACE, "eye_33_133_aperture_ratio_lt_0.10")
                else -> driver.accept(t, features)
            }
            if (k == 60) session.event(t + 2, EvaluationSession.EVENT_CAMERA_ERROR, "ERROR_CAMERA_IN_USE")
            if (k == 50) { driver.reset(t + 5, "watchdog_no_result"); driver.handle(pending, t + 20) } // processed after the reset
            else driver.handle(pending, t + 14 + (k % 7))
            t += frameMs
        }
        session.finish(end, START_MS + if (end == EvaluationEndReason.COMPLETED) EvaluationProtocol.TOTAL_MS else durationMs)
        return driver
    }

    fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "repository root not found from ${System.getProperty("user.dir")}" }
    }
}
