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
    /** Bias calibration must not learn display smoothing or pre-settle queued frames. */
    fun calibrationCheckPoint(nowMs: Long, targetAtMs: Long, settleMs: Long = 900): Pair<Double, Double>? =
        rawPoint?.takeIf {
            quality == TrackingQuality.TRACKING && nowMs-timestampMs in 0..250 &&
                timestampMs-targetAtMs >= settleMs && it.first.isFinite() && it.second.isFinite()
        }

    fun calibrationFeatures(nowMs: Long, after: Long, maxAgeMs: Long = 250): EyeFeatures? = features?.takeIf {
        timestampMs > after && nowMs - timestampMs in 0..maxAgeMs &&
            quality in listOf(TrackingQuality.TRACKING, TrackingQuality.OUT_OF_RANGE)
    }
}

/** Calibrated prediction, robust fixation filtering and freshness-gated interaction. */
class GazePipeline(private val trackingMaxAgeMs: Long = 500, private val actionMaxAgeMs: Long = 500, useBars:Boolean=false) {
    private val interaction = InteractionEngine(useBars)
    val bars get()=interaction.bars
    private val stabilizer = GazeStabilizer()
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
        stabilizer.reset()
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
            closureAt = null; reopenedAt = null; smoothed = null; stabilizer.reset()
        } else if (observation.state != EyeState.OPEN) {
            if (closureAt == null) closureAt = timestampMs
            reopenedAt = null
        } else if (closureAt != null && reopenedAt == null) reopenedAt = timestampMs
        val settling = observation.state == EyeState.OPEN && reopenedAt?.let { timestampMs-it < 150 } == true
        val held = closureAt != null && timestampMs-closureAt!! <= 1200
        if (observation.state == EyeState.OPEN && !settling) { closureAt=null; reopenedAt=null }
        val features = observation.features.takeIf { observation.state == EyeState.OPEN && !settling }
        val position = features?.let { predictor?.predictForFiltering(it) }
        // Modest offscreen overscan avoids inward clipping bias. Bound extreme
        // extrapolations so one bad frame cannot leave a long filter tail.
        val filtered = position?.let { stabilizer.update(
            it.first.coerceIn(-.25,1.25) to it.second.coerceIn(-.25,1.25), timestampMs) }
        smoothed = if (filtered != null) filtered.point else if (held) smoothed else {
            stabilizer.reset(); null
        }
        // Controlled range experiment: preserve the center and horizontal coordinate.
        // Expand only the final vertical output; do not change calibration or filter history.
        // The same screenPoint is used for the pointer, target lookup and all interactions.
        val screenPoint=smoothed?.let {
            it.first.coerceIn(0.0,1.0) to (.5+(it.second-.5)*1.10).coerceIn(0.0,1.0)
        }
        val quality = when {
            settling || filtered?.confirmed == false -> TrackingQuality.UNCERTAIN
            features != null -> if (position == null) TrackingQuality.OUT_OF_RANGE else TrackingQuality.TRACKING
            observation.state == EyeState.LOST -> TrackingQuality.LOST
            observation.state == EyeState.CLOSED -> TrackingQuality.CLOSED
            else -> TrackingQuality.UNCERTAIN
        }
        val actions = if (actionsEnabled && predictor != null && nowMs-timestampMs in 0..actionMaxAgeMs) {
            // Coordinate conversion failure is not face loss: pause movement without clearing the mode.
            val eye = observation.state
            interaction.update(timestampMs, eye, if (quality == TrackingQuality.TRACKING) screenPoint else null, targetKey = if (quality == TrackingQuality.TRACKING) screenPoint?.let { targetKey?.invoke(it) } else null, receivedAt = nowMs)
        } else {
            val hadDwell = pending
            cancelInteraction()
            if (hadDwell) listOf(EyeAction.Cancel) else emptyList()
        }
        return GazeSample(timestampMs, quality, features, position, screenPoint, interaction.progress, actions, interaction.scrollMode)
    }
}
