package dev.gazedot

import android.content.pm.ApplicationInfo

/*
 * Calibration-comparison gaze evaluation (debug builds only). The protocol mirrors
 * docs/calibration-comparison/protocol.json (notouch-calibration-comparison-eval 1.0.0); EvaluationProtocolTest keeps
 * the two identical. One session follows one fresh calibration and contains three 80 s head-position blocks, each the
 * unchanged Iteration 3 schedule (16 targets, two passes, 32 presentations). Targets are instructed fixation points and
 * head positions are instructed positions; neither is independently measured.
 */
enum class EvaluationMode(val id: String, val label: String, val showsPointer: Boolean) {
    BLIND_MEASUREMENT("blind_measurement", "블라인드 측정 (기본)", false),
    LIVE_PREVIEW("live_preview", "라이브 미리보기 (보조)", true),
}

enum class EvaluationPhase(val id: String) {
    PREPARATION("preparation"), COUNTDOWN("countdown"), SETTLING("settling"), MEASUREMENT("measurement"),
}

/** Instructed head position of one block, relative to the fixed phone. Small displacements, approximately front-facing. */
enum class EvaluationPosition(val id: String, val label: String, val instructions: List<String>) {
    NEUTRAL("neutral", "기본 위치", listOf("편한 정면 자세를 잡아 주세요", "보정할 때와 같은 기본 위치입니다")),
    HIGHER("higher", "조금 높은 위치", listOf("머리를 기본 위치보다 조금 위로 옮겨 주세요", "정면을 유지하고 기기는 움직이지 마세요")),
    LOWER("lower", "조금 낮은 위치", listOf("머리를 기본 위치보다 조금 아래로 옮겨 주세요", "정면을 유지하고 기기는 움직이지 마세요")),
}

/** The declared block orders. */
enum class EvaluationOrder(val id: String, val label: String, val positions: List<EvaluationPosition>) {
    NHL("nhl", "기본 → 위 → 아래", listOf(EvaluationPosition.NEUTRAL, EvaluationPosition.HIGHER, EvaluationPosition.LOWER)),
    LHN("lhn", "아래 → 위 → 기본", listOf(EvaluationPosition.LOWER, EvaluationPosition.HIGHER, EvaluationPosition.NEUTRAL)),
}

data class EvaluationTarget(val id: String, val x: Double, val y: Double)

/** One presentation of a block; [trialIndex] runs 0..31 across the block's two passes, [pass] is 1 or 2. */
data class Presentation(val trialIndex: Int, val pass: Int, val indexInPass: Int, val target: EvaluationTarget) {
    /** The Iteration 3 case id, shared by every block: "pass1-r0c0". */
    val caseSuffix get() = "pass$pass-${target.id}"
    /** Unique within a session: the instructed head position plus the Iteration 3 case id. */
    fun caseId(position: EvaluationPosition) = "${position.id}-$caseSuffix"
}

object EvaluationProtocol {
    const val ID = "notouch-calibration-comparison-eval"
    const val VERSION = "1.0.0"
    /** The measured pipeline: GazePipeline of feature/home-screen at SOURCE_BASE with a three-pass calibration. */
    const val PIPELINE_VARIANT = "home_screen_gaze_pipeline_e8fa01e"
    const val SOURCE_BASE = "e8fa01ef1e397f508aa5a3b3a595539b4ef4490a"
    const val EVALUATION_REFERENCE = "72411ec35d5d6e9547c322de3d7a14e65812b82e"
    val CALIBRATION_IDS = listOf("calibration_a_three_pass_static", "calibration_b_three_pass_positions")
    const val CALIBRATION_PASSES = 3
    const val CALIBRATION_POINTS_PER_PASS = 9
    const val CALIBRATION_ANCHORS = CALIBRATION_PASSES * CALIBRATION_POINTS_PER_PASS
    val GRID = listOf(0.20, 0.40, 0.60, 0.80)
    const val TARGET_MS = 2500L
    const val SETTLING_MS = 1000L
    const val MEASUREMENT_MS = TARGET_MS - SETTLING_MS
    const val BLOCKS = 3
    /** After Ready, the block's schedule starts this much later. */
    const val COUNTDOWN_MS = 3000L
    /** The session clock starts this long after the session_start reset, so logging is active before its first frame. */
    const val SESSION_START_LEAD_MS = 100L

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
    /** The 32 presentations of every block, in order. */
    val presentations: List<Presentation> =
        firstPass.mapIndexed { i, t -> Presentation(i, 1, i, t) } +
            secondPass.mapIndexed { i, t -> Presentation(firstPass.size + i, 2, i, t) }
    val BLOCK_MS = presentations.size * TARGET_MS
}

