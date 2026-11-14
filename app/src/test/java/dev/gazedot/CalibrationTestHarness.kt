package dev.gazedot

/** Synthetic eye features that move with the target, so the unchanged GazeModel.fit accepts them. */
internal fun eyeFor(target: Pair<Double, Double>) =
    EyeFeatures((target.first - .5) * .3, (target.second - .5) * .2, .5, .5, .25)

internal fun gazeSample(timestampMs: Long, f: EyeFeatures?, quality: TrackingQuality = TrackingQuality.TRACKING) =
    GazeSample(timestampMs, quality, f, null, null, 0f, emptyList())

/**
 * Drives a [CalibrationController] with a fake clock. Target fractions are used directly as display
 * coordinates. Every fit call and every outcome is recorded.
 */
internal class CalibrationHarness(
    private val features: (pass: Int, target: Pair<Double, Double>) -> EyeFeatures = { _, target -> eyeFor(target) },
    fitter: (List<Anchor>) -> GazeModel? = { GazeModel.fit(it) },
    onOutcome: (CalibrationOutcome) -> Unit = {},
) {
    var now = 10_000L
    val fits = mutableListOf<List<Anchor>>()
    val outcomes = mutableListOf<CalibrationOutcome>()
    val controller = CalibrationController(
        clock = { now },
        toDisplay = { x, y -> x to y },
        fit = { anchors -> fits += anchors.toList(); fitter(anchors) },
        onFinished = { outcomes += it; onOutcome(it) },
    )

    /** One camera result for the current target (or [f]) captured at [timestamp]. */
    fun frame(
        f: EyeFeatures? = if (controller.closed) null else features(controller.pass, controller.target),
        quality: TrackingQuality = TrackingQuality.TRACKING,
        timestamp: Long = now,
    ) = controller.onFrame(gazeSample(timestamp, f, quality))

    /** Advance [ms], tick, then deliver one fresh frame, as the camera (~15 fps) and the 100 ms tick do. */
    fun step(ms: Long = FRAME_MS) {
        now += ms
        controller.onTick()
        frame()
    }

    fun runUntil(maxMs: Long = 300_000, observe: () -> Unit = {}, done: () -> Boolean) {
        val end = now + maxMs
        while (!done()) {
            check(now < end) { "not reached within $maxMs ms" }
            step()
            observe()
        }
    }

    fun completeAnchors(n: Int) = runUntil { controller.anchors.size >= n || controller.closed }
    fun runToEnd(observe: () -> Unit = {}) = runUntil(observe = observe) { controller.closed }

    companion object { const val FRAME_MS = 66L }
}
