package dev.gazedot

import kotlin.math.*

data class EyeFeatures(val x: Double, val y: Double, val faceX: Double, val faceY: Double, val scale: Double)
data class Anchor(val eye: EyeFeatures, val x: Double, val y: Double)

/** Read-only copy of a fitted [GazeModel], recorded by evaluation sessions for replay checks. */
data class GazeModelParameters(
    val meanX: Double, val meanY: Double, val stdX: Double, val stdY: Double,
    val wx: List<Double>, val wy: List<Double>,
    val referenceX: Double, val referenceY: Double,
    val referenceFaceX: Double, val referenceFaceY: Double, val referenceScale: Double,
    val fitError: Double,
)

/** Calibration maps iris displacement inside the eye to display coordinates.
 * This is a calibrated approximation, not a pretrained screen-gaze model. */
class GazeModel private constructor(
    private val meanX: Double, private val meanY: Double,
    private val stdX: Double, private val stdY: Double,
    private val wx: DoubleArray, private val wy: DoubleArray,
    private val reference: EyeFeatures,
    val fitError: Double,
) {
    fun predict(f: EyeFeatures): Pair<Double, Double>? =
        linearOutput(f)?.let { it.first.coerceIn(0.0, 1.0) to it.second.coerceIn(0.0, 1.0) }

    /** Why [predict] returns null for [f]; null when it predicts. */
    fun rejection(f: EyeFeatures): String? = when {
        !listOf(f.x, f.y, f.faceX, f.faceY, f.scale).all { it.isFinite() } -> "non_finite_features"
        // A changed pose/distance invalidates this simple calibration. Do not invent a point.
        f.scale !in reference.scale * 0.72..reference.scale * 1.28 -> "scale_outside_0.72_1.28"
        abs(f.faceX - reference.faceX) > 0.12 -> "face_x_shift_gt_0.12"
        abs(f.faceY - reference.faceY) > 0.12 -> "face_y_shift_gt_0.12"
        else -> null
    }

    /** The linear map before [predict]'s [0, 1] clamp. Evaluation diagnostics only; the app never uses it. */
    fun linearOutput(f: EyeFeatures): Pair<Double, Double>? {
        if (rejection(f) != null) return null
        val v = doubleArrayOf(1.0, (f.x - meanX) / stdX, (f.y - meanY) / stdY)
        return dot(wx, v) to dot(wy, v)
    }

    fun parameters() = GazeModelParameters(meanX, meanY, stdX, stdY, wx.toList(), wy.toList(),
        reference.x, reference.y, reference.faceX, reference.faceY, reference.scale, fitError)

    companion object {
        fun fit(anchors: List<Anchor>): GazeModel? {
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
            return GazeModel(mx, my, sx, sy, wx, wy, f, error)
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

/** Exponential smoothing of the model output for the overlay (factor 0.28). A null prediction clears it. */
class GazeSmoother {
    var value: Pair<Double, Double>? = null
        private set

    fun update(position: Pair<Double, Double>?): Pair<Double, Double>? {
        value = if (position == null) null else value?.let {
            it.first + 0.28 * (position.first - it.first) to it.second + 0.28 * (position.second - it.second)
        } ?: position
        return value
    }

    fun reset() { value = null }
}

fun medianFeatures(samples: List<EyeFeatures>): EyeFeatures {
    fun median(selector: (EyeFeatures) -> Double): Double = samples.map(selector).sorted()[samples.size / 2]
    return EyeFeatures(median { it.x }, median { it.y }, median { it.faceX }, median { it.faceY }, median { it.scale })
}
