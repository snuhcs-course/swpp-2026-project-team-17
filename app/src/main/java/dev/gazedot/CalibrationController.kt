package dev.gazedot

import kotlin.math.abs

/**
 * Per-point collection rules copied unchanged from the nine-point calibration of feature/home-screen
 * (e8fa01ef1e397f508aa5a3b3a595539b4ef4490a). Only [PASSES] and [PASS_BREAK_MS] are new.
 */
object CalibrationRules {
    /** The existing nine targets as fractions of the calibration view, in presentation order. */
    val TARGETS: List<Pair<Double, Double>> = listOf(.5 to .5, .15 to .15, .5 to .15, .85 to .15,
        .85 to .5, .85 to .85, .5 to .85, .15 to .85, .15 to .5)
    const val SETTLING_MS = 1_000L
    const val MIN_SAMPLES = 22
    const val MIN_POINT_MS = 2_500L
    /** A point that has not completed after this long (strictly greater) fails the whole calibration. */
    const val POINT_TIMEOUT_MS = 9_000L
    /** Within-point movement rule: every sample's face position must stay this close to the point's median. */
    const val MAX_FACE_SHIFT = .045
    /** The mid-pass blink break starts once this many targets of the pass are complete. */
    const val BLINK_BREAK_AFTER_POINTS = 4
    const val BLINK_BREAK_MS = 2_000L

    /** New: complete passes through [TARGETS]; the fit runs once, after the last. */
    const val PASSES = 3
    /** New: break after every pass except the last. */
    const val PASS_BREAK_MS = 3_000L
    val TOTAL_ANCHORS = PASSES * TARGETS.size
}

enum class CalibrationBreak { BLINK, PASS }

enum class CalibrationFailure { TIMEOUT, FIT_REJECTED }

/** One accepted anchor with its schedule labels. [pass] and [point] are 1-based; the fit sees only [anchor]. */
data class CalibrationAnchor(val pass: Int, val point: Int, val position: HeadPosition, val anchor: Anchor)

/** The result of one calibration attempt. [model] is non-null only if all anchors were collected and the fit succeeded. */
class CalibrationOutcome(
    val calibrationId: String,
    anchors: List<CalibrationAnchor>,
    val model: GazeModel?,
    val failure: CalibrationFailure?,
) {
    val anchors: List<CalibrationAnchor> = anchors.toList()
}

/** Everything the calibration screen draws. [target] is a view fraction, null during breaks. */
data class CalibrationScreen(
    val title: String,
    val position: String,
    val target: Pair<Double, Double>?,
    val sampleProgress: Float,
    val center: List<String>,
    val footer: List<String>,
)

/**
 * Schedules and collects a [CalibrationRules.PASSES]-pass calibration through the existing nine targets.
 * Main thread only. [clock] is read exactly where the original calibration view read SystemClock.uptimeMillis.
 * [toDisplay] converts a target's view fraction to normalized display coordinates when its anchor is accepted.
 * [fit] runs once, after the final anchor; no intermediate model is fitted or reported.
 */