/** The normalization basis of calibration anchors and predictions: maximumWindowMetrics bounds and rotation. */
data class DisplayBasis(val width: Int, val height: Int, val rotation: Int)

/**
 * Full-display geometry captured when a session starts. [displayWidth]/[displayHeight] are the calibration basis
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

/** Where an acceptance time falls in a session. [blockIndex] is 0-based; [blockElapsedMs] is null during preparation. */
data class EvaluationLocation(
    val blockIndex: Int, val position: EvaluationPosition, val phase: EvaluationPhase,
    val presentation: Presentation?, val blockElapsedMs: Long?,
) {
    val caseId get() = presentation?.caseId(position)
}

/**
 * The session timeline, assigned by frame-acceptance time. Block 0's preparation starts at [startMs]; a block's
 * countdown starts when Ready is pressed, its 80 s schedule COUNTDOWN_MS later, and the next block's preparation at
 * that block's planned end. Ready times are written on the main thread and read on the camera worker.
 */
class EvaluationSchedule(val mode: EvaluationMode, val order: EvaluationOrder, val startMs: Long, val geometry: EvaluationGeometry) {
    private val ready = LongArray(EvaluationProtocol.BLOCKS) { -1L }

    @Synchronized fun readyMs(k: Int): Long? = ready[k].takeIf { it >= 0 }
    fun blockStartMs(k: Int): Long? = readyMs(k)?.plus(EvaluationProtocol.COUNTDOWN_MS)
    fun blockEndMs(k: Int): Long? = blockStartMs(k)?.plus(EvaluationProtocol.BLOCK_MS)
    fun preparationStartMs(k: Int): Long? = if (k == 0) startMs else blockEndMs(k - 1)
    val endMs: Long? get() = blockEndMs(EvaluationProtocol.BLOCKS - 1)
    fun position(k: Int) = order.positions[k]

    /** Ready for block [k]: accepted once, and only at or after the start of its preparation. */
    @Synchronized fun markReady(k: Int, at: Long): Boolean {
        val preparation = preparationStartMs(k) ?: return false
        if (ready[k] >= 0 || at < preparation) return false
        ready[k] = at
        return true
    }

    @Synchronized fun locate(t: Long): EvaluationLocation? {
        if (t < startMs) return null
        for (k in 0 until EvaluationProtocol.BLOCKS) {
            val position = order.positions[k]
            val r = readyMs(k)
            if (r == null || t < r) return EvaluationLocation(k, position, EvaluationPhase.PREPARATION, null, null)
            val start = r + EvaluationProtocol.COUNTDOWN_MS
            if (t < start) return EvaluationLocation(k, position, EvaluationPhase.COUNTDOWN, null, t - start)
            val elapsed = t - start
            if (elapsed < EvaluationProtocol.BLOCK_MS) {
                val p = EvaluationProtocol.presentations[(elapsed / EvaluationProtocol.TARGET_MS).toInt()]
                val phase = if (elapsed % EvaluationProtocol.TARGET_MS < EvaluationProtocol.SETTLING_MS) EvaluationPhase.SETTLING
                    else EvaluationPhase.MEASUREMENT
                return EvaluationLocation(k, position, phase, p, elapsed)
            }
        }
        return null
    }

    fun covers(t: Long) = t >= startMs && endMs?.let { t < it } != false
    fun isFinished(now: Long) = endMs?.let { now >= it } == true
    /** The latest block whose schedule has started by [t], or null. */
    fun startedBlockAt(t: Long): Int? = (EvaluationProtocol.BLOCKS - 1 downTo 0).firstOrNull { k -> blockStartMs(k)?.let { t >= it } == true }
    /** Presentations of block [k] that were fully shown before [endUptimeMs]. */
    fun presentationsCompleted(k: Int, endUptimeMs: Long): Int {
        val start = blockStartMs(k) ?: return 0
        val shown = minOf(endUptimeMs, start + EvaluationProtocol.BLOCK_MS) - start
        return (shown / EvaluationProtocol.TARGET_MS).toInt().coerceIn(0, EvaluationProtocol.presentations.size)
    }
}

