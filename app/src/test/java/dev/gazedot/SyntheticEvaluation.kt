package dev.gazedot

import java.io.File
import java.util.Random

/**
 * SYNTHETIC test driver, not participant data. Drives the real EvaluationSession and GazePipeline the way
 * TrackingService's evaluation hooks do (accept on the worker, handle on the main thread, resets), without
 * a camera. Observations are generated so the synthetic model maps them near each target.
 */
object SyntheticEvaluation {
    const val CONFIG = "synthetic|fixture|1080x2400|0|features-v2"
    const val START_MS = 1_000_000L
    const val WALL_MS = 1_793_491_200_000L // 2026-11-01T00:00:00Z
    val GEOMETRY = EvaluationGeometry(1080, 2400, 0, 0, 1080, 2400)

    private fun eye(x: Double, y: Double) = (x - .5) * .05 to (y - .5) * .04
    fun anchors(): List<Anchor> = listOf(.15, .5, .85).flatMap { ty -> listOf(.15, .5, .85).map { tx ->
        val (ex, ey) = eye(tx, ty)
        Anchor(EyeFeatures.from(doubleArrayOf(ex, ey, ex, ey, .5, .5, .3, 0.0, 0.0, 0.0)), tx, ty)
    } }
    fun savedCalibration() = ModelStore.calibrationJson(CONFIG, anchors())
    fun model() = EvaluationModelSnapshot.refit(savedCalibration())!!

    fun info(mode: EvaluationMode, model: GazeModel) = EvaluationSessionInfo("SYNTHETIC", "S1", BuildConfig.EVAL_PROTOCOL_SHA256, linkedMapOf(
        "build" to mapOf("git_commit" to "synthetic-fixture", "git_dirty_at_build" to "false",
            "git_base_main" to "synthetic-fixture", "build_type" to "synthetic"),
        "app" to mapOf("application_id" to "dev.gazedot", "version_name" to "synthetic", "version_code" to 0L),
        "libraries" to mapOf("mediapipe_tasks_vision" to "synthetic", "camerax" to "synthetic", "face_landmarker_task_sha256" to "synthetic"),
        "device" to mapOf("manufacturer" to "synthetic", "model" to "fixture", "api_level" to 35, "android_release" to "15"),
        "display" to mapOf("basis" to "DisplaySpace.bounds", "width_px" to 1080, "height_px" to 2400, "rotation" to 0,
            "density" to 2.75, "evaluation_view" to mapOf("left_px" to 0, "top_px" to 0, "width_px" to 1080, "height_px" to 2400)),
        "predictor" to mapOf("kind" to "GazeModel", "source" to "personal_9_point_calibration", "label" to "synthetic",
            "config_key" to CONFIG, "calibration_serial" to 1, "calibration_completed_uptime_ms" to START_MS - 5000,
            "calibration_age_ms_at_start" to 5000L, "evaluation_runs_on_this_calibration_before" to 0,
            "verified_against_saved_calibration" to true,
            "saved_calibration_sha256" to EvaluationChecksums.sha256(savedCalibration().toByteArray(Charsets.UTF_8)),
            "fit_error" to model.fitError, "snapshot_file" to EvaluationSession.SNAPSHOT_FILE),
        "conditions" to mapOf("viewing_distance_cm" to 40.0, "lighting" to "synthetic", "glasses" to "none", "notes" to "synthetic fixture",
            "declared" to "synthetic"),
        "pipeline" to mapOf("frame_interval_min_ms" to TrackingService.FRAME_INTERVAL_MS, "stale_result_ms" to TrackingService.STALE_RESULT_MS,
            "filter" to "GazeStabilizer", "filter_reset_at_run_start" to true, "actions_dispatched" to false,
            "cloud_enabled" to false, "pointer_visible" to mode.showsPointer),
    ))

    fun session(dir: File, mode: EvaluationMode = EvaluationMode.BLIND_MEASUREMENT, id: String = "eval-synthetic-0001",
                maxPendingRows: Int = 4096, onFailure: (String) -> Unit = {}): EvaluationSession {
        val model = model()
        val run = EvaluationRun(mode, START_MS, GEOMETRY)
        return EvaluationSession(id, run, dir, info(mode, model), model,
            EvaluationModelSnapshot.json(savedCalibration(), model, "synthetic"),
            { it.run() }, { _, block -> block() }, { WALL_MS }, { _, e -> onFailure(e) }, maxPendingRows)
    }

