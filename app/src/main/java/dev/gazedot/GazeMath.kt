package dev.gazedot

import kotlin.math.*

enum class GazeSource { LANDMARK, ITRACKER }

data class EyeFeatures(val x: Double, val y: Double, val faceX: Double, val faceY: Double, val scale: Double,
    val leftX: Double = x, val leftY: Double = y, val rightX: Double = x, val rightY: Double = y, val head: HeadPose? = null, val source: GazeSource = GazeSource.LANDMARK)
data class Anchor(val eye: EyeFeatures, val x: Double, val y: Double)

/** Screen mapping over either legacy iris features or frozen iTracker camera-centimetre outputs.
 * This small affine fit never trains or changes the pretrained CNN weights. */
class GazeModel private constructor(
    private val meanX: Double, private val meanY: Double,
    private val stdX: Double, private val stdY: Double,
    private val wx: DoubleArray, private val wy: DoubleArray,
    private val reference: EyeFeatures,
    val fitError: Double,
    val correction: ResidualCorrection? = null,
    private val leftModel: GazeModel? = null, private val rightModel: GazeModel? = null,
    val headCorrection: HeadCorrection? = null,
    private val screenCorrectionX: Double = 0.0,
    private val screenCorrectionY: Double = 0.0,
    private val leftWeight: Double = .5,
) {
    /** Largest singular value of the raw-centimetre to screen mapping (before clipping). */
    val mappingGain: Double get() {
        if (leftModel != null && rightModel != null) return maxOf(leftModel.mappingGain,rightModel.mappingGain)
        val a=wx[1]/stdX;val b=wx[2]/stdY;val c=wy[1]/stdX;val d=wy[2]/stdY
        val trace=a*a+b*b+c*c+d*d;val determinant=(a*d-b*c).pow(2)
        return sqrt((trace+sqrt(max(0.0,trace*trace-4*determinant)))/2)
    }
    fun predict(f: EyeFeatures): Pair<Double, Double>? = predictForFiltering(f)?.let {
        it.first.coerceIn(0.0,1.0) to it.second.coerceIn(0.0,1.0)
    }
    /** Keep edge noise symmetric until display smoothing has finished. */
    fun predictForFiltering(f: EyeFeatures): Pair<Double, Double>? {
        val p=predictUncorrected(f) ?: return null
        val corrected=correction?.apply(p.first,p.second) ?: p
        val result=corrected.first+screenCorrectionX to corrected.second+screenCorrectionY
        return result.takeIf { it.first.isFinite() && it.second.isFinite() }
    }
    fun withCorrection(value: ResidualCorrection?) = GazeModel(meanX,meanY,stdX,stdY,wx,wy,reference,fitError,value,leftModel,rightModel,headCorrection,screenCorrectionX,screenCorrectionY,leftWeight)
    fun withHeadCorrection(value: HeadCorrection?) = GazeModel(meanX,meanY,stdX,stdY,wx,wy,reference,fitError,null,leftModel,rightModel,value,screenCorrectionX,screenCorrectionY,leftWeight)
    fun withScreenCorrection(dx: Double, dy: Double): GazeModel? {
        if (!dx.isFinite() || !dy.isFinite()) return null
        return GazeModel(meanX,meanY,stdX,stdY,wx,wy,reference,fitError,correction,leftModel,rightModel,headCorrection,
            screenCorrectionX + dx,screenCorrectionY + dy,leftWeight)
    }
    internal fun withEyeWeight(weight: Double): GazeModel {
        require(weight in 0.0..1.0)
        return GazeModel(meanX,meanY,stdX,stdY,wx,wy,reference,fitError,correction,
            leftModel,rightModel,headCorrection,screenCorrectionX,screenCorrectionY,weight)
    }
    /** Fixed calibration intercept, applied to both eye mappings; never a runtime drift update. */
    internal fun withMappingOffset(dx: Double, dy: Double): GazeModel {
        require(dx.isFinite() && dy.isFinite())
        val shiftedX = wx.copyOf().also { it[0] += dx }
        val shiftedY = wy.copyOf().also { it[0] += dy }
        return GazeModel(meanX,meanY,stdX,stdY,shiftedX,shiftedY,reference,fitError,correction,
            leftModel?.withMappingOffset(dx,dy),rightModel?.withMappingOffset(dx,dy),
            headCorrection,screenCorrectionX,screenCorrectionY,leftWeight)
    }
    fun predictUncorrected(f: EyeFeatures): Pair<Double, Double>? {
        val raw=predictBase(f) ?: return null
        return if(headCorrection==null) raw else headCorrection.apply(f.head ?: return null,raw)
    }
    fun predictBase(f: EyeFeatures): Pair<Double, Double>? {
        if(f.source != reference.source)return null
        if (!listOf(f.x, f.y, f.faceX, f.faceY, f.scale).all { it.isFinite() }) return null
        // Iris coordinates are relative to eye corners, not the face's image location.
        // Translation/ordinary handheld distance changes must not discard valid eyes.
        // Detection handles occlusion; reject only degenerate geometry here.
        if (f.scale <= 0.0) return null
        if (leftModel != null && rightModel != null) {
            val left = leftModel.predictUncorrected(f.copy(x=f.leftX, y=f.leftY)) ?: return null
            val right = rightModel.predictUncorrected(f.copy(x=f.rightX, y=f.rightY)) ?: return null
            return left.first*leftWeight+right.first*(1-leftWeight) to left.second*leftWeight+right.second*(1-leftWeight)
        }
        val v = doubleArrayOf(1.0, (f.x - meanX) / stdX, (f.y - meanY) / stdY)
        return dot(wx, v) to dot(wy, v)
    }

    companion object {
        fun fit(anchors: List<Anchor>, ridge: Double = .006, axisAligned: Boolean = false): GazeModel? {
            if (anchors.isEmpty() || !ridge.isFinite() || ridge < 0) return null
            if(anchors.map{it.eye.source}.distinct().size != 1)return null
            // The pretrained CNN has already fused both eyes and face information.
            if(anchors.first().eye.source == GazeSource.ITRACKER)return fitSingle(anchors, ridge, axisAligned)
            // Fit each eye once, then combine SCREEN coordinates. An unused third fit
            // on averaged eye features can cancel valid signals and reject both eyes.
            val left = fitSingle(anchors.map { it.copy(eye=it.eye.copy(x=it.eye.leftX,y=it.eye.leftY)) }, ridge, axisAligned) ?: return null
            val right = fitSingle(anchors.map { it.copy(eye=it.eye.copy(x=it.eye.rightX,y=it.eye.rightY)) }, ridge, axisAligned) ?: return null
            return GazeModel(left.meanX,left.meanY,left.stdX,left.stdY,left.wx,left.wy,left.reference,
                maxOf(left.fitError,right.fitError),leftModel=left,rightModel=right)
        }
        private fun fitSingle(anchors: List<Anchor>, ridge: Double, axisAligned: Boolean): GazeModel? {
            if (anchors.size < 5 || anchors.any { a ->
                !listOf(a.eye.x, a.eye.y, a.eye.faceX, a.eye.faceY, a.eye.scale, a.x, a.y).all { it.isFinite() }
            }) return null
            val mx = anchors.map { it.eye.x }.average()
            val my = anchors.map { it.eye.y }.average()
            val sx = sqrt(anchors.map { (it.eye.x - mx).pow(2) }.average())
            val sy = sqrt(anchors.map { (it.eye.y - my).pow(2) }.average())
            val cnn = anchors.first().eye.source == GazeSource.ITRACKER
            if (sx < (if(cnn) .15 else .008) || sy < (if(cnn) .15 else .006)) return null
            val rows = anchors.map { doubleArrayOf(1.0, (it.eye.x - mx) / sx, (it.eye.y - my) / sy) }
            // Axis-aligned candidates avoid amplifying vertical noise into horizontal motion.
            val wx = if (axisAligned) doubleArrayOf(anchors.map { it.x }.average(),
                rows.indices.sumOf { rows[it][1] * anchors[it].x } /
                    (rows.sumOf { it[1]*it[1] } + ridge*rows.size), 0.0)
                else solve(rows, anchors.map { it.x }, ridge) ?: return null
            val wy = if (axisAligned) doubleArrayOf(anchors.map { it.y }.average(), 0.0,
                rows.indices.sumOf { rows[it][2] * anchors[it].y } /
                    (rows.sumOf { it[2]*it[2] } + ridge*rows.size))
                else solve(rows, anchors.map { it.y }, ridge) ?: return null
            val error = sqrt(rows.indices.map { i ->
                (dot(wx, rows[i]) - anchors[i].x).pow(2) + (dot(wy, rows[i]) - anchors[i].y).pow(2)
            }.average())
            if (!error.isFinite() || error > 0.18) return null
            val f = EyeFeatures(mx, my, anchors.map { it.eye.faceX }.average(),
                anchors.map { it.eye.faceY }.average(), anchors.map { it.eye.scale }.average(),source=anchors.first().eye.source)
            return GazeModel(mx, my, sx, sy, wx, wy, f, error)
        }
        private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        private fun solve(rows: List<DoubleArray>, targets: List<Double>, ridge: Double): DoubleArray? {
            val a = Array(3) { i -> DoubleArray(4) { j ->
                if (j == 3) rows.indices.sumOf { rows[it][i] * targets[it] }
                else rows.sumOf { it[i] * it[j] } + if (i == j && i != 0) ridge * rows.size else 0.0
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

fun medianFeatures(samples: List<EyeFeatures>): EyeFeatures {
    require(samples.isNotEmpty() && samples.map{it.source}.distinct().size==1)
    fun median(selector: (EyeFeatures) -> Double): Double = samples.map(selector).sorted()[samples.size / 2]
    return EyeFeatures(median { it.x }, median { it.y }, median { it.faceX }, median { it.faceY }, median { it.scale },
        median { it.leftX }, median { it.leftY }, median { it.rightX }, median { it.rightY },
        if(samples.all{it.head!=null}) HeadPose(median{it.head!!.yaw},median{it.head!!.pitch},median{it.head!!.roll},
            median{it.head!!.x},median{it.head!!.y},median{it.head!!.iris}) else null,
        samples.first().source)
}
