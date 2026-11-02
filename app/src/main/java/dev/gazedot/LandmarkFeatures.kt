package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

/** Features of one face, or the check in [LandmarkFeatures.extract] that rejected it. */
data class FeatureExtraction(val features: EyeFeatures?, val rejection: String?)

object LandmarkFeatures {
    fun extract(p: List<NormalizedLandmark>, width: Int, height: Int): EyeFeatures? = extractDetailed(p, width, height).features

    /** [extract] plus the name of the failed check. Evaluation logs the name; the checks themselves are unchanged. */
    fun extractDetailed(p: List<NormalizedLandmark>, width: Int, height: Int): FeatureExtraction {
        if (p.size < 478) return FeatureExtraction(null, "fewer_than_478_landmarks")
        var rejection: String? = null
        fun point(i: Int) = p[i].x().toDouble() * width to p[i].y().toDouble() * height
        fun eye(a: Int, b: Int, iris: Int, upper: Int, lower: Int): Pair<Double, Double>? {
            val c1 = point(a); val c2 = point(b); val center = point(iris)
            val dx = c2.first - c1.first; val dy = c2.second - c1.second
            val len = hypot(dx, dy)
            if (len < 12) { rejection = "eye_${a}_${b}_corner_span_lt_12px"; return null }
            val top = point(upper); val bottom = point(lower)
            if (hypot(top.first - bottom.first, top.second - bottom.second) / len < 0.10) {
                rejection = "eye_${a}_${b}_aperture_ratio_lt_0.10"; return null
            }
            val ix = center.first - (c1.first + c2.first) / 2
            val iy = center.second - (c1.second + c2.second) / 2
            return (ix * dx + iy * dy) / (len * len) to (-ix * dy + iy * dx) / (len * len)
        }
        // Both corner pairs have the same image-space direction. Images are NOT mirrored.
        val r = eye(33, 133, 468, 159, 145) ?: return FeatureExtraction(null, rejection)
        val l = eye(362, 263, 473, 386, 374) ?: return FeatureExtraction(null, rejection)
        val scale = hypot((p[33].x() - p[263].x()).toDouble(),
            (p[33].y() - p[263].y()).toDouble() * height / width)
        return FeatureExtraction(EyeFeatures((r.first + l.first) / 2, (r.second + l.second) / 2,
            p[1].x().toDouble(), p[1].y().toDouble(), scale), null)
    }
}
