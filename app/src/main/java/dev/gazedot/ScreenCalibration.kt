package dev.gazedot

import kotlin.math.*

/** Target acquisition independent of Android UI, head pose, iris diameter and the old gaze model. */
internal class ScreenCalibration(startAt: Long) {
    val targets = listOf(.5 to .5, .08 to .5, .92 to .5, .5 to .08, .5 to .92,
        .08 to .08, .92 to .08, .92 to .92, .08 to .92)
    private val groups = MutableList<CalibrationFixation?>(targets.size) { null }
    private val samples = mutableListOf<EyeFeatures>()
    private var readyAt = startAt + 600
    private var lastSeen = -1L
    private var lastValid = startAt
    private var interrupted = false
    var index = 0; private set
    var review: Review? = null; private set
    var hint = "파란 점 가운데를 바라보세요"; private set
    val count get() = samples.size
    val progress get() = (count / 24f).coerceIn(0f,1f)
    val completedCount get() = groups.count { it != null }
    fun fixations() = groups.filterNotNull()
    data class Review(val model: GazeModel?, val report: String, val worstTarget: Int,
        val rms: Double? = null, val maxTargetError: Double? = null)

    fun observe(sample: GazeSample?, now: Long, targetOnDisplay: Pair<Double,Double>, missingReason: String) {
        if(review!=null) return
        // A malformed future timestamp must not poison all later valid samples.
        if(sample!=null && sample.timestampMs>now) { hint="새 눈 표본 대기";return }
        if(sample!=null && sample.timestampMs<=lastSeen) return
        if(sample!=null) lastSeen=sample.timestampMs
        val f=sample?.calibrationFeatures(now,-1,750)?.takeIf {
            it.source==GazeSource.LANDMARK && it.scale>0 &&
                listOf(it.x,it.y,it.leftX,it.leftY,it.rightX,it.rightY,it.faceX,it.faceY,it.scale).all(Double::isFinite)
        }
        if(f==null) {
            interrupted=true
            if(now-lastValid>1500) samples.clear()
            hint="$missingReason · $count/24개 보관"
            return
        }
        if(now-lastValid>1500) { samples.clear(); readyAt=max(readyAt,sample.timestampMs+600) }
        lastValid=now
        if(interrupted) { readyAt=max(readyAt,sample.timestampMs+150);interrupted=false }
        if(sample.timestampMs<readyAt) { hint="점 가운데에 시선을 잠시 멈춰 주세요";return }
        samples += f
        hint="눈 표본 $count/24개 · 같은 점을 바라보세요"
        if(count<24) return
        groups[index]=CalibrationFixation(samples.toList(),targetOnDisplay.first,targetOnDisplay.second)
        val next=groups.indexOfFirst { it==null }
        if(next>=0) begin(next,now) else { review=fit(groups.filterNotNull()); hint="보정 결과 확인" }
    }
    fun retry(target: Int, now: Long) {
        require(target in targets.indices)
        groups[target]=null;review=null;begin(target,now)
    }
    fun restart(now: Long) { groups.indices.forEach { groups[it]=null };review=null;begin(0,now) }
    private fun begin(target:Int,now:Long) {
        index=target;samples.clear();readyAt=now+600;lastValid=now;interrupted=false
        hint="${target+1}번 점을 바라보세요 · 완료 $completedCount/9"
    }
    companion object {
        fun fit(groups:List<CalibrationFixation>):Review {
            require(groups.size==9 && groups.all { it.samples.size>=24 })
            // Representatives remain available for diagnostics; fit and selection use separate frames.
            val anchors=groups.map { Anchor(medianFeatures(it.samples.take(16)),it.x,it.y) }
            val model=CalibrationMapping.fit(groups)
            if(model==null) {
                val spread=groups.map { g -> val m=medianFeatures(g.samples)
                    g.samples.map { hypot(it.x-m.x,it.y-m.y) }.average() }
                val worst=spread.indices.maxBy { spread[it] }
                val xs=anchors.map { it.eye.x }; val ys=anchors.map { it.eye.y }
                return Review(null,"점별 눈 좌표를 화면 위치와 연결하지 못했습니다.\n눈 좌표 범위 X %.3f / Y %.3f\n${worst+1}번 점을 다시 측정하거나 전체 측정을 다시 할 수 있어요."
                    .format(xs.max()-xs.min(),ys.max()-ys.min()),worst)
            }
            val perTarget=groups.map { g -> g.samples.subList(20,24).map { f ->
                val p=model.predictBase(f)!!;hypot(p.first-g.x,p.second-g.y)
            } }
            val worst=perTarget.indices.maxBy { perTarget[it].average() }
            val rms=sqrt(perTarget.flatten().map { it*it }.average())
            val maxError=perTarget[worst].average()
            val report="9개 점의 보정 모델을 계산했습니다.\n후반 표본 오차 RMS %.1f%% · 최대 %.1f%%\n오차가 가장 큰 점: ${worst+1}번\n같은 점에서의 내부 검사이며 실제 사용 정확도는 별도 확인이 필요합니다."
                .format(rms*100,maxError*100)
            // Untouched final frames report uncertainty without changing the existing apply/retry flow.
            return Review(model,report,worst,rms,maxError)
        }
    }
}