class CalibrationController(
    private val clock: () -> Long,
    private val toDisplay: (Double, Double) -> Pair<Double, Double>,
    private val fit: (List<Anchor>) -> GazeModel?,
    private val onFinished: (CalibrationOutcome) -> Unit,
) {
    private var passIndex = 0
    private var pointIndex = 0
    private var since = clock()
    private var pendingBreak: CalibrationBreak? = null
    private var breakUntil = 0L
    private val samples = mutableListOf<EyeFeatures>()
    private val accepted = mutableListOf<CalibrationAnchor>()
    private var lastSampleAt = 0L
    var closed = false
        private set
    var hint = "머리는 고정하고 파란 점을 바라보세요"
        private set

    /** 1-based pass of the current (or, during a pass break, the next) target. */
    val pass get() = passIndex + 1
    /** 1-based point within [pass]. */
    val point get() = pointIndex + 1
    val target: Pair<Double, Double> get() = CalibrationRules.TARGETS[pointIndex]
    val position: HeadPosition get() = CalibrationPlan.positions[passIndex]
    val sampleCount get() = samples.size
    val anchors: List<CalibrationAnchor> get() = accepted.toList()
    /** The break being shown, until the controller notices that it has ended. */
    val currentBreak: CalibrationBreak? get() = pendingBreak
    val breakEndsAt: Long get() = breakUntil

    fun close() {
        closed = true
        samples.clear()
        accepted.clear()
        pendingBreak = null
        breakUntil = 0L
    }

    /** Ends an expired break and starts the next target's settling and timeout clocks at [now]. */
    private fun inBreak(now: Long): Boolean {
        if (pendingBreak == null) return false
        if (now < breakUntil) return true
        pendingBreak = null
        breakUntil = 0L
        // Start the next target's settling and timeout clocks after the break, even without eye detection.
        since = now
        hint = collectingHint()
        return false
    }

    private fun collectingHint() = "$point / ${CalibrationRules.TARGETS.size} · 파란 점을 계속 바라보세요"

    /** Called every 100 ms. Returns false once the calibration has finished or closed. */
    fun onTick(): Boolean {
        if (closed) return false
        val now = clock()
        if (!inBreak(now) && now - since > CalibrationRules.POINT_TIMEOUT_MS) {
            finish(null, CalibrationFailure.TIMEOUT)
            return false
        }
        return true
    }

    fun onFrame(sample: GazeSample?) {
        if (closed) return
        val now = clock()
        if (inBreak(now)) return
        val elapsed = now - since
        // Keep collecting open-eye features even when the old model rejects this pose.
        val f = sample?.calibrationFeatures(now, lastSampleAt)
        if (f == null) { hint = "눈 검출 대기 · 눈을 뜨고 얼굴을 카메라로 향해 주세요"; return }
        lastSampleAt = sample.timestampMs
        hint = collectingHint()
        if (elapsed < CalibrationRules.SETTLING_MS) return
        samples += f
        if (samples.size >= CalibrationRules.MIN_SAMPLES && elapsed >= CalibrationRules.MIN_POINT_MS) {
            val eye = medianFeatures(samples)
            // Reject motion within a fixation, rather than fitting mismatched samples.
            if (samples.any { abs(it.faceX - eye.faceX) > CalibrationRules.MAX_FACE_SHIFT ||
                    abs(it.faceY - eye.faceY) > CalibrationRules.MAX_FACE_SHIFT }) {
                samples.clear(); since = clock()
                hint = "머리가 움직였습니다 · 같은 점에서 다시 측정합니다"
                return
            }
            val xy = toDisplay(target.first, target.second)
            // Genuine median features, including face position and scale; never normalized across passes.
            accepted += CalibrationAnchor(pass, point, position, Anchor(eye, xy.first, xy.second))
            pointIndex++; samples.clear(); since = clock()
            if (pointIndex == CalibrationRules.TARGETS.size) {
                if (passIndex + 1 < CalibrationRules.PASSES) {
                    passIndex++; pointIndex = 0
                    startBreak(CalibrationBreak.PASS, since + CalibrationRules.PASS_BREAK_MS)
                } else {
                    // The only fit: all passes, every anchor kept separate, nothing averaged across passes.
                    val model = fit(accepted.map { it.anchor })
                    finish(model, if (model == null) CalibrationFailure.FIT_REJECTED else null)
                }
            } else if (pointIndex == CalibrationRules.BLINK_BREAK_AFTER_POINTS) {
                startBreak(CalibrationBreak.BLINK, since + CalibrationRules.BLINK_BREAK_MS)
            }
        }
    }

    private fun startBreak(kind: CalibrationBreak, until: Long) {
        pendingBreak = kind
        breakUntil = until
    }

    private fun finish(model: GazeModel?, failure: CalibrationFailure?) {
        val outcome = CalibrationOutcome(CalibrationPlan.ID, accepted, model, failure)
        close()
        onFinished(outcome)
    }

    /** What to draw at [now]. Pure: an expired break is still shown until a tick or frame ends it. */
    fun screen(now: Long): CalibrationScreen? {
        if (closed) return null
        val points = CalibrationRules.TARGETS.size
        val passes = CalibrationRules.PASSES
        val back = "중단하려면 뒤로 가기"
        val secondsLeft = ((breakUntil - now + 999) / 1000).coerceAtLeast(1)
        return when (pendingBreak) {
            CalibrationBreak.BLINK -> CalibrationScreen(
                title = "$pass/${passes}회차 · ${CalibrationRules.BLINK_BREAK_AFTER_POINTS}/$points 완료 · 잠시 쉬어가기",
                position = "자세: ${position.label}",
                target = null, sampleProgress = 0f,
                center = listOf("눈을 편하게 깜빡여 주세요",
                    "${secondsLeft}초 후 나머지 ${points - CalibrationRules.BLINK_BREAK_AFTER_POINTS}개를 이어서 진행합니다"),
                footer = listOf(position.holdInstruction, back))
            CalibrationBreak.PASS -> CalibrationScreen(
                title = "${pass - 1}/${passes}회차 완료 · 잠시 쉬어가기",
                position = "다음 자세: ${position.label}",
                target = null, sampleProgress = 0f,
                center = position.enterInstruction + "${secondsLeft}초 후 $pass/${passes}회차를 시작합니다",
                footer = listOf(CalibrationPlan.PASS_BREAK_FOOTER, back))
            null -> CalibrationScreen(
                title = "시선 보정 · $pass/${passes}회차 · $point/$points",
                position = "자세: ${position.label}",
                target = target,
                sampleProgress = (samples.size / CalibrationRules.MIN_SAMPLES.toFloat()).coerceAtMost(1f),
                center = emptyList(),
                footer = listOf(hint, back))
        }
    }
}
