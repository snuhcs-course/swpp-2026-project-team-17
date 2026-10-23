package dev.gazedot

/** Accessed on the main thread. Camera worker posts immutable results here. */
object TrackerState {
    var running = false
    var model: GazePredictor? = null
    var modelLabel = "기본 홍채 포인터 · 보정 없이 시작"
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
    var configKey = ""
    var cancelActions: (() -> Unit)? = null
    var cancelPendingClick: (() -> Unit)? = null
    var features: EyeFeatures? = null
    var sample: GazeSample? = null
    var gaze: ScreenGaze? = null
        private set
    private var nextCalibration = 0L
    private var calibration: Long? = null

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
        cancelCalibration()
        collecting = false
        clearCoordinates()
    }

    /** Keep the selected model and all saved values until an explicit session succeeds. */
    fun beginCalibration(): Long {
        cancelCalibration()
        val session = ++nextCalibration
        calibration = session
        collecting = true
        cancelActions?.invoke()
        clearCoordinates()
        return session
    }

    fun finishCalibration(session: Long, result: GazePredictor?, save: () -> Unit): Boolean {
        if (calibration != session) return false
        try {
            if (result == null) return false
            save()
            model = result
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
