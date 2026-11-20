package dev.gazedot

import kotlin.math.*

data class CalibrationFixation(val samples: List<EyeFeatures>, val x: Double, val y: Double)
data class CalibrationAuditResult(val model: GazeModel?, val report: String)

/** Temporal holdout at each target. Never evaluate only the five fitted medians. */
object CalibrationAudit {
    fun fit(groups: List<CalibrationFixation>): CalibrationAuditResult {
        if(groups.size<5 || groups.any{it.samples.size<20}) return CalibrationAuditResult(null,"표본 부족 · 각 점 20개 이상 필요")
        // Equal target weights; early samples fit, later samples independently check stability.
        val selected=groups.map { it.samples.takeLast(22) }
        val train=groups.indices.flatMap { i -> selected[i].take(14).map { Anchor(it,groups[i].x,groups[i].y) } }
        val model=GazeModel.fit(train)
        val centers=selected.map(::medianFeatures)
        fun span(axis:(EyeFeatures)->Double)=centers.maxOf(axis)-centers.minOf(axis)
        val sx=span{it.x};val sy=span{it.y}
        val noise=sqrt(selected.indices.flatMap{i->selected[i].map{(it.x-centers[i].x).pow(2)+(it.y-centers[i].y).pow(2)}}.average())
        val raw="iTracker 목표 간 범위 X %.3f / Y %.3f cm, 고정 응시 흔들림 RMS %.3f cm".format(sx,sy,noise)
        if(model==null)return CalibrationAuditResult(null,"보정 불가 · 출력 분리 또는 적합도 부족\n$raw")
        val jitter=selected.indices.map { i ->
            val pts=selected[i].map { model.predictBase(it)!! }
            val mx=pts.map{it.first}.average();val my=pts.map{it.second}.average()
            sqrt(pts.map{(it.first-mx).pow(2)+(it.second-my).pow(2)}.average())
        }.maxOrNull()!!
        val errors=groups.indices.flatMap{i->selected[i].drop(14).map {
            val p=model.predictBase(it)!!;hypot(p.first-groups[i].x,p.second-groups[i].y)
        }}.sorted()
        val rms=sqrt(errors.map{it*it}.average())
        val p90=errors[((errors.size-1)*.9).toInt()]
        // Explicit engineering limits in normalized screen units, not a clinical accuracy claim.
        val accepted=jitter<=.08 && rms<=.12 && p90<=.20
        val report="$raw\n보정 배율 최대 %.3f 화면단위/cm\n보정 후 고정점 흔들림 최대 RMS %.3f\n미학습 후반 표본 오차 RMS %.3f / P90 %.3f\n%s\n학습 14개/점, 검증 나머지 · 동일 목표의 시간 분리 검증(새 위치 정확도 검증은 별도)".format(
            model.mappingGain,jitter,rms,p90,if(accepted) "보정 품질 검사 통과" else "보정 품질 미달 · 흔들리는 보정값을 적용하지 않았습니다")
        return CalibrationAuditResult(if(accepted) model else null,report)
    }
}
