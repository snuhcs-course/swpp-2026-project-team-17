package dev.gazedot

import kotlin.math.abs

/** Circular angles and a single session reference prevent pose changes becoming eye labels. */
object CalibrationPose {
    fun angle(value: Double, reference: Double): Double =
        ((value - reference + 540) % 360 + 360) % 360 - 180

    fun stable(value: HeadPose, reference: HeadPose): Boolean =
        listOf(value.yaw,value.pitch,value.roll,value.x,value.y,value.iris,
            reference.yaw,reference.pitch,reference.roll,reference.x,reference.y,reference.iris).all { it.isFinite() } &&
        value.iris > 0 && reference.iris > 0 &&
        abs(angle(value.yaw,reference.yaw)) <= 4 &&
        abs(angle(value.pitch,reference.pitch)) <= 4 &&
        abs(angle(value.roll,reference.roll)) <= 4 &&
        // Projected iris diameter changes with gaze; it is not a head-motion gate.
        // Face-scale consistency is checked independently by CalibrationAudit.
        abs(value.x-reference.x) <= .025 && abs(value.y-reference.y) <= .025
}
