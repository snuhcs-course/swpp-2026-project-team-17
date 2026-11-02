package dev.gazedot

import android.content.pm.ApplicationInfo

/*
 * Iteration 3 gaze-pipeline evaluation of the home-screen candidate (debug builds only). The protocol mirrors
 * docs/evaluation/iteration3/protocol.json; EvaluationProtocolTest keeps the two identical.
 * Targets are instructed fixation points, not independently measured eye direction.
 */
enum class EvaluationMode(val id: String, val label: String, val showsPointer: Boolean) {
    BLIND_MEASUREMENT("blind_measurement", "블라인드 측정 (기본)", false),
    LIVE_PREVIEW("live_preview", "라이브 미리보기 (보조)", true),
}

enum class EvaluationPhase(val id: String) { SETTLING("settling"), MEASUREMENT("measurement") }

data class EvaluationTarget(val id: String, val x: Double, val y: Double)

/** One target presentation; [trialIndex] runs 0..31 across both passes, [pass] is 1 or 2. */
data class Presentation(val trialIndex: Int, val pass: Int, val indexInPass: Int, val target: EvaluationTarget) {
    val caseId get() = "pass$pass-${target.id}"
}

object EvaluationProtocol {
    const val ID = "notouch-gaze-eval-iter3"
    const val VERSION = "2.0.0"
    /** The measured pipeline: this branch's home-screen candidate, not the integrated main pipeline. */
    const val PIPELINE_VARIANT = "home_screen_candidate"
    const val SOURCE_BASE = "30be1cf726a47ab5751bd8e8bb4f537d0c82c810"
    const val EVALUATION_REFERENCE = "f59bf6acb40c4c1a7eaee5547caea4a382139db1"
    const val PREDICTOR_SOURCE = "candidate_9_point_calibration"
    val GRID = listOf(0.20, 0.40, 0.60, 0.80)
    const val TARGET_MS = 2500L
    const val SETTLING_MS = 1000L
    const val MEASUREMENT_MS = TARGET_MS - SETTLING_MS
    /** The run clock starts this long after the run screen is laid out, so logging is active before the first frame. */
    const val RUN_START_LEAD_MS = 100L

    /** Row-major: id "r{row}c{col}", x from the column, y from the row. */
    val targets: List<EvaluationTarget> = GRID.indices.flatMap { r ->
        GRID.indices.map { c -> EvaluationTarget("r${r}c$c", GRID[c], GRID[r]) }
    }
    /** Serpentine from top-left: even rows left to right, odd rows right to left. */
    val firstPass: List<EvaluationTarget> = GRID.indices.flatMap { r ->
        val columns = if (r % 2 == 0) GRID.indices else GRID.indices.reversed()
        columns.map { c -> targets[r * GRID.size + c] }
    }
    val secondPass: List<EvaluationTarget> = firstPass.reversed()
    val presentations: List<Presentation> =
        firstPass.mapIndexed { i, t -> Presentation(i, 1, i, t) } +
            secondPass.mapIndexed { i, t -> Presentation(firstPass.size + i, 2, i, t) }
    val TOTAL_MS = presentations.size * TARGET_MS
}

/** The normalization basis of calibration anchors and predictions: maximumWindowMetrics bounds and rotation. */
data class DisplayBasis(val width: Int, val height: Int, val rotation: Int)

/**
 * Full-display geometry captured when a run starts. [displayWidth]/[displayHeight] are the calibration basis
 * (WindowManager.maximumWindowMetrics bounds), which calibration anchors and predictions are normalized by.
 */
data class EvaluationGeometry(
    val displayWidth: Int, val displayHeight: Int,
    val viewLeft: Int, val viewTop: Int, val viewWidth: Int, val viewHeight: Int,
) {
    /** Where a normalized point is drawn in a view at (viewLeft, viewTop): the calibration screen's own mapping. */
    fun toView(x: Double, y: Double) = x * displayWidth - viewLeft to y * displayHeight - viewTop
    fun containsAllTargets(marginPx: Double) = EvaluationProtocol.targets.all { t ->
        val (x, y) = toView(t.x, t.y)
        x in marginPx..viewWidth - marginPx && y in marginPx..viewHeight - marginPx
    }
}

/** The run schedule. Attempts are assigned by their frame-acceptance time, never by arrival time. */
class EvaluationRun(val mode: EvaluationMode, val startMs: Long, val geometry: EvaluationGeometry) {
    val endMs get() = startMs + EvaluationProtocol.TOTAL_MS
    fun covers(acceptedMs: Long) = acceptedMs >= startMs && acceptedMs < endMs
    fun presentationAt(acceptedMs: Long): Presentation? {
        if (!covers(acceptedMs)) return null
        return EvaluationProtocol.presentations[((acceptedMs - startMs) / EvaluationProtocol.TARGET_MS).toInt()]
    }
    fun onsetMs(p: Presentation) = startMs + p.trialIndex * EvaluationProtocol.TARGET_MS
    fun phaseAt(acceptedMs: Long): EvaluationPhase? {
        val p = presentationAt(acceptedMs) ?: return null
        return if (acceptedMs - onsetMs(p) < EvaluationProtocol.SETTLING_MS) EvaluationPhase.SETTLING else EvaluationPhase.MEASUREMENT
    }
    fun isFinished(now: Long) = now >= endMs
}

