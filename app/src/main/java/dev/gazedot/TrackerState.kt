package dev.gazedot

/** Accessed on the main thread. Camera worker posts immutable results here. */
object TrackerState {
    var running = false
    var model: GazeModel? = null
    var controls = false
    var collecting = false
    var interactionAllowed = false
    var localDwellProgress = 0f
    var localScrollMode = false
    private var settingsScreens = 0
    fun settingsStarted() {
        settingsScreens++
        interactionAllowed = false
        cancelActions?.invoke()
    }
    fun settingsStopped() { settingsScreens=(settingsScreens-1).coerceAtLeast(0); interactionAllowed=settingsScreens==0 }
    var speaking = false
    var microphoneReady = false
    var actionStatus = "조작 꺼짐"
    var frameAt = 0L
    var eyeState = EyeState.LOST
    var cancelActions: (() -> Unit)? = null
    var cancelPendingClick: (() -> Unit)? = null
    var features: EyeFeatures? = null
    var sample: GazeSample? = null
    var gaze: ScreenGaze? = null
        private set
    private var nextCalibration = 0L
    private var calibration: Long? = null

    /**
     * Evaluation only: the last successful calibration of this tracking run, with its labelled fit inputs. Cleared when
     * any new calibration starts and when tracking stops, so a failed recalibration cannot pass off the model it keeps
     * as a fresh calibration. The model itself is kept or replaced exactly as before.
     */
    var calibrationRecord: CalibrationRecord? = null
        private set
    var calibrationSerial = 0
        private set
    /** Calibration serial already used by an evaluation session; a session needs a fresh calibration. */
    var evaluatedCalibrationSerial = 0
    /** Set by TrackingService: clears the gaze pipeline's smoothing state for an evaluation; the argument names the cause. */
    var resetSmoothing: ((String) -> Unit)? = null
    /** Set by TrackingService: on-screen origin and size of the pointer overlay view, or null. */
    var overlayBounds: (() -> IntArray?)? = null

    /**
     * Message of the last calibration attempt that ended without a new model. Shown by [frameStatus] while eyes are
     * detected, until the next calibration starts or tracking stops, so the per-frame status cannot hide the failure.
     */
    var calibrationFailure: String? = null
        private set
    fun calibrationFailed(message: String) { calibrationFailure = message }

    /** Status text for one processed frame (the per-frame messages of feature/home-screen, unchanged). */
    fun frameStatus(quality: TrackingQuality, hasModel: Boolean): String {
        val failure = calibrationFailure
        if (failure != null && quality in listOf(TrackingQuality.TRACKING, TrackingQuality.OUT_OF_RANGE)) return failure
        return when (quality) {
            TrackingQuality.LOST -> "얼굴 검출 대기"
            TrackingQuality.CLOSED -> "눈 감김 감지"
            TrackingQuality.UNCERTAIN -> "눈 상태 확인 중"
            TrackingQuality.STALE -> "영상 처리가 지연됩니다 · 조작 일시 대기"
            TrackingQuality.OUT_OF_RANGE -> if (!hasModel) "눈 검출됨 · 9점 보정을 시작해 주세요"
                else "얼굴 위치가 달라졌습니다 · 원래 위치로 돌아오거나 재보정하세요"
            TrackingQuality.TRACKING -> "추적 중 · 홈으로 나가도 빨간 점이 표시됩니다"
        }
    }

    fun calibrationCompleted(record: CalibrationRecord) {
        calibrationRecord = record
        calibrationSerial++
    }
    fun clearCalibrationRecord() { calibrationRecord = null }

    /** The service's condition for gaze clicks, scrolling and dictation, plus the explicit evaluation guard. */
    fun gazeActionsEnabled(accessibilityConnected: Boolean) = controls && interactionAllowed && !collecting &&
        !speaking && accessibilityConnected && !EvaluationState.suppressesActions
    /** The accessibility service's condition for taps, scrolls and text insertion, plus the explicit evaluation guard. */
    fun gazeControlAllowed() = running && controls && interactionAllowed && !collecting && !EvaluationState.suppressesActions

    fun hasFreshFeatures(now: Long) = features != null && now - frameAt in 0..250

    fun clearCoordinates() {
        localDwellProgress = 0f
        localScrollMode = false
        frameAt = 0L
        eyeState = EyeState.LOST
        features = null
        sample = null
        gaze = null
    }

    fun trackingStopped() {
        running = false
        controls = false
        calibrationRecord = null
        calibrationFailure = null
        cancelCalibration()
        collecting = false
        clearCoordinates()
    }

    /** Keep the previous in-memory calibration until a new session succeeds. */
    fun beginCalibration(): Long {
        cancelCalibration()
        calibrationRecord = null
        calibrationFailure = null
        val session = ++nextCalibration
        calibration = session
        collecting = true
        cancelActions?.invoke()
        clearCoordinates()
        return session
    }

    fun finishCalibration(session: Long, result: GazeModel?): Boolean {
        if (calibration != session) return false
        try {
            if (result == null) return false
            model = result
            calibrationFailure = null
            return true
        } finally {
            cancelCalibration()
        }
    }

    fun cancelCalibration() {
        if (calibration == null) return
        calibration = null
        collecting = false
        cancelActions?.invoke()
        clearCoordinates()
    }
    var status = "시작 전 · 기기를 거치대에 고정해 주세요"
    var listener: (() -> Unit)? = null
    fun publish(f: EyeFeatures?, message: String) {
        features = f
        if (f == null) {
            sample = null
            gaze = null
        }
        status = message
        listener?.invoke()
    }

    fun publishSample(result: GazeSample, message: String) {
        sample = result
        frameAt = result.timestampMs
        features = result.features
        gaze = result.toScreenGaze()
        status = message
        listener?.invoke()
    }
}
