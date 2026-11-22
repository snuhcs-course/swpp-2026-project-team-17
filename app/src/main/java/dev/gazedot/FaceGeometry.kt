package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

data class HeadPose(val yaw:Double,val pitch:Double,val roll:Double,val x:Double,val y:Double,val iris:Double) {
    fun vector()=doubleArrayOf(yaw/15,pitch/12,roll/12,x/.08,y/.08,ln(iris)/.2)
    fun relativeTo(r:HeadPose)=doubleArrayOf(CalibrationPose.angle(yaw,r.yaw)/15,
        CalibrationPose.angle(pitch,r.pitch)/12,CalibrationPose.angle(roll,r.roll)/12,
        (x-r.x)/.08,(y-r.y)/.08,ln(iris/r.iris)/.2)
    companion object {
        // FaceLandmarkerResult matrices are column-major. Translation is not assumed to be measured cm.
        fun angles(m:FloatArray?):Triple<Double,Double,Double>? {
            if(m==null || m.size!=16 || m.any { !it.isFinite() }) return null
            fun norm(a:Int,b:Int,c:Int)=sqrt((m[a]*m[a]+m[b]*m[b]+m[c]*m[c]).toDouble())
            val a=norm(0,1,2); val b=norm(4,5,6); val c=norm(8,9,10)
            if(minOf(a,b,c)<.001) return null
            return Triple(Math.toDegrees(asin((-m[2]/a).coerceIn(-1.0,1.0))),
                Math.toDegrees(atan2(m[6]/b,m[10]/c)),Math.toDegrees(atan2(m[1]/a,m[0]/a)))
        }
    }
}
data class FaceFrame(val at:Long,val width:Int,val height:Int,val outline:List<Pair<Double,Double>>,
    val head:HeadPose,val leftDiameter:Double,val rightDiameter:Double,val leftRatio:Double,val rightRatio:Double,
    val leftOpen:Double,val rightOpen:Double,val leftBlink:Double,val rightBlink:Double,
    val faceWidth:Double,val faceHeight:Double) {
    fun setupIssue():String? = when {
        !listOf(head.x,head.y,head.yaw,head.pitch,head.roll,leftDiameter,rightDiameter,leftRatio,rightRatio,leftOpen,rightOpen,leftBlink,rightBlink,faceWidth,faceHeight).all{it.isFinite()} -> "얼굴 측정값 확인 중"
        leftBlink>=.25 || rightBlink>=.25 || leftOpen<=.07 || rightOpen<=.07 -> "눈을 자연스럽게 떠 주세요 · 깜빡임은 괜찮아요"
        faceWidth<.30 || faceHeight<.42 || leftDiameter<5 || rightDiameter<5 -> "얼굴을 조금 가까이 가져와 주세요"
        faceWidth>.68 || faceHeight>.88 -> "얼굴을 조금 멀리 두세요"
        abs(head.x-.5)>=.07 || abs(head.y-.5)>=.12 -> "얼굴을 화면 가운데로 옮겨 주세요"
        abs(head.yaw)>=12 || abs(head.pitch)>=12 || abs(head.roll)>=10 -> "얼굴을 카메라 쪽으로 향해 주세요"
        leftRatio !in .15.. .65 || rightRatio !in .15.. .65 -> "홍채 경계 확인 중 · 조명이나 반사를 확인하세요"
        else -> null
    }
    fun aligned()=setupIssue()==null
}
data class EyeProfile(val leftOpen:Double,val rightOpen:Double,val leftDiameter:Double,val rightDiameter:Double,
    val leftRatio:Double,val rightRatio:Double) {
    companion object {
        fun fit(frames:List<FaceFrame>):EyeProfile? {
            if(frames.size<30 || frames.any { !it.aligned() } || frames.zipWithNext().any{it.second.at<=it.first.at} || frames.last().at-frames.first().at<1800) return null
            if(frames.any{!SetupSamples.samePose(it,frames.first())})return null
            fun med(f:(FaceFrame)->Double)=frames.map(f).sorted()[frames.size/2]
            val l=med{it.leftDiameter};val r=med{it.rightDiameter}
            if(frames.count{abs(it.leftDiameter/l-1)<.08 && abs(it.rightDiameter/r-1)<.08}<frames.size*.9) return null
            return EyeProfile(med{it.leftOpen},med{it.rightOpen},l,r,med{it.leftRatio},med{it.rightRatio})
        }
    }
}
object FaceGeometry {
    private val contour=listOf(10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109)
    fun extract(p:List<NormalizedLandmark>,w:Int,h:Int,at:Long,m:FloatArray?,blinks:Map<String,Float>):FaceFrame? {
        if(p.size<478 || w<=0 || h<=0 || p.any{!it.x().isFinite() || !it.y().isFinite()}) return null
        val angles=HeadPose.angles(m) ?: return null
        fun d(a:Int,b:Int)=hypot((p[a].x()-p[b].x()).toDouble()*w,(p[a].y()-p[b].y()).toDouble()*h)
        val lw=d(362,263);val rw=d(33,133); if(min(lw,rw)<12) return null
        // Opposing iris-boundary landmarks: use both axes, independently for each eye.
        val ld=max(d(474,476),d(475,477));val rd=max(d(469,471),d(470,472))
        if(min(ld,rd)<2 || !ld.isFinite() || !rd.isFinite()) return null
        val outline=contour.map{p[it].x().toDouble() to p[it].y().toDouble()}
        return FaceFrame(at,w,h,outline,HeadPose(angles.first,angles.second,angles.third,p[1].x().toDouble(),p[1].y().toDouble(),(ld+rd)/2/w),
            ld,rd,ld/lw,rd/rw,d(386,374)/lw,d(159,145)/rw,
            blinks["eyeBlinkLeft"]?.toDouble() ?: 1.0,blinks["eyeBlinkRight"]?.toDouble() ?: 1.0,
            outline.maxOf{it.first}-outline.minOf{it.first},outline.maxOf{it.second}-outline.minOf{it.second})
    }
}

