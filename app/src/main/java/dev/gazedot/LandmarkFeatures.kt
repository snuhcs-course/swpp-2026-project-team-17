package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

object LandmarkFeatures {
    fun extract(p: List<NormalizedLandmark>, width: Int, height: Int): EyeFeatures? {
        if (p.size < 478) return null
        fun point(i: Int) = p[i].x().toDouble() * width to p[i].y().toDouble() * height
        fun eye(a: Int, b: Int, iris: Int, upper: Int, lower: Int): Pair<Double, Double>? {
            val c1 = point(a); val c2 = point(b); val center = point(iris)
            val dx = c2.first - c1.first; val dy = c2.second - c1.second
            val len = hypot(dx, dy)
            if (len < 12) return null
            val top = point(upper); val bottom = point(lower)
            // Closure classification is handled by EyeObservations using personal open-eye baselines.
            // Keep only a minimal geometric visibility floor here, not a second population threshold.
            if (hypot(top.first - bottom.first, top.second - bottom.second) / len < 0.04) return null
            val ix = center.first - (c1.first + c2.first) / 2
            val iy = center.second - (c1.second + c2.second) / 2
            return (ix * dx + iy * dy) / (len * len) to (-ix * dy + iy * dx) / (len * len)
        }
        // Both corner pairs have the same image-space direction. Images are NOT mirrored.
        val r = eye(33, 133, 468, 159, 145) ?: return null
        val l = eye(362, 263, 473, 386, 374) ?: return null
        val scale = hypot((p[33].x() - p[263].x()).toDouble(),
            (p[33].y() - p[263].y()).toDouble() * height / width)
        fun vertical(iris:Int,upper:Int,lower:Int): Double? {
            val center=point(iris);val top=point(upper);val bottom=point(lower)
            val dx=bottom.first-top.first;val dy=bottom.second-top.second
            val squared=dx*dx+dy*dy
            if(squared<1) return null
            return ((center.first-(top.first+bottom.first)/2)*dx+
                (center.second-(top.second+bottom.second)/2)*dy)/squared
        }
        val quickLeft=vertical(473,386,374)
        val quickRight=vertical(468,159,145)
        val quickY=if(quickLeft!=null && quickRight!=null) (quickLeft+quickRight)/2 else null
        return EyeFeatures((r.first + l.first) / 2, (r.second + l.second) / 2,
            p[1].x().toDouble(), p[1].y().toDouble(), scale, l.first, l.second, r.first, r.second, quickVertical=quickY)
    }
}