/**
 * Main thread only. While a session is active, including preparation and countdown, the overlay pointer follows the
 * mode and gaze clicks, scrolling and dictation are suppressed (TrackerState.gazeActionsEnabled / gazeControlAllowed
 * and TrackingService.perform). Camera processing and the numerical pipeline keep running unchanged.
 */
object EvaluationState {
    @Volatile var mode: EvaluationMode? = null
        private set
    val active get() = mode != null
    val hidesPointer get() = mode?.showsPointer == false
    val suppressesActions get() = active

    fun begin(m: EvaluationMode) { mode = m }
    fun end() { mode = null }
}

/**
 * One successful calibration, frozen for evaluation: identity, labelled fit inputs in collection order, timing and
 * the display basis its anchors were normalized by. Kept in memory like the model itself.
 */
class CalibrationRecord(
    val calibrationId: String,
    val model: GazeModel,
    anchors: List<CalibrationAnchor>,
    val basisWidth: Int, val basisHeight: Int, val rotation: Int,
    val viewLeft: Int, val viewTop: Int, val viewWidth: Int, val viewHeight: Int,
    val startedAtMs: Long, val completedAtMs: Long,
) {
    val anchors: List<CalibrationAnchor> = anchors.toList()
    /** Exactly what GazeModel.fit received. */
    val fitAnchors: List<Anchor> get() = anchors.map { it.anchor }
    val durationMs get() = completedAtMs - startedAtMs
    val movementRetries get() = anchors.sumOf { it.movementRetries }
}

enum class EvaluationRefusal(val id: String, val message: String) {
    UNSUPPORTED_API("unsupported_api", "Android 11(API 30) 이상에서만 평가할 수 있습니다. 보정 좌표계가 화면 전체 기준이 아닙니다."),
    NOT_RUNNING("not_running", "카메라를 먼저 시작해 주세요."),
    NO_EYES("no_eyes", "눈이 검출될 때까지 기다려 주세요."),
    NOT_FRESHLY_CALIBRATED("not_freshly_calibrated", "이번 추적에서 새 보정을 먼저 성공적으로 완료해 주세요."),
    WRONG_CANDIDATE("wrong_candidate", "이 빌드의 보정 후보로 완료한 보정이 아닙니다."),
    INVALID_CALIBRATION("invalid_calibration", "보정 기록이 27개 기준점(3회차 × 9점) 형식이 아닙니다. 다시 보정해 주세요."),
    CALIBRATION_ALREADY_USED("calibration_already_used", "이 보정으로 이미 평가 세션을 시작했습니다. 새 보정을 완료한 뒤 다시 시작해 주세요."),
    SNAPSHOT_MISMATCH("snapshot_mismatch", "보정 입력으로 다시 계산한 모델이 현재 모델과 다릅니다. 다시 보정해 주세요."),
    DISPLAY_CHANGED("display_changed", "보정 당시와 화면 크기·방향이 다릅니다. 다시 보정해 주세요."),
}

object EvaluationGate {
    /**
     * The measured model must be a successful calibration of this build's candidate completed in this tracking run
     * (27 anchors, three passes of nine points), unused by an earlier session, reproduced exactly by GazeModel.fit from
     * its frozen inputs, and on an unchanged display basis.
     */
    fun check(
        apiLevel: Int, running: Boolean, featuresPresent: Boolean, model: GazeModel?, record: CalibrationRecord?,
        calibrationSerial: Int, evaluatedSerial: Int, current: DisplayBasis?, candidateId: String = CalibrationPlan.ID,
    ): EvaluationRefusal? = when {
        apiLevel < 30 -> EvaluationRefusal.UNSUPPORTED_API
        !running -> EvaluationRefusal.NOT_RUNNING
        !featuresPresent -> EvaluationRefusal.NO_EYES
        record == null || model == null || model !== record.model -> EvaluationRefusal.NOT_FRESHLY_CALIBRATED
        record.calibrationId != candidateId || record.calibrationId !in EvaluationProtocol.CALIBRATION_IDS -> EvaluationRefusal.WRONG_CANDIDATE
        !EvaluationModelSnapshot.wellFormed(record) -> EvaluationRefusal.INVALID_CALIBRATION
        calibrationSerial == evaluatedSerial -> EvaluationRefusal.CALIBRATION_ALREADY_USED
        EvaluationModelSnapshot.refit(record)?.parameters() != model.parameters() -> EvaluationRefusal.SNAPSHOT_MISMATCH
        current != DisplayBasis(record.basisWidth, record.basisHeight, record.rotation) -> EvaluationRefusal.DISPLAY_CHANGED
        else -> null
    }
}