data class MotionAnchor(val head:HeadPose,val rawX:Double,val rawY:Double,val targetX:Double,val targetY:Double)
/** User-fitted head/eye residuals. Uses the paper's head-reference idea, not its physical projection equations. */
class HeadCorrection private constructor(val reference:HeadPose,private val wx:DoubleArray,private val wy:DoubleArray,
    private val ranges:DoubleArray) {
    private fun delta(h:HeadPose)=h.relativeTo(reference)
    fun apply(h:HeadPose,p:Pair<Double,Double>):Pair<Double,Double>? {
        if(h.iris<=0 || !h.vector().all{it.isFinite()}) return null
        val d=delta(h)
        if(d.indices.any{abs(d[it])>max(.25,ranges[it]*1.3)}) return null
        val dx=d.indices.sumOf{d[it]*wx[it]};val dy=d.indices.sumOf{d[it]*wy[it]}
        if(abs(dx)>.25 || abs(dy)>.25) return null
        return p.first+dx to p.second+dy
    }
    companion object {
        fun fit(a:List<MotionAnchor>):HeadCorrection? {
            if(a.size<13 || a.any{it.head.iris<=0 || !(it.head.vector().toList()+listOf(it.rawX,it.rawY,it.targetX,it.targetY)).all(Double::isFinite)}) return null
            val ref=a.first().head
            val rows=a.map{it.head.relativeTo(ref)}
            val ranges=DoubleArray(6){i->rows.maxOf{abs(it[i])}}
            if(ranges.any { it < .35 }) return null
            fun solve(target:List<Double>):DoubleArray? {
                val matrix=Array(6){i->DoubleArray(7){j->if(j==6) rows.indices.sumOf{k->rows[k][i]*target[k]} else rows.sumOf{it[i]*it[j]}+if(i==j) .15 else 0.0}}
                for(i in 0..5) {
                    val pivot=(i..5).maxBy{abs(matrix[it][i])};if(abs(matrix[pivot][i])<1e-9)return null
                    val tmp=matrix[i];matrix[i]=matrix[pivot];matrix[pivot]=tmp
                    val div=matrix[i][i];for(j in i..6)matrix[i][j]/=div
                    for(k in 0..5)if(k!=i){val f=matrix[k][i];for(j in i..6)matrix[k][j]-=f*matrix[i][j]}
                }
                return DoubleArray(6){matrix[it][6]}
            }
            return HeadCorrection(ref,solve(a.map{it.targetX-it.rawX})?:return null,solve(a.map{it.targetY-it.rawY})?:return null,ranges)
        }
        fun accept(candidate:HeadCorrection,a:List<MotionAnchor>):Boolean {
            if(a.size<4)return false
            var before=0.0;var after=0.0
            for(v in a){val p=candidate.apply(v.head,v.rawX to v.rawY)?:return false
                val e=hypot(v.rawX.coerceIn(0.0,1.0)-v.targetX,v.rawY.coerceIn(0.0,1.0)-v.targetY)
                val n=hypot(p.first.coerceIn(0.0,1.0)-v.targetX,p.second.coerceIn(0.0,1.0)-v.targetY)
                if(n>e+.005)return false;before+=e;after+=n}
            return after/a.size<=before/a.size-.01
        }
    }
}
