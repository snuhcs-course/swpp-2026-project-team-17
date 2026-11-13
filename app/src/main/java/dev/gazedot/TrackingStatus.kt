package dev.gazedot

/** UI facts only. Detection/prediction thresholds remain in the existing pipeline. */
enum class TrackingPhase {
    STOPPED, STARTING, CALIBRATING, PAUSED, TRACKING, MISSING, EYES_PENDING,
    NEEDS_CALIBRATION, COORDINATES_UNAVAILABLE, SIGNAL_PENDING, ERROR,
}

enum class RecoverySetting { NONE, CAMERA, OVERLAY, ACCESSIBILITY }

data class TrackingFault(val message: String, val advice: String,
    val setting: RecoverySetting = RecoverySetting.NONE)

data class TrackingStatus(val phase: TrackingPhase, val message: String, val advice: String = "") {
    val overlayMessage get() = when (phase) {
        TrackingPhase.MISSING, TrackingPhase.NEEDS_CALIBRATION,
        TrackingPhase.COORDINATES_UNAVAILABLE, TrackingPhase.SIGNAL_PENDING,
        TrackingPhase.ERROR -> message
        else -> ""
    }
}

fun trackingStatus(running: Boolean, controls: Boolean, collecting: Boolean,
    calibrated: Boolean, sample: GazeSample?, eye: EyeState, now: Long,
    fault: TrackingFault?, signalPending: Boolean): TrackingStatus {
    if (fault != null) return TrackingStatus(TrackingPhase.ERROR, fault.message, fault.advice)
    if (!running) return TrackingStatus(TrackingPhase.STOPPED, "추적이 중지되어 있어요", "카메라를 시작해 주세요.")
    if (collecting) return TrackingStatus(TrackingPhase.CALIBRATING, "시선 보정 중이에요")
    if (!controls) return TrackingStatus(TrackingPhase.PAUSED, "조작을 잠시 멈췄어요",
        "홈이나 추적 알림에서 ‘조작 재개’를 눌러 주세요. 자동으로 재개하지 않아요.")
    if (signalPending || (sample != null && now - sample.timestampMs !in 0..250))
        return TrackingStatus(TrackingPhase.SIGNAL_PENDING, "영상 입력을 기다리고 있어요",
            "새 영상 결과가 도착하지 않았어요. 계속되면 카메라를 다시 시작해 주세요.")
    if (sample == null) return TrackingStatus(TrackingPhase.STARTING, "카메라·눈 검출 준비 중이에요")
    if (sample.quality == TrackingQuality.STALE) return TrackingStatus(TrackingPhase.SIGNAL_PENDING,
        "영상 처리가 지연되고 있어요", "계속되면 카메라를 다시 시작해 주세요.")
    if (eye == EyeState.LOST) return TrackingStatus(TrackingPhase.MISSING,
        "얼굴·눈을 인식하지 못하고 있어요", "조명, 카메라 가림과 얼굴 위치를 확인해 주세요.")
    if (eye != EyeState.OPEN) return TrackingStatus(TrackingPhase.EYES_PENDING,
        if (eye == EyeState.CLOSED) "눈 감김 감지 · 시선 입력 대기" else "눈 상태를 확인하고 있어요")
    if (!calibrated) return TrackingStatus(TrackingPhase.NEEDS_CALIBRATION,
        "시선 보정이 필요해요", "눈이 검출되면 ‘다시 보정하기’를 선택해 주세요.")
    if (sample.toScreenGaze()?.valid != true) return TrackingStatus(TrackingPhase.COORDINATES_UNAVAILABLE,
        "시선 좌표를 사용할 수 없어요", "보정할 때의 얼굴 위치를 확인해 주세요. 필요하면 직접 다시 보정할 수 있어요.")
    return TrackingStatus(TrackingPhase.TRACKING, "정상 추적 중이에요",
        "시선 위치가 맞지 않으면 ‘다시 보정하기’를 선택해 주세요.")
}

object RecognitionHelpSettings { const val LOSS_HELP_DELAY_MS = 20_000L }

/** Advances only on fresh LOST observations, never on a lack of camera results. */
class RecognitionLossPolicy(private val delayMs: Long = RecognitionHelpSettings.LOSS_HELP_DELAY_MS) {
    private var since: Long? = null
    private var lastSampleAt: Long? = null
    private var shown = false

    fun reset() { since = null; lastSampleAt = null; shown = false }
    // Navigation hides the card, but must not offer it again in the same loss episode.
    fun suspend() { since = null; lastSampleAt = null }

    fun update(now: Long, sampleAt: Long?, missing: Boolean, eligible: Boolean): Boolean {
        if (sampleAt == null || now - sampleAt !in 0..250) { suspend(); return false }
        if (!missing) { reset(); return false }
        if (!eligible) { suspend(); return false }
        val previous = lastSampleAt
        if (previous != null && (sampleAt < previous || sampleAt - previous > 250)) suspend()
        if (lastSampleAt == sampleAt) return false
        lastSampleAt = sampleAt
        if (since == null) since = now
        if (!shown && now - since!! >= delayMs) { shown = true; return true }
        return false
    }
}
