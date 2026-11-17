package dev.gazedot

enum class TrackingQuality { TRACKING, CLOSED, UNCERTAIN, LOST, OUT_OF_RANGE, STALE }

data class GazeSample(
    val timestampMs: Long,
    val quality: TrackingQuality,
    val features: EyeFeatures?,
    val rawPoint: Pair<Double, Double>?,
    val point: Pair<Double, Double>?,
    val dwellProgress: Float,
    val actions: List<EyeAction>,
    val scrollMode: Boolean = false,
) {
    fun calibrationFeatures(nowMs: Long, after: Long, maxAgeMs: Long = 250): EyeFeatures? = features?.takeIf {
        timestampMs > after && nowMs - timestampMs in 0..maxAgeMs &&
            quality in listOf(TrackingQuality.TRACKING, TrackingQuality.OUT_OF_RANGE)
    }
}

/** The original calibrated predictor and 0.28 smoothing, followed by interaction only. */
class GazePipeline(private val trackingMaxAgeMs: Long = 500, private val actionMaxAgeMs: Long = 500) {
    private val interaction = InteractionEngine()
    private var smoothed: Pair<Double, Double>? = null
    private var lastTimestamp: Long? = null
    private var model: GazeModel? = null
    private var closureAt: Long? = null
    private var reopenedAt: Long? = null
    val pending get() = interaction.pending

    fun cancelInteraction() = interaction.reset(preserveSelection = true)
    fun cancelPendingClick() = interaction.cancelClick()
    fun reset() {
        interaction.reset()
        smoothed = null
        lastTimestamp = null
        model = null
        closureAt = null; reopenedAt = null
    }

    fun process(timestampMs: Long, nowMs: Long, observation: EyeObservation,
        predictor: GazeModel?, actionsEnabled: Boolean,
        targetKey: ((Pair<Double, Double>) -> String?)? = null): GazeSample {
        if (timestampMs > nowMs || nowMs - timestampMs > trackingMaxAgeMs ||
            lastTimestamp?.let { timestampMs <= it } == true) {
            val previous = lastTimestamp
            val hadDwell = pending
            reset()
            lastTimestamp = previous
            return GazeSample(timestampMs, TrackingQuality.STALE, null, null, null, 0f,
                if (hadDwell) listOf(EyeAction.Cancel) else emptyList())
        }
        if (model !== predictor || lastTimestamp?.let { timestampMs - it > trackingMaxAgeMs } == true) reset()
        model = predictor
        lastTimestamp = timestampMs
        if (observation.state == EyeState.LOST) {
            closureAt = null; reopenedAt = null; smoothed = null
        } else if (observation.state != EyeState.OPEN) {
            if (closureAt == null) closureAt = timestampMs
            reopenedAt = null
        } else if (closureAt != null && reopenedAt == null) reopenedAt = timestampMs
        val settling = observation.state == EyeState.OPEN && reopenedAt?.let { timestampMs-it < 150 } == true
        val held = closureAt != null && timestampMs-closureAt!! <= 1200
        if (observation.state == EyeState.OPEN && !settling) { closureAt=null; reopenedAt=null }
        val features = observation.features.takeIf { observation.state == EyeState.OPEN && !settling }
        val position = features?.let { predictor?.predict(it) }
        // Same interpolation and null reset as feature/home-screen TrackingService.
        smoothed = if (position == null) { if (held) smoothed else null } else smoothed?.let {
            it.first + 0.28 * (position.first - it.first) to it.second + 0.28 * (position.second - it.second)
        } ?: position
        val quality = when {
            settling -> TrackingQuality.UNCERTAIN
            features != null -> if (position == null) TrackingQuality.OUT_OF_RANGE else TrackingQuality.TRACKING
            observation.state == EyeState.LOST -> TrackingQuality.LOST
            observation.state == EyeState.CLOSED -> TrackingQuality.CLOSED
            else -> TrackingQuality.UNCERTAIN
        }
        val actions = if (actionsEnabled && predictor != null && nowMs-timestampMs in 0..actionMaxAgeMs) {
            val eye = if (quality == TrackingQuality.OUT_OF_RANGE) EyeState.LOST else observation.state
            interaction.update(timestampMs, eye, if (quality == TrackingQuality.TRACKING) smoothed else null, targetKey = if (quality == TrackingQuality.TRACKING) smoothed?.let { targetKey?.invoke(it) } else null, receivedAt = nowMs)
        } else {
            val hadDwell = pending
            cancelInteraction()
            if (hadDwell) listOf(EyeAction.Cancel) else emptyList()
        }
        return GazeSample(timestampMs, quality, features, position, smoothed, interaction.progress, actions, interaction.scrollMode)
    }
}
