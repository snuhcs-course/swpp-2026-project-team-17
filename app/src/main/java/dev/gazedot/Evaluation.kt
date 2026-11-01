package dev.gazedot

import android.content.pm.ApplicationInfo
import org.json.JSONObject

/*
 * Iteration 3 gaze-pipeline evaluation (debug builds only). The protocol mirrors
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
    const val VERSION = "1.0.0"
    val GRID = listOf(0.20, 0.40, 0.60, 0.80)
    const val TARGET_MS = 2500L
    const val SETTLING_MS = 1000L
    const val MEASUREMENT_MS = TARGET_MS - SETTLING_MS

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

/**
 * Full-display geometry captured when a run starts. [displayWidth]/[displayHeight] are
 * DisplaySpace.bounds, the basis of calibration anchors and predictions.
 */
data class EvaluationGeometry(
    val displayWidth: Int, val displayHeight: Int,
    val viewLeft: Int, val viewTop: Int, val viewWidth: Int, val viewHeight: Int,
) {
    /** Inverse of DisplayCoordinates.normalize for a view at (viewLeft, viewTop). */
    fun toView(x: Double, y: Double) = x * displayWidth - viewLeft to y * displayHeight - viewTop
    fun containsAllTargets(marginPx: Double) = EvaluationProtocol.targets.all { t ->
        val (x, y) = toView(t.x, t.y)
        x in marginPx..viewWidth - marginPx && y in marginPx..viewHeight - marginPx
    }
}

/** The run schedule. Attempts are assigned by their frame-acceptance time, never by arrival time. */
class EvaluationRun(val mode: EvaluationMode, val startMs: Long, val geometry: EvaluationGeometry) {
    fun covers(acceptedMs: Long) = acceptedMs >= startMs && acceptedMs < startMs + EvaluationProtocol.TOTAL_MS
    fun presentationAt(acceptedMs: Long): Presentation? {
        if (!covers(acceptedMs)) return null
        return EvaluationProtocol.presentations[((acceptedMs - startMs) / EvaluationProtocol.TARGET_MS).toInt()]
    }
    fun onsetMs(p: Presentation) = startMs + p.trialIndex * EvaluationProtocol.TARGET_MS
    fun phaseAt(acceptedMs: Long): EvaluationPhase? {
        val p = presentationAt(acceptedMs) ?: return null
        return if (acceptedMs - onsetMs(p) < EvaluationProtocol.SETTLING_MS) EvaluationPhase.SETTLING else EvaluationPhase.MEASUREMENT
    }
    fun isFinished(now: Long) = now - startMs >= EvaluationProtocol.TOTAL_MS
}

/** Main thread only. While active, no gaze action, cloud request or local UI gaze action may run. */
object EvaluationState {
    var mode: EvaluationMode? = null
        private set
    val active get() = mode != null
    val hidesPointer get() = mode?.showsPointer == false

    fun begin(m: EvaluationMode) {
        mode = m
        CloudSession.disable()
        clearInteraction()
    }

    /**
     * Leaves no partial dwell, scroll mode or gesture state behind for ordinary use. While actions were disabled
     * GazePipeline already cleared interaction state every frame. The filter is deliberately not reset here: that
     * would discard results of frames accepted just before a completed run ended, and count them against it.
     */
    fun end() {
        if (mode == null) return
        mode = null
        clearInteraction()
    }

    private fun clearInteraction() {
        TrackerState.cancelActions?.invoke()
        TrackerState.localDwellProgress = 0f
        TrackerState.localScrollMode = false
    }
}