    /** Mirrors TrackingService.process and its main-thread handler for one session. */
    class Driver(val session: EvaluationSession, private val predictor: GazePredictor) {
        val pipeline = GazePipeline()
        private var epoch = 0L
        inner class Pending(val attempt: EvaluationAttempt, val accepted: Long, val epoch: Long, val observation: EyeObservation)

        fun reset(at: Long, reason: String) {
            session.pipelineReset(at, reason); epoch++; pipeline.reset()
        }
        fun accept(at: Long, observation: EyeObservation, detector: EvaluationDetector = EvaluationDetector.FACE): Pending? {
            session.frameReceived()
            val a = session.beginAttempt(at, at * 1_000_000L) ?: return null
            a.observed(detector, observation, if (detector == EvaluationDetector.NOT_READY) null else 11_500_000L,
                at * 1_000_000L + 12_000_000L)
            return Pending(a, at, epoch, observation)
        }
        fun handle(p: Pending, at: Long): GazeSample? {
            val handled = at * 1_000_000L + 250_000L
            p.attempt.reachedMain(handled)
            if (p.epoch != epoch) { p.attempt.discarded(EvaluationOutcome.DISCARDED_EPOCH, at); return null }
            if (at - p.accepted > TrackingService.STALE_RESULT_MS) {
                p.attempt.discarded(EvaluationOutcome.STALE_DELIVERY, at); reset(at, "stale_delivery"); return null
            }
            val sample = pipeline.process(p.accepted, at, p.observation, predictor, false)
            p.attempt.processed(at, sample, session.run.mode.showsPointer && sample.point != null, handled + 400_000L)
            return sample
        }
    }

    /**
     * A deterministic run from [START_MS] for [durationMs], one frame every [frameMs], ending with [end].
     * Includes lost faces, closed eyes, a not-ready detector, out-of-range poses, off-screen gaze, a stale
     * delivery, a result discarded by a watchdog reset, cadence skips and a frame gap.
     */
    fun simulate(session: EvaluationSession, durationMs: Long, frameMs: Long, end: EvaluationEndReason, seed: Long = 7): Driver {
        val random = Random(seed)
        val driver = Driver(session, session.predictor)
        driver.reset(START_MS, "run_start")
        var k = 0
        var t = START_MS + 3
        while (t < START_MS + durationMs) {
            k++
            if (k in 70..74) { t += frameMs; continue } // no frames: a > 250 ms gap the filter must see
            if (k % 5 == 0) { session.frameReceived(); session.frameSkipped(false) }
            val target = session.run.presentationAt(t)!!.target
            val offScreen = k in 40..44
            val (ex, ey) = eye(if (offScreen) 1.12 else target.x, if (offScreen) -0.08 else target.y)
            val jitter = { random.nextGaussian() * .0012 }
            val faceX = if (k % 41 == 0) .7 else .5 + random.nextGaussian() * .002
            val features = EyeFeatures(ex + jitter(), ey + jitter(), faceX, .5, .3,
                ex + jitter(), ey + jitter(), ex + jitter(), ey + jitter(), .01, -.02, .003,
                if (k % 3 == 0) 38.5 + random.nextGaussian() else Double.NaN, .02, -.01)
            val (observation, detector) = when {
                k == 10 -> EyeObservation(EyeState.LOST, null, 0.0, 0.0) to EvaluationDetector.NOT_READY
                k % 37 == 0 -> EyeObservation(EyeState.LOST, null, 0.0, 0.0) to EvaluationDetector.NO_FACE
                k % 23 == 0 -> EyeObservation(EyeState.CLOSED, null, .05, .06) to EvaluationDetector.FACE
                else -> EyeObservation(EyeState.OPEN, features, .21, .22) to EvaluationDetector.FACE
            }
            val pending = driver.accept(t, observation, detector)
            if (pending != null) {
                when (k) {
                    30 -> driver.handle(pending, t + 300)                                   // stale delivery
                    50 -> { driver.reset(t + 5, "watchdog_no_result"); driver.handle(pending, t + 20) } // discarded
                    else -> driver.handle(pending, t + 14 + (k % 7))
                }
            }
            t += frameMs
        }
        session.finish(end, START_MS + if (end == EvaluationEndReason.COMPLETED) EvaluationProtocol.TOTAL_MS else durationMs)
        return driver
    }

    fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "repository root not found from ${System.getProperty("user.dir")}" }
    }
}
