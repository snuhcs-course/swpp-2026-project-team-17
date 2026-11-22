package dev.gazedot

import kotlin.math.*

/** Main-thread, bounded numeric diagnostics. No camera images or message content. */
object GazeTrace {
    data class Row(val at:Long,val latency:Long,val eye:String,val raw:Pair<Double,Double>?,
        val mapped:Pair<Double,Double>?,val head:List<Double>?)
    private val rows=ArrayDeque<Row>()
    fun record(at:Long,latency:Long,eye:EyeState,f:EyeFeatures?,model:GazeModel?) {
        rows.addLast(Row(at,latency,eye.name,f?.let{it.x to it.y},f?.let{model?.predictUncorrected(it)},
            f?.head?.let{listOf(it.yaw,it.pitch,it.roll,it.x,it.y,it.iris)}))
        while(rows.size>300)rows.removeFirst()
    }
    fun since(at:Long)=rows.filter{it.at>=at}
    fun rms(points:List<Pair<Double,Double>>):Double {
        if(points.isEmpty())return 0.0
        val x=points.map{it.first}.average();val y=points.map{it.second}.average()
        return sqrt(points.map{(it.first-x).pow(2)+(it.second-y).pow(2)}.average())
    }
    fun report(rows:List<Row>,target:Pair<Double,Double>,gain:Double):String {
        val fresh=rows.filter{it.latency<=500 && it.eye==EyeState.OPEN.name}
        val raw=fresh.mapNotNull{it.raw};val mapped=fresh.mapNotNull{it.mapped}
        val latency=rows.map{it.latency}.sorted()
        if(raw.size>=12 && mapped.size<12)return "눈 특징 원본 ${raw.size}개 · 흔들림 RMS %.3f 특징단위\n사용 가능한 보정 좌표가 없어 좌표 오차는 계산하지 않았습니다. calibration 품질 결과와 함께 확인하세요.".format(rms(raw))
        if(raw.size<12 || mapped.size<12)return "유효 표본 부족: 전체 ${rows.size}, 원본 ${raw.size}, 좌표 ${mapped.size}\n500ms 초과 ${rows.count{it.latency>500}} · 눈 검출과 처리 지연부터 확인해야 합니다."
        val rawRms=rms(raw);val mappedRms=rms(mapped)
        val error=sqrt(mapped.map{(it.first-target.first).pow(2)+(it.second-target.second).pow(2)}.average())
        val note=when {
            mappedRms>.08 -> "필터 전 화면 좌표가 불안정합니다. 원본 흔들림과 보정 배율을 함께 확인해야 합니다."
            error>.15 -> "흔들림보다 일정한 위치 오차가 큽니다. 보정·머리 위치 변화 확인이 필요합니다."
            else -> "이번 고정 응시에서는 큰 오차가 확인되지 않았습니다. 다른 위치·자세 검증이 필요합니다."
        }
        return "표본 ${rows.size} / 유효 ${mapped.size}\n눈 특징 원본 흔들림 RMS %.3f 특징단위\n필터 전 화면 흔들림 RMS %.3f\n목표점 오차 RMS %.3f (가로·세로 정규화 단위)\n보정 최대 배율 %.3f 화면단위/특징단위\n처리 지연 P90 %d ms / 500ms 초과 %d개\n%s\n원인 확정이 아닌 측정 결과입니다.".format(rawRms,mappedRms,error,gain,latency[((latency.size-1)*.9).toInt()],rows.count{it.latency>500},note)
    }
}
