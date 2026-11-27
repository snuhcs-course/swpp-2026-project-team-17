package dev.gazedot

import kotlin.math.*

/** Fixed spatial calibration, not a temporal filter or runtime adaptation. */
internal class CalibrationWarp private constructor(
    private val source: List<Pair<Double, Double>>,
    private val target: List<Pair<Double, Double>>,
    val maxGain: Double,
) {
    fun map(p: Pair<Double, Double>): Pair<Double, Double> {
        for (ids in triangles) {
            val a=source[ids[0]]; val b=source[ids[1]]-a; val c=source[ids[2]]-a
            val d=p-a; val det=cross(b,c)
            val u=cross(d,c)/det; val v=cross(b,d)/det
            if (u >= -1e-9 && v >= -1e-9 && u+v <= 1+1e-9) {
                return target[ids[0]] + (target[ids[1]]-target[ids[0]])*u + (target[ids[2]]-target[ids[0]])*v
            }
        }
        // Blend boundary displacements continuously outside the measured region.
        // Nearest-triangle extrapolation jumps at corners; clamping loses screen edges.
        var total=0.0; var dx=0.0; var dy=0.0
        for (k in boundary.indices) {
            val i=boundary[k]; val j=boundary[(k+1)%boundary.size]
            val edge=source[j]-source[i]; val d=p-source[i]
            val t=((d.first*edge.first+d.second*edge.second)/dot(edge,edge)).coerceIn(0.0,1.0)
            val q=source[i]+edge*t
            val delta=target[i]+(target[j]-target[i])*t-q
            val distance=dot(p-q,p-q)
            if (distance < 1e-18) return p+delta
            val weight=1.0/(distance*distance)
            total+=weight; dx+=weight*delta.first; dy+=weight*delta.second
        }
        return p.first+dx/total to p.second+dy/total
    }

    companion object {
        private val triangles=listOf(intArrayOf(5,3,0),intArrayOf(5,0,1),intArrayOf(3,6,2),intArrayOf(3,2,0),
            intArrayOf(1,0,4),intArrayOf(1,4,8),intArrayOf(0,2,7),intArrayOf(0,7,4))
        private val boundary=listOf(5,3,6,2,7,4,8,1)
        fun fit(source: List<Pair<Double,Double>>, target: List<Pair<Double,Double>>): CalibrationWarp? {
            if (source.size!=9 || target.size!=9 || (source+target).any { !it.first.isFinite() || !it.second.isFinite() }) return null
            var gain=1.0
            for (ids in triangles) {
                val b=source[ids[1]]-source[ids[0]]; val c=source[ids[2]]-source[ids[0]]
                val tb=target[ids[1]]-target[ids[0]]; val tc=target[ids[2]]-target[ids[0]]
                val det=cross(b,c)
                if (abs(det)<1e-5 || det*cross(tb,tc)<=0) return null
                val a=(tb.first*c.second-tc.first*b.second)/det
                val h=(-tb.first*c.first+tc.first*b.first)/det
                val v=(tb.second*c.second-tc.second*b.second)/det
                val d=(-tb.second*c.first+tc.second*b.first)/det
                val trace=a*a+h*h+v*v+d*d
                val local=sqrt((trace+sqrt(max(0.0,trace*trace-4*(a*d-h*v).pow(2))))/2)
                if (!local.isFinite() || local>2.5) return null
                gain=max(gain,local)
            }
            return CalibrationWarp(source.toList(),target.toList(),gain)
        }
        private operator fun Pair<Double,Double>.minus(b: Pair<Double,Double>)=first-b.first to second-b.second
        private operator fun Pair<Double,Double>.plus(b: Pair<Double,Double>)=first+b.first to second+b.second
        private operator fun Pair<Double,Double>.times(t: Double)=first*t to second*t
        private fun cross(a: Pair<Double,Double>,b: Pair<Double,Double>)=a.first*b.second-a.second*b.first
        private fun dot(a: Pair<Double,Double>,b: Pair<Double,Double>)=a.first*b.first+a.second*b.second
    }
}