/** Main thread only. While active the overlay pointer follows the mode; nothing else in the pipeline changes. */
object EvaluationState {
    var mode: EvaluationMode? = null
        private set
    val active get() = mode != null
    val hidesPointer get() = mode?.showsPointer == false

    fun begin(m: EvaluationMode) { mode = m }
    fun end() { mode = null }
}

enum class EvaluationBlock(val id: String, val message: String) {
    UNSUPPORTED_API("unsupported_api", "Android 11(API 30) 이상에서만 평가할 수 있습니다. 보정 좌표계가 화면 전체 기준이 아닙니다."),
    NOT_RUNNING("not_running", "카메라를 먼저 시작해 주세요."),
    NO_EYES("no_eyes", "눈이 검출될 때까지 기다려 주세요."),
    NOT_FRESHLY_CALIBRATED("not_freshly_calibrated", "이번 추적에서 새 9점 보정을 먼저 완료해 주세요."),
    CALIBRATION_ALREADY_USED("calibration_already_used", "이 보정으로 이미 평가를 시작했습니다. 새 9점 보정을 완료한 뒤 다시 시작해 주세요."),
    SNAPSHOT_MISMATCH("snapshot_mismatch", "보정 입력으로 다시 계산한 모델이 현재 모델과 다릅니다. 다시 보정해 주세요."),
    DISPLAY_CHANGED("display_changed", "보정 당시와 화면 크기·방향이 다릅니다. 다시 보정해 주세요."),
}

object EvaluationGate {
    /**
     * The measured model must be the nine-point calibration completed in this tracking run, unused by an
     * earlier run, reproduced exactly by GazeModel.fit from its frozen inputs, and on an unchanged display basis.
     */
    fun check(
        apiLevel: Int, running: Boolean, featuresPresent: Boolean, model: GazeModel?, calibration: CalibrationRecord?,
        calibrationSerial: Int, evaluatedSerial: Int, current: DisplayBasis?,
    ): EvaluationBlock? = when {
        apiLevel < 30 -> EvaluationBlock.UNSUPPORTED_API
        !running -> EvaluationBlock.NOT_RUNNING
        !featuresPresent -> EvaluationBlock.NO_EYES
        calibration == null || model == null || model !== calibration.model -> EvaluationBlock.NOT_FRESHLY_CALIBRATED
        calibrationSerial == evaluatedSerial -> EvaluationBlock.CALIBRATION_ALREADY_USED
        EvaluationModelSnapshot.refit(calibration.anchors)?.parameters() != model.parameters() -> EvaluationBlock.SNAPSHOT_MISMATCH
        current != DisplayBasis(calibration.basisWidth, calibration.basisHeight, calibration.rotation) -> EvaluationBlock.DISPLAY_CHANGED
        else -> null
    }
}

/** Frozen predictor input for replay: the calibration's exact fit inputs plus the fitted parameters they must reproduce. */
object EvaluationModelSnapshot {
    const val SCHEMA_VERSION = 1

    fun refit(anchors: List<Anchor>): GazeModel? = GazeModel.fit(anchors)

    fun anchorsJson(anchors: List<Anchor>): List<Map<String, Any?>> = anchors.map {
        mapOf("eye" to mapOf("x" to it.eye.x, "y" to it.eye.y, "face_x" to it.eye.faceX, "face_y" to it.eye.faceY,
            "scale" to it.eye.scale), "x" to it.x, "y" to it.y)
    }
    /** Canonical text of the fit inputs; its SHA-256 identifies the calibration. */
    fun anchorsText(anchors: List<Anchor>) = EvaluationJson.write(anchorsJson(anchors))
    fun inputsSha256(anchors: List<Anchor>) = EvaluationChecksums.sha256(anchorsText(anchors).toByteArray(Charsets.UTF_8))

    fun parametersMap(p: GazeModelParameters): Map<String, Any?> = mapOf(
        "mean_x" to p.meanX, "mean_y" to p.meanY, "std_x" to p.stdX, "std_y" to p.stdY,
        "wx" to p.wx, "wy" to p.wy,
        "reference_x" to p.referenceX, "reference_y" to p.referenceY,
        "reference_face_x" to p.referenceFaceX, "reference_face_y" to p.referenceFaceY,
        "reference_scale" to p.referenceScale, "fit_error" to p.fitError,
    )

    fun json(record: CalibrationRecord, serial: Int): String = EvaluationJson.write(mapOf(
        "snapshot_schema_version" to SCHEMA_VERSION,
        "pipeline_variant" to EvaluationProtocol.PIPELINE_VARIANT,
        "predictor_kind" to "GazeModel",
        "predictor_source" to EvaluationProtocol.PREDICTOR_SOURCE,
        "calibration_serial" to serial,
        "calibration_basis" to mapOf("width_px" to record.basisWidth, "height_px" to record.basisHeight,
            "rotation" to record.rotation, "view_left_px" to record.viewLeft, "view_top_px" to record.viewTop,
            "view_width_px" to record.viewWidth, "view_height_px" to record.viewHeight),
        "calibration_inputs_sha256" to inputsSha256(record.anchors),
        "anchors" to anchorsJson(record.anchors),
        "parameters" to parametersMap(record.model.parameters()),
        "replay_note" to "Replay refits GazeModel.fit(anchors) and requires every parameter to match exactly.",
    )) + "\n"
}

fun evaluationEntryEnabled(appFlags: Int) = (appFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,32}")
fun validEvaluationIdentifier(value: String) = IDENTIFIER.matches(value)
