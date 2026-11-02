package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot

data class EyeObservation(val state: EyeState, val features: EyeFeatures?, val leftOpen: Double, val rightOpen: Double)

/** Gesture classification is independent of the original coordinate extractor. */
object EyeObservations {
    fun observe(p: List<NormalizedLandmark>, width: Int, height: Int): EyeObservation {
        if (p.size < 478 || width <= 0 || height <= 0 ||
            p.take(478).any { !it.x().isFinite() || !it.y().isFinite() }) {
            return EyeObservation(EyeState.LOST, null, 0.0, 0.0)
        }
        fun openness(a: Int, b: Int, top: Int, bottom: Int): Double? {
            fun distance(i: Int, j: Int) = hypot((p[i].x() - p[j].x()).toDouble() * width,
                (p[i].y() - p[j].y()).toDouble() * height)
            val span = distance(a, b)
            return if (span < 12) null else distance(top, bottom) / span
        }
        val right = openness(33, 133, 159, 145)
        val left = openness(362, 263, 386, 374)
        if (right == null || left == null) return EyeObservation(EyeState.LOST, null, 0.0, 0.0)
        val state = eyeState(left, right)
        // Do not apply gesture thresholds to the legacy five-value gaze features.
        return EyeObservation(state, LandmarkFeatures.extract(p, width, height), left, right)
    }
    fun eyeState(leftOpen: Double, rightOpen: Double): EyeState = when {
        !leftOpen.isFinite() || !rightOpen.isFinite() -> EyeState.LOST
        leftOpen < .11 && rightOpen < .11 -> EyeState.CLOSED
        leftOpen < .11 && rightOpen > .15 -> EyeState.LEFT_WINK
        rightOpen < .11 && leftOpen > .15 -> EyeState.RIGHT_WINK
        leftOpen > .15 && rightOpen > .15 -> EyeState.OPEN
        else -> EyeState.UNCERTAIN
    }
}
