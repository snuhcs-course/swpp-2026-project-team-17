package dev.gazedot

/**
 * Head position the participant holds during one pass. The phone stays fixed on its stand.
 * [label] is shown throughout the pass, [enterInstruction] during the break before the pass,
 * [holdInstruction] during the pass's mid-pass blink break.
 * Positions are described qualitatively; no distance or angle is measured or enforced.
 */
enum class HeadPosition(
    val id: String,
    val label: String,
    val enterInstruction: List<String>,
    val holdInstruction: String,
) {
    ORDINARY("ordinary", "기본 위치 · 정면을 보고 그대로 유지",
        listOf("편한 정면 자세를 잡아 주세요", "이 위치가 기본 위치입니다"),
        "머리는 기본 위치 그대로 · 기기는 고정"),
    HIGHER("higher", "조금 높은 위치 · 정면을 보고 그대로 유지",
        listOf("머리를 조금 위로 옮겨 주세요", "기본 위치보다 살짝 높게 · 정면 유지"),
        "머리는 조금 높은 위치 그대로 · 기기는 고정"),
    LOWER("lower", "조금 낮은 위치 · 정면을 보고 그대로 유지",
        listOf("머리를 조금 아래로 옮겨 주세요", "기본 위치보다 살짝 낮게 · 정면 유지"),
        "머리는 조금 낮은 위치 그대로 · 기기는 고정"),
}

/**
 * Candidate B: three passes through the nine targets, one per head position relative to the stationary phone.
 * The participant moves only during the inter-pass breaks and holds still while each pass is collected.
 */
object CalibrationPlan {
    /** Read by evaluation tooling to identify how a calibration was collected. */
    const val ID = "calibration_b_three_pass_positions"
    /** Head position for passes 1..[CalibrationRules.PASSES]. */
    val positions: List<HeadPosition> = listOf(HeadPosition.ORDINARY, HeadPosition.HIGHER, HeadPosition.LOWER)
    const val PASS_BREAK_FOOTER = "기기는 움직이지 마세요 · 눈은 편하게 깜빡이세요"
}
