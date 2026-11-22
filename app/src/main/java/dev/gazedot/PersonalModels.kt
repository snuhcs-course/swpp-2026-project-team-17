package dev.gazedot

import kotlin.math.*

data class CorrectionAnchor(val rawX: Double, val rawY: Double, val targetX: Double, val targetY: Double)

/** Small residual model. Axis-wise scale and offset, regularized toward identity. */
data class ResidualCorrection(val sx: Double, val ox: Double, val sy: Double, val oy: Double) {
    fun apply(x: Double, y: Double): Pair<Double, Double> =
        (x + (sx*x + ox - x).coerceIn(-.18, .18)) to
        (y + (sy*y + oy - y).coerceIn(-.18, .18))

    companion object {
        fun fit(a: List<CorrectionAnchor>): ResidualCorrection? {
            if (a.size < 5 || a.any { !listOf(it.rawX,it.rawY,it.targetX,it.targetY).all(Double::isFinite) }) return null
            fun axis(x: List<Double>, y: List<Double>): Pair<Double,Double>? {
                if (x.max() - x.min() < .15 || y.max() - y.min() < .3) return null
                val mx=x.average(); val my=y.average()
                val variance=x.sumOf { (it-mx).pow(2) }
                val slope=((x.indices.sumOf { (x[it]-mx)*(y[it]-my) } + .03)/(variance+.03)).coerceIn(.65,1.5)
                return slope to (my-slope*mx).coerceIn(-.25,.25)
            }
            val x=axis(a.map { it.rawX },a.map { it.targetX }) ?: return null
            val y=axis(a.map { it.rawY },a.map { it.targetY }) ?: return null
            return ResidualCorrection(x.first,x.second,y.first,y.second)
        }
        fun error(a: List<CorrectionAnchor>, correction: ResidualCorrection?): Double =
            if(a.isEmpty()) Double.POSITIVE_INFINITY else a.map {
                val p=correction?.apply(it.rawX,it.rawY) ?: (it.rawX to it.rawY)
                hypot(p.first.coerceIn(0.0,1.0)-it.targetX,p.second.coerceIn(0.0,1.0)-it.targetY)
            }.average()
        fun accept(validation: List<CorrectionAnchor>, candidate: ResidualCorrection, old: ResidualCorrection?): Boolean {
            if(validation.size < 4) return false
            val before=error(validation,old); val after=error(validation,candidate)
            return before.isFinite() && after.isFinite() && after <= before-.01 &&
                validation.all { error(listOf(it),candidate) <= error(listOf(it),old)+.03 }
        }
    }
}

data class SelectionCandidate(val id: String, val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun distance(x: Double,y: Double)=hypot(x-x.coerceIn(left,right),y-y.coerceIn(top,bottom))
}

/** Conditional softmax with spatial, personal frequency and previous-choice features. */
class PersonalTargetRanker {
    var weights=doubleArrayOf(-4.0,.25,.25,.1); private set
    private val counts=linkedMapOf<String,Double>()
    private var previous=""
    var confirmations=0; private set
    private fun key(screen: String,id: String)="$screen:$id"
    private fun transition(screen: String,id: String)="$screen:$previous>$id"
    fun features(screen: String,c: SelectionCandidate,x: Double,y: Double)=doubleArrayOf(
        c.distance(x,y).coerceIn(0.0,1.0),
        ln(1+(counts[key(screen,c.id)] ?: 0.0)).coerceAtMost(4.0)/4,
        ln(1+(counts[transition(screen,c.id)] ?: 0.0)).coerceAtMost(4.0)/4,
        sqrt((c.right-c.left)*(c.bottom-c.top)).coerceIn(0.0,1.0))
    fun probabilities(features: List<DoubleArray>): List<Double> {
        if(features.isEmpty()) return emptyList()
        require(features.all { it.size==4 && it.all(Double::isFinite) })
        val scores=features.map { f -> weights.indices.sumOf { weights[it]*f[it] } }
        val ex=scores.map { exp(it-scores.max()) }; val sum=ex.sum()
        return ex.map { it/sum }
    }
    fun confirm(screen: String, candidates: List<SelectionCandidate>, frozenFeatures: List<DoubleArray>, selected: Int) {
        require(selected in candidates.indices && candidates.size==frozenFeatures.size)
        val p=probabilities(frozenFeatures)
        for(j in weights.indices) {
            val gradient=frozenFeatures.indices.sumOf { i -> (p[i]-(if(i==selected) 1.0 else 0.0))*frozenFeatures[i][j] }
            weights[j]=(weights[j]-.15*(gradient+.002*weights[j])).coerceIn(-8.0,8.0)
        }
        // Recency weighting and bounded storage avoid an unbounded history.
        counts.replaceAll { _,v -> v*.995 }
        val id=candidates[selected].id
        listOf(key(screen,id),transition(screen,id)).forEach { counts[it]=(counts[it] ?: 0.0)+1 }
        while(counts.size>256) counts.remove(counts.minBy { it.value }.key)
        previous=id; confirmations++
    }
    fun encode()=listOf("1",confirmations.toString(),weights.joinToString(","),
        counts.entries.joinToString(";") { "${it.key}=${it.value}" }).joinToString("\n")
    fun restore(text: String): Boolean = runCatching {
        val lines=text.split('\n'); require(lines.size==4 && lines[0]=="1")
        val w=lines[2].split(',').map(String::toDouble); require(w.size==4 && w.all { it.isFinite() && abs(it)<=8 })
        val n=lines[1].toInt(); require(n>=0)
        val c=linkedMapOf<String,Double>()
        if(lines[3].isNotEmpty()) lines[3].split(';').forEach {
            val entry=it.split('='); require(entry.size==2 && entry[0].length<=220)
            val v=entry[1].toDouble(); require(v.isFinite() && v>=0); c[entry[0]]=v
        }
        require(c.size<=256)
        weights=w.toDoubleArray(); confirmations=n; counts.clear(); counts.putAll(c); previous=""
        true
    }.getOrDefault(false)
}
