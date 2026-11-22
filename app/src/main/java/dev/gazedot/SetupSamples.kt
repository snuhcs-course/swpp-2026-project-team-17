package dev.gazedot

import kotlin.math.abs

/** Registration samples are separate from actionable gaze. Brief blinks do not erase good data. */
class SetupSamples {
    private val frames=mutableListOf<FaceFrame>()
    private var lastSeen=0L
    val count get()=frames.size
    fun reset(){frames.clear();lastSeen=0}
    fun add(frame:FaceFrame?,now:Long):EyeProfile? {
        if(frames.lastOrNull()?.let{now-it.at>1200}==true)frames.clear()
        frames.removeAll{now-it.at>8000}
        if(frame==null || now-frame.at !in 0..750 || frame.at<=lastSeen)return null
        lastSeen=frame.at
        if(!frame.aligned())return null
        if(frames.firstOrNull()?.let{!samePose(frame,it)}==true)frames.clear()
        frames+=frame
        if(frames.size>45)frames.removeAt(0)
        return EyeProfile.fit(frames)
    }
    companion object {
        fun samePose(a:FaceFrame,b:FaceFrame)=
            abs(CalibrationPose.angle(a.head.yaw,b.head.yaw))<5 &&
            abs(CalibrationPose.angle(a.head.pitch,b.head.pitch))<5 &&
            abs(CalibrationPose.angle(a.head.roll,b.head.roll))<5 &&
            abs(a.head.x-b.head.x)<.03 && abs(a.head.y-b.head.y)<.03
    }
}