enum class EvaluationBlock(val id: String, val message: String) {
    NOT_RUNNING("not_running", "카메라를 먼저 시작해 주세요."),
    NO_EYES("no_eyes", "눈이 검출될 때까지 기다려 주세요."),
    NOT_FRESHLY_CALIBRATED("not_freshly_calibrated", "이번 추적에서 새 9점 보정을 먼저 완료해 주세요. 저장된 모델·기본 포인터로는 평가하지 않습니다."),
    CALIBRATION_ALREADY_USED("calibration_already_used", "이 보정으로 이미 평가를 시작했습니다. 새 9점 보정을 완료한 뒤 다시 시작해 주세요."),
    NOT_PERSONAL_CALIBRATION("not_personal_calibration", "현재 모델이 9점 개인 보정 모델이 아닙니다."),
    SAVED_CALIBRATION_MISMATCH("saved_calibration_mismatch", "현재 모델이 저장된 보정값과 일치하지 않습니다. 다시 보정해 주세요."),
    CONFIG_MISMATCH("config_mismatch", "보정 당시와 기기·화면 크기·방향이 다릅니다. 다시 보정해 주세요."),
}

object EvaluationGate {
    /**
     * The primary predictor must be the personal nine-point calibration completed in this tracking run,
     * unused by an earlier run, and identical to the saved calibration refit. Never a fallback model.
     */
    fun check(
        running: Boolean, freshFeatures: Boolean, model: GazePredictor?, calibratedModel: GazePredictor?,
        calibrationSerial: Int, evaluatedSerial: Int, savedRefit: GazeModel?, savedConfig: String?, currentConfig: String,
    ): EvaluationBlock? = when {
        !running -> EvaluationBlock.NOT_RUNNING
        !freshFeatures -> EvaluationBlock.NO_EYES
        calibratedModel == null || model !== calibratedModel -> EvaluationBlock.NOT_FRESHLY_CALIBRATED
        calibrationSerial == evaluatedSerial -> EvaluationBlock.CALIBRATION_ALREADY_USED
        model !is GazeModel -> EvaluationBlock.NOT_PERSONAL_CALIBRATION
        savedRefit == null || savedRefit.parameters() != model.parameters() -> EvaluationBlock.SAVED_CALIBRATION_MISMATCH
        savedConfig != currentConfig -> EvaluationBlock.CONFIG_MISMATCH
        else -> null
    }
}

/** Frozen predictor input for replay: the saved calibration text plus the fitted parameters it must reproduce. */
object EvaluationModelSnapshot {
    const val SCHEMA_VERSION = 1

    fun refit(savedCalibration: String?): GazeModel? = savedCalibration?.let {
        runCatching { GazeModel.fit(ModelStore.calibrationAnchors(JSONObject(it))) }.getOrNull()
    }
    fun config(savedCalibration: String?): String? =
        savedCalibration?.let { runCatching { JSONObject(it).getString("config") }.getOrNull() }

    fun parametersMap(p: GazeModelParameters): Map<String, Any?> = mapOf(
        "mean_x" to p.meanX, "mean_y" to p.meanY, "std_x" to p.stdX, "std_y" to p.stdY,
        "wx" to p.wx, "wy" to p.wy,
        "reference_face_x" to p.referenceFaceX, "reference_face_y" to p.referenceFaceY,
        "reference_scale" to p.referenceScale, "fit_error" to p.fitError,
    )

    fun json(savedCalibration: String, model: GazeModel, label: String): String = EvaluationJson.write(mapOf(
        "snapshot_schema_version" to SCHEMA_VERSION,
        "predictor_kind" to "GazeModel",
        "predictor_source" to "personal_9_point_calibration",
        "model_label" to label,
        "saved_calibration_sha256" to EvaluationChecksums.sha256(savedCalibration.toByteArray(Charsets.UTF_8)),
        "saved_calibration_json" to savedCalibration,
        "parameters" to parametersMap(model.parameters()),
        "replay_note" to "Replay refits GazeModel.fit(ModelStore.calibrationAnchors(saved_calibration_json)) and requires parameters to match exactly.",
    )) + "\n"
}

fun evaluationEntryEnabled(appFlags: Int) = (appFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,32}")
fun validEvaluationIdentifier(value: String) = IDENTIFIER.matches(value)
