package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot

data class EyeObservation(val state: EyeState, val features: EyeFeatures?, val leftOpen: Double, val rightOpen: Double)

/** Gesture classification is independent of the original coordinate extractor. */
object EyeObservations {
    fun observe(p: List<NormalizedLandmark>, width: Int, height: Int, face: FaceFrame? = null, profile: EyeProfile? = null, hasBlinkEvidence: Boolean = false, blinkScores: Pair<Double,Double>? = null): EyeObservation {
        if (p.size < 478 || width <= 0 || height <= 0 ||
            p.take(478).any { !it.x().isFinite() || !it.y().isFinite() }) {
            return EyeObservation(EyeState.LOST, null, 0.0, 0.0)
        }
        fun openness(a: Int, b: Int, top: Int, bottom: Int): Double? {
            fun distance(i: Int, j: Int) = hypot((p[i].x() - p[j].x()).toDouble() * width,
                (p[i].y() - p[j].y()).toDouble() * height)
            val span = distance(a, b)
            return if (span < 12) null else distance(top, bottom) / span
        }
        val right = openness(33, 133, 159, 145)
        val left = openness(362, 263, 386, 374)
        if (right == null || left == null) return EyeObservation(EyeState.LOST, null, 0.0, 0.0)
        // Blendshapes do not depend on the optional pose matrix/iris diameter extraction.
        val scores=blinkScores ?: face?.let { it.leftBlink to it.rightBlink }
        val state = personalizedState(left,right,scores?.first ?: 0.0,scores?.second ?: 0.0,
            profile,hasBlinkEvidence && scores!=null)
        // Partial closure can displace iris landmarks: never expose them as calibration/gaze samples.
        return EyeObservation(state, if (state == EyeState.OPEN) LandmarkFeatures.extract(p, width, height)?.copy(head=face?.head) else null, left, right)
    }
    fun personalizedState(left:Double,right:Double,leftBlink:Double,rightBlink:Double,profile:EyeProfile?,hasBlinkEvidence:Boolean=true):EyeState {
        val l=if(profile!=null && profile.leftOpen>0) left/profile.leftOpen*.22 else left
        val r=if(profile!=null && profile.rightOpen>0) right/profile.rightOpen*.22 else right
        // Looking down/sideways narrows the lids without being a blink. With a personal
        // baseline AND low independent blink scores, accept half-open visible eyes.
        // Real closure/winks and ambiguous blink evidence keep the conservative classifier.
        if (hasBlinkEvidence && l.isFinite() && r.isFinite() &&
            leftBlink in 0.0..0.20 && rightBlink in 0.0..0.20 &&
            (if(profile==null) left>=.04 && right>=.04 else l>=.11 && r>=.11)) {
            return EyeState.OPEN
        }
        return eyeState(if(hasBlinkEvidence && leftBlink>.65) .05 else l,if(hasBlinkEvidence && rightBlink>.65) .05 else r)
    }
    fun eyeState(leftOpen: Double, rightOpen: Double): EyeState = when {
        !leftOpen.isFinite() || !rightOpen.isFinite() -> EyeState.LOST
        leftOpen < .11 && rightOpen < .11 -> EyeState.CLOSED
        leftOpen < .11 && rightOpen > .15 -> EyeState.LEFT_WINK
        rightOpen < .11 && leftOpen > .15 -> EyeState.RIGHT_WINK
        leftOpen > .15 && rightOpen > .15 -> EyeState.OPEN
        else -> EyeState.UNCERTAIN
    }
}
