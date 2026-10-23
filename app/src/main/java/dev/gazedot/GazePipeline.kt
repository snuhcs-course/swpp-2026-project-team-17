package dev.gazedot

enum class TrackingQuality { TRACKING, CLOSED, UNCERTAIN, LOST, OUT_OF_RANGE, STALE }

/** Camera and UI-independent output. Points are normalized, deliberately NOT clamped:
 * coordinates outside the screen drive scrolling. Call only from the UI thread. */
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
    /** A previous model rejecting a new pose must not prevent explicit recalibration. */
    fun calibrationFeatures(nowMs: Long, after: Long): EyeFeatures? = features?.takeIf {
        timestampMs > after && nowMs - timestampMs in 0..250 &&
            quality in listOf(TrackingQuality.TRACKING, TrackingQuality.OUT_OF_RANGE)
    }
}

class GazePipeline {
    private val stabilizer = GazeStabilizer()
    private val interaction = InteractionEngine()
    private var model: GazePredictor? = null
    private var lastTimestamp: Long? = null
    val pending get() = interaction.pending

    fun cancelInteraction(preserveScrollMode: Boolean = false) =
        interaction.reset(preserveSelection = true, preserveScrollMode = preserveScrollMode)

    fun cancelPendingClick() = interaction.cancelClick()

    fun reset(preserveSelection: Boolean = false) {
        interaction.reset(preserveSelection)
        stabilizer.reset()
        lastTimestamp = null
    }

    fun process(
        timestampMs: Long,
        nowMs: Long,
        observation: EyeObservation,
        predictor: GazePredictor,
        actionsEnabled: Boolean,
        clickCandidate: Pair<Double, Double>? = null,
        targetKey: ((Pair<Double, Double>) -> String?)? = null,
    ): GazeSample {
        val hadDwell = pending
        // Reject duplicates, delayed frames, and timestamps from the wrong clock.
        if (timestampMs > nowMs || nowMs - timestampMs > 250 ||
            lastTimestamp?.let { timestampMs <= it } == true) {
            val previous = lastTimestamp
            reset(preserveSelection = true)
            lastTimestamp = previous
            return GazeSample(timestampMs, TrackingQuality.STALE, null, null, null, 0f,
                if (hadDwell) listOf(EyeAction.Cancel) else emptyList())
        }
        var cancelled = false
        if (predictor !== model || lastTimestamp?.let { timestampMs - it > 250 } == true) {
            cancelled = hadDwell
            reset(preserveSelection = true)
            model = predictor
        }
        lastTimestamp = timestampMs
        val features = observation.features?.takeIf { observation.state == EyeState.OPEN && it.vector().all(Double::isFinite) }
        val raw = features?.let(predictor::predict)?.takeIf { it.first.isFinite() && it.second.isFinite() }
        val quality = when (observation.state) {
            EyeState.LOST -> TrackingQuality.LOST
            EyeState.CLOSED -> TrackingQuality.CLOSED
            EyeState.UNCERTAIN -> TrackingQuality.UNCERTAIN
            EyeState.LEFT_WINK, EyeState.RIGHT_WINK -> TrackingQuality.UNCERTAIN
            EyeState.OPEN -> if (raw == null) TrackingQuality.OUT_OF_RANGE else TrackingQuality.TRACKING
        }
        val point = raw?.let { stabilizer.update(it, timestampMs) }
        if (quality == TrackingQuality.LOST || quality == TrackingQuality.OUT_OF_RANGE) stabilizer.reset()
        val actions = mutableListOf<EyeAction>()
        if (cancelled) actions += EyeAction.Cancel
        if (!actionsEnabled) {
            if (pending) actions += EyeAction.Cancel
            cancelInteraction()
        } else {
            val eye = if (quality == TrackingQuality.OUT_OF_RANGE) EyeState.LOST else observation.state
            val candidate = clickCandidate?.takeIf { it.first in 0.0..1.0 && it.second in 0.0..1.0 }
            val clickPoint = candidate ?: point
            actions += interaction.update(timestampMs, eye, point, clickPoint,
                clickPoint?.let { targetKey?.invoke(it) }, nowMs)
        }
        return GazeSample(timestampMs, quality, features, raw, point, interaction.progress, actions, interaction.scrollMode)
    }
}