/** Frozen predictor input for replay: the labelled fit inputs plus the fitted parameters they must reproduce. */
object EvaluationModelSnapshot {
    const val SCHEMA_VERSION = 2

    /** 27 anchors labelled pass 1..3 × point 1..9 in collection order. */
    fun wellFormed(record: CalibrationRecord): Boolean =
        record.anchors.size == EvaluationProtocol.CALIBRATION_ANCHORS &&
            record.anchors.map { it.pass to it.point } == (1..EvaluationProtocol.CALIBRATION_PASSES).flatMap { p ->
                (1..EvaluationProtocol.CALIBRATION_POINTS_PER_PASS).map { p to it }
            }

    fun refit(record: CalibrationRecord): GazeModel? = GazeModel.fit(record.fitAnchors)
    fun refit(anchors: List<CalibrationAnchor>): GazeModel? = GazeModel.fit(anchors.map { it.anchor })

    fun anchorsJson(anchors: List<CalibrationAnchor>): List<Map<String, Any?>> = anchors.map {
        mapOf("pass" to it.pass, "point" to it.point, "calibration_position" to it.position.id,
            "movement_retries" to it.movementRetries, "accepted_uptime_ms" to it.acceptedAtMs,
            "eye" to mapOf("x" to it.anchor.eye.x, "y" to it.anchor.eye.y, "face_x" to it.anchor.eye.faceX,
                "face_y" to it.anchor.eye.faceY, "scale" to it.anchor.eye.scale),
            "x" to it.anchor.x, "y" to it.anchor.y)
    }
    /** Canonical text of the labelled fit inputs; its SHA-256 identifies the calibration. */
    fun anchorsText(anchors: List<CalibrationAnchor>) = EvaluationJson.write(anchorsJson(anchors))
    fun inputsSha256(anchors: List<CalibrationAnchor>) = EvaluationChecksums.sha256(anchorsText(anchors).toByteArray(Charsets.UTF_8))

    fun parametersMap(p: GazeModelParameters): Map<String, Any?> = mapOf(
        "mean_x" to p.meanX, "mean_y" to p.meanY, "std_x" to p.stdX, "std_y" to p.stdY,
        "wx" to p.wx, "wy" to p.wy,
        "reference_x" to p.referenceX, "reference_y" to p.referenceY,
        "reference_face_x" to p.referenceFaceX, "reference_face_y" to p.referenceFaceY,
        "reference_scale" to p.referenceScale, "fit_error" to p.fitError,
    )

    fun json(record: CalibrationRecord, serial: Int): String = EvaluationJson.write(linkedMapOf(
        "snapshot_schema_version" to SCHEMA_VERSION,
        "pipeline_variant" to EvaluationProtocol.PIPELINE_VARIANT,
        "calibration_id" to record.calibrationId,
        "predictor_kind" to "GazeModel",
        "calibration_serial" to serial,
        "calibration_timing" to mapOf("started_uptime_ms" to record.startedAtMs, "completed_uptime_ms" to record.completedAtMs,
            "duration_ms" to record.durationMs, "movement_retries" to record.movementRetries),
        "calibration_basis" to mapOf("width_px" to record.basisWidth, "height_px" to record.basisHeight,
            "rotation" to record.rotation, "view_left_px" to record.viewLeft, "view_top_px" to record.viewTop,
            "view_width_px" to record.viewWidth, "view_height_px" to record.viewHeight),
        "calibration_inputs_sha256" to inputsSha256(record.anchors),
        "anchors" to anchorsJson(record.anchors),
        "parameters" to parametersMap(record.model.parameters()),
        "replay_note" to "Replay refits GazeModel.fit on the 27 anchors in this order and requires every parameter to match exactly.",
    )) + "\n"
}

fun evaluationEntryEnabled(appFlags: Int) = (appFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,32}")
fun validEvaluationIdentifier(value: String) = IDENTIFIER.matches(value)
