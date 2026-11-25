package dev.gazedot

/** Non-blocking eyelid baseline. No face box, head pose, iris size or distance target. */
class NaturalEyeBaseline {
    private val frames = mutableListOf<FaceFrame>()
    private var lastAt = -1L
    private var profile: EyeProfile? = null
    fun add(frame: FaceFrame?, hasBlinkScores: Boolean): EyeProfile? {
        profile?.let { return it }
        if (frame == null || frame.at <= lastAt) return null
        lastAt = frame.at
        frames.removeAll { frame.at - it.at > 5_000 }
        if (!hasBlinkScores || !listOf(frame.leftOpen, frame.rightOpen, frame.leftBlink, frame.rightBlink).all { it.isFinite() } ||
            frame.leftOpen !in .04.. .6 || frame.rightOpen !in .04.. .6 ||
            frame.leftBlink !in 0.0.. .15 || frame.rightBlink !in 0.0.. .15) return null
        frames.add(frame)
        if (frames.size < 20 || frames.last().at - frames.first().at < 1_000) return null
        fun median(select: (FaceFrame) -> Double) = frames.map(select).sorted()[frames.size / 2]
        // Diameter fields are observational only; they do not gate or normalize screen coordinates.
        profile = EyeProfile(median { it.leftOpen }, median { it.rightOpen },
            median { it.leftDiameter }, median { it.rightDiameter }, median { it.leftRatio }, median { it.rightRatio })
        frames.clear()
        return profile
    }
}
