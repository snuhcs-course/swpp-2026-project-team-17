package dev.gazedot

// FROZEN BASELINE, test sources only. Generated mechanically from the unchanged home-screen candidate
// (commit 30be1cf726a47ab5751bd8e8bb4f537d0c82c810) with `git show`, renaming only the declarations
// (GazeModel -> BaselineGazeModel, LandmarkFeatures -> BaselineLandmarkFeatures,
// medianFeatures -> baselineMedianFeatures) and dropping the shared EyeFeatures/Anchor declarations.
// CandidateBehaviorTest requires the instrumented app code to reproduce these outputs bit for bit.
// Never edit by hand.

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

/** Calibration maps iris displacement inside the eye to display coordinates.
 * This is a calibrated approximation, not a pretrained screen-gaze model. */
class BaselineGazeModel private constructor(
    private val meanX: Double, private val meanY: Double,
    private val stdX: Double, private val stdY: Double,
    private val wx: DoubleArray, private val wy: DoubleArray,
    private val reference: EyeFeatures,
    val fitError: Double,
) {
    fun predict(f: EyeFeatures): Pair<Double, Double>? {
        if (!listOf(f.x, f.y, f.faceX, f.faceY, f.scale).all { it.isFinite() }) return null
        // A changed pose/distance invalidates this simple calibration. Do not invent a point.
        if (f.scale !in reference.scale * 0.72..reference.scale * 1.28 ||
            abs(f.faceX - reference.faceX) > 0.12 || abs(f.faceY - reference.faceY) > 0.12) return null
        val v = doubleArrayOf(1.0, (f.x - meanX) / stdX, (f.y - meanY) / stdY)
        return dot(wx, v).coerceIn(0.0, 1.0) to dot(wy, v).coerceIn(0.0, 1.0)
    }

    companion object {
        fun fit(anchors: List<Anchor>): BaselineGazeModel? {
            if (anchors.size < 5 || anchors.any { a ->
                !listOf(a.eye.x, a.eye.y, a.eye.faceX, a.eye.faceY, a.eye.scale, a.x, a.y).all { it.isFinite() }
            }) return null
            val mx = anchors.map { it.eye.x }.average()
            val my = anchors.map { it.eye.y }.average()
            val sx = sqrt(anchors.map { (it.eye.x - mx).pow(2) }.average())
            val sy = sqrt(anchors.map { (it.eye.y - my).pow(2) }.average())
            if (sx < 0.008 || sy < 0.006) return null
            val rows = anchors.map { doubleArrayOf(1.0, (it.eye.x - mx) / sx, (it.eye.y - my) / sy) }
            val wx = solve(rows, anchors.map { it.x }) ?: return null
            val wy = solve(rows, anchors.map { it.y }) ?: return null
            val error = sqrt(rows.indices.map { i ->
                (dot(wx, rows[i]) - anchors[i].x).pow(2) + (dot(wy, rows[i]) - anchors[i].y).pow(2)
            }.average())
            if (!error.isFinite() || error > 0.18) return null
            val f = EyeFeatures(mx, my, anchors.map { it.eye.faceX }.average(),
                anchors.map { it.eye.faceY }.average(), anchors.map { it.eye.scale }.average())
            return BaselineGazeModel(mx, my, sx, sy, wx, wy, f, error)
        }
        private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        private fun solve(rows: List<DoubleArray>, targets: List<Double>): DoubleArray? {
            val a = Array(3) { i -> DoubleArray(4) { j ->
                if (j == 3) rows.indices.sumOf { rows[it][i] * targets[it] }
                else rows.sumOf { it[i] * it[j] } + if (i == j && i != 0) 0.03 else 0.0
            } }
            for (col in 0..2) {
                val pivot = (col..2).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < 1e-9) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                val divisor = a[col][col]
                for (j in col..3) a[col][j] /= divisor
                for (i in 0..2) if (i != col) {
                    val factor = a[i][col]
                    for (j in col..3) a[i][j] -= factor * a[col][j]
                }
            }
            return DoubleArray(3) { a[it][3] }
        }
    }
}

fun baselineMedianFeatures(samples: List<EyeFeatures>): EyeFeatures {
    fun median(selector: (EyeFeatures) -> Double): Double = samples.map(selector).sorted()[samples.size / 2]
    return EyeFeatures(median { it.x }, median { it.y }, median { it.faceX }, median { it.faceY }, median { it.scale })
}

object BaselineLandmarkFeatures {
    fun extract(p: List<NormalizedLandmark>, width: Int, height: Int): EyeFeatures? {
        if (p.size < 478) return null
        fun point(i: Int) = p[i].x().toDouble() * width to p[i].y().toDouble() * height
        fun eye(a: Int, b: Int, iris: Int, upper: Int, lower: Int): Pair<Double, Double>? {
            val c1 = point(a); val c2 = point(b); val center = point(iris)
            val dx = c2.first - c1.first; val dy = c2.second - c1.second
            val len = hypot(dx, dy)
            if (len < 12) return null
            val top = point(upper); val bottom = point(lower)
            if (hypot(top.first - bottom.first, top.second - bottom.second) / len < 0.10) return null
            val ix = center.first - (c1.first + c2.first) / 2
            val iy = center.second - (c1.second + c2.second) / 2
            return (ix * dx + iy * dy) / (len * len) to (-ix * dy + iy * dx) / (len * len)
        }
        // Both corner pairs have the same image-space direction. Images are NOT mirrored.
        val r = eye(33, 133, 468, 159, 145) ?: return null
        val l = eye(362, 263, 473, 386, 374) ?: return null
        val scale = hypot((p[33].x() - p[263].x()).toDouble(),
            (p[33].y() - p[263].y()).toDouble() * height / width)
        return EyeFeatures((r.first + l.first) / 2, (r.second + l.second) / 2,
            p[1].x().toDouble(), p[1].y().toDouble(), scale)
    }
}

/** TrackingService.process smoothing statement at 30be1cf, verbatim apart from the function wrapper. */
fun baselineSmooth(smoothed: Pair<Double, Double>?, position: Pair<Double, Double>?): Pair<Double, Double>? =
    if (position == null) null else smoothed?.let {
            it.first + 0.28 * (position.first - it.first) to it.second + 0.28 * (position.second - it.second)
        } ?: position
