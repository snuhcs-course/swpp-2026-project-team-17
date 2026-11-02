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
    fun calibrationFeatures(nowMs: Long, after: Long): EyeFeatures? = features?.takeIf {
        timestampMs > after && nowMs - timestampMs in 0..250 &&
            quality in listOf(TrackingQuality.TRACKING, TrackingQuality.OUT_OF_RANGE)
    }
}

/** The original calibrated predictor and 0.28 smoothing, followed by interaction only. */
class GazePipeline {
    private val interaction = InteractionEngine()
    private var smoothed: Pair<Double, Double>? = null
    private var lastTimestamp: Long? = null
    private var model: GazeModel? = null
    val pending get() = interaction.pending

    fun cancelInteraction() = interaction.reset(preserveSelection = true)
    fun cancelPendingClick() = interaction.cancelClick()
    fun reset() {
        interaction.reset()
        smoothed = null
        lastTimestamp = null
        model = null
    }

    fun process(timestampMs: Long, nowMs: Long, observation: EyeObservation,
        predictor: GazeModel?, actionsEnabled: Boolean,
        targetKey: ((Pair<Double, Double>) -> String?)? = null): GazeSample {
        if (timestampMs > nowMs || nowMs - timestampMs > 250 ||
            lastTimestamp?.let { timestampMs <= it } == true) {
            val previous = lastTimestamp
            val hadDwell = pending
            reset()
            lastTimestamp = previous
            return GazeSample(timestampMs, TrackingQuality.STALE, null, null, null, 0f,
                if (hadDwell) listOf(EyeAction.Cancel) else emptyList())
        }
        if (model !== predictor || lastTimestamp?.let { timestampMs - it > 250 } == true) reset()
        model = predictor
        lastTimestamp = timestampMs
        val features = observation.features
        val position = features?.let { predictor?.predict(it) }
        // Same interpolation and null reset as feature/home-screen TrackingService.
        smoothed = if (position == null) null else smoothed?.let {
            it.first + 0.28 * (position.first - it.first) to it.second + 0.28 * (position.second - it.second)
        } ?: position
        val quality = when {
            features != null -> if (position == null) TrackingQuality.OUT_OF_RANGE else TrackingQuality.TRACKING
            observation.state == EyeState.LOST -> TrackingQuality.LOST
            observation.state == EyeState.CLOSED -> TrackingQuality.CLOSED
            else -> TrackingQuality.UNCERTAIN
        }
        val actions = if (actionsEnabled && predictor != null) {
            val eye = if (quality == TrackingQuality.OUT_OF_RANGE) EyeState.LOST else observation.state
            interaction.update(timestampMs, eye, smoothed, targetKey = smoothed?.let { targetKey?.invoke(it) }, receivedAt = nowMs)
        } else {
            val hadDwell = pending
            cancelInteraction()
            if (hadDwell) listOf(EyeAction.Cancel) else emptyList()
        }
        return GazeSample(timestampMs, quality, features, position, smoothed, interaction.progress, actions, interaction.scrollMode)
    }
}
