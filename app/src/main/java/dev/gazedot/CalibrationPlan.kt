package dev.gazedot

/**
 * Head position the participant holds during one pass. The phone stays fixed on its stand.
 * [label] is shown throughout the pass, [enterInstruction] during the break before the pass,
 * [holdInstruction] during the pass's mid-pass blink break.
 */
enum class HeadPosition(
    val id: String,
    val label: String,
    val enterInstruction: List<String>,
    val holdInstruction: String,
) {
    SAME("same", "처음 자세 그대로 · 정면, 같은 거리",
        listOf("눈을 편하게 깜빡여 주세요", "같은 자세와 거리를 그대로 유지해 주세요"),
        "머리와 기기는 그대로 유지해 주세요"),
}

/** Candidate A: three passes through the nine targets, all in one comfortable front-facing head position. */
object CalibrationPlan {
    /** Read by evaluation tooling to identify how a calibration was collected. */
    const val ID = "calibration_a_three_pass_static"
    /** Head position for passes 1..[CalibrationRules.PASSES]. */
    val positions: List<HeadPosition> = List(CalibrationRules.PASSES) { HeadPosition.SAME }
    const val PASS_BREAK_FOOTER = "머리와 기기는 그대로 유지해 주세요"
}
