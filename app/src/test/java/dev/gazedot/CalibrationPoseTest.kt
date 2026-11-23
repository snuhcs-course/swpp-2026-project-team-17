package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class CalibrationPoseTest {
    private val reference=HeadPose(12.0,18.0,3.0,.5,.5,.025)
    @Test fun phoneTiltDoesNotRequireWorldZeroPose() {
        assertTrue(CalibrationPose.stable(reference,reference))
        assertTrue(CalibrationPose.stable(reference.copy(pitch=20.0),reference))
    }
    @Test fun eyeCalibrationRejectsHeadRotationEvenWhenFaceCentreIsUnchanged() {
        assertFalse(CalibrationPose.stable(reference.copy(yaw=20.0),reference))
        assertFalse(CalibrationPose.stable(reference.copy(roll=10.0),reference))
        assertTrue(CalibrationPose.stable(reference.copy(iris=.031),reference))
        assertFalse(CalibrationPose.stable(reference.copy(x=.55),reference))
        assertFalse(CalibrationPose.stable(reference.copy(iris=0.0),reference))
    }
    @Test fun angleWrapDoesNotCreateLargeHeadMovement() {
        val r=reference.copy(pitch=179.0)
        val p=r.copy(pitch=-179.0)
        assertEquals(2.0,CalibrationPose.angle(p.pitch,r.pitch),.00001)
        assertTrue(CalibrationPose.stable(p,r))
        assertEquals(2.0/12,p.relativeTo(r)[1],.00001)
    }
    @Test fun changingProjectedIrisSizeIsNotHeadMovement() {
        for (diameter in listOf(.018, .022, .025, .031))
            assertTrue(CalibrationPose.stable(reference.copy(iris=diameter),reference))
    }
}
