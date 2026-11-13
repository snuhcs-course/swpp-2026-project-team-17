package dev.gazedot

/** Accessed on the main thread. Camera worker posts immutable results here. */
object TrackerState {
    var running = false
    var model: GazeModel? = null
    var controls = false
        set(value) {
            if (field == value) return
            field = value
            cancelActions?.invoke()
            contextChanged?.invoke()
            notifyListeners()
        }
    var collecting = false
    var interactionAllowed = false
    var localDwellProgress = 0f
    var localScrollMode = false
    private var settingsScreens = 0
    var helpVisible = false
    var recoveryOpening = false
    var fault: TrackingFault? = null
    var signalPending = false
    var contextChanged: (() -> Unit)? = null
    private val observers = linkedSetOf<() -> Unit>()
    fun observe(observer: () -> Unit) { observers += observer }
    fun unobserve(observer: () -> Unit) { observers -= observer }
    fun notifyListeners() { listener?.invoke(); observers.toList().forEach { it() } }
    fun currentStatus(now: Long) = trackingStatus(running, controls, collecting, model != null,
        sample, eyeState, now, fault, signalPending)
    fun externalActionsAllowed() = running && controls && interactionAllowed && !collecting &&
        !helpVisible && !recoveryOpening && fault == null && !signalPending
    fun recognitionHelpEligible(accessibilityReady: Boolean) = running && controls && interactionAllowed &&
        !collecting && !speaking && !recoveryOpening && model != null && fault == null &&
        !signalPending && accessibilityReady
    fun settingsStarted() {
        settingsScreens++
        interactionAllowed = false
        cancelActions?.invoke()
        contextChanged?.invoke()
    }
    fun settingsStopped() {
        cancelActions?.invoke()
        settingsScreens=(settingsScreens-1).coerceAtLeast(0)
        interactionAllowed=settingsScreens==0
        contextChanged?.invoke()
    }
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
        helpVisible = false
        recoveryOpening = false
        signalPending = false
    }

    /** Keep the previous in-memory calibration until a new session succeeds. */
    fun beginCalibration(): Long {
        cancelCalibration()
        val session = ++nextCalibration
        calibration = session
        collecting = true
        cancelActions?.invoke()
        clearCoordinates()
        contextChanged?.invoke()
        return session
    }

    fun finishCalibration(session: Long, result: GazeModel?): Boolean {
        if (calibration != session) return false
        try {
            if (result == null) return false
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
        contextChanged?.invoke()
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
        notifyListeners()
    }

    fun publishSample(result: GazeSample, message: String) {
        sample = result
        frameAt = result.timestampMs
        features = result.features
        gaze = result.toScreenGaze()?.takeIf { eyeState == EyeState.OPEN }
        status = message
        notifyListeners()
    }
}
