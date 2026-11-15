package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot

/**
 * Evaluation diagnostics only: names the check that left a detected face without gaze features.
 * Repeats EyeObservations.observe's checks and then LandmarkFeatures.extract's, each with its own arithmetic,
 * without changing either. CandidateBehaviorTest requires the result to be null exactly when observe returns features.
 */
object EvaluationDiagnostics {
    fun featureRejection(p: List<NormalizedLandmark>, width: Int, height: Int): String? {
        // EyeObservations.observe: these make the observation LOST.
        if (p.size < 478) return "fewer_than_478_landmarks"
        if (width <= 0 || height <= 0) return "invalid_image_size"
        if (p.take(478).any { !it.x().isFinite() || !it.y().isFinite() }) return "non_finite_landmarks"
        fun observeSpan(a: Int, b: Int) = hypot((p[a].x() - p[b].x()).toDouble() * width, (p[a].y() - p[b].y()).toDouble() * height)
        if (observeSpan(33, 133) < 12) return "eye_33_133_span_lt_12px"
        if (observeSpan(362, 263) < 12) return "eye_362_263_span_lt_12px"
        // LandmarkFeatures.extract: these leave an observed face without features.
        fun point(i: Int) = p[i].x().toDouble() * width to p[i].y().toDouble() * height
        fun eye(a: Int, b: Int, upper: Int, lower: Int): String? {
            val c1 = point(a); val c2 = point(b)
            val len = hypot(c2.first - c1.first, c2.second - c1.second)
            if (len < 12) return "eye_${a}_${b}_corner_span_lt_12px"
            val top = point(upper); val bottom = point(lower)
            if (hypot(top.first - bottom.first, top.second - bottom.second) / len < 0.10) return "eye_${a}_${b}_aperture_ratio_lt_0.10"
            return null
        }
        return eye(33, 133, 159, 145) ?: eye(362, 263, 386, 374)
    }
}
