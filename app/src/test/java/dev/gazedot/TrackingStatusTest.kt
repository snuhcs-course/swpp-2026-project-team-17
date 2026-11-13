package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class TrackingStatusTest {
    // Pure state fixtures; these do not simulate a successful camera session.
    private fun sample(quality: TrackingQuality = TrackingQuality.TRACKING,
        point: Pair<Double, Double>? = .5 to .5, at: Long = 1_000) =
        GazeSample(at, quality, null, point, point, 0f, emptyList())
    private fun status(running: Boolean = true, controls: Boolean = true, collecting: Boolean = false,
        calibrated: Boolean = true, result: GazeSample? = sample(), eye: EyeState = EyeState.OPEN,
        fault: TrackingFault? = null, signal: Boolean = false) =
        trackingStatus(running, controls, collecting, calibrated, result, eye, 1_000, fault, signal)

    @Test fun normalTrackingNeverAddsAnOverlayBanner() {
        val result = status()
        assertEquals(TrackingPhase.TRACKING, result.phase)
        assertEquals("", result.overlayMessage)
    }
    @Test fun confirmedLossAndUnavailableCoordinatesAreDifferent() {
        assertEquals(TrackingPhase.MISSING, status(result = sample(TrackingQuality.LOST, null), eye = EyeState.LOST).phase)
        assertEquals(TrackingPhase.COORDINATES_UNAVAILABLE, status(result = sample(TrackingQuality.OUT_OF_RANGE, null)).phase)
        assertEquals(TrackingPhase.NEEDS_CALIBRATION, status(calibrated = false, result = sample(TrackingQuality.OUT_OF_RANGE, null)).phase)
    }
    @Test fun blinkWinkAndAmbiguityAreNeverClassifiedAsMissing() {
        for (eye in listOf(EyeState.CLOSED, EyeState.LEFT_WINK, EyeState.RIGHT_WINK, EyeState.UNCERTAIN)) {
            val result = status(result = sample(TrackingQuality.UNCERTAIN, null), eye = eye)
            assertEquals(TrackingPhase.EYES_PENDING, result.phase)
            assertEquals("", result.overlayMessage)
        }
    }
    @Test fun errorAndSignalLossTakePrecedenceOverOldMissingEyes() {
        val lost = sample(TrackingQuality.LOST, null)
        assertEquals(TrackingPhase.ERROR, status(result = lost, eye = EyeState.LOST,
            fault = TrackingFault("camera", "retry")).phase)
        assertEquals(TrackingPhase.SIGNAL_PENDING, status(result = lost, eye = EyeState.LOST, signal = true).phase)
        assertEquals(TrackingPhase.SIGNAL_PENDING, status(result = sample(at = 500), eye = EyeState.LOST).phase)
    }
    @Test fun stoppedPausedAndCalibratingRemainExplicit() {
        assertEquals(TrackingPhase.STOPPED, status(running = false).phase)
        assertEquals(TrackingPhase.PAUSED, status(controls = false, eye = EyeState.LOST).phase)
        assertEquals(TrackingPhase.CALIBRATING, status(collecting = true).phase)
        assertEquals(TrackingPhase.STARTING, status(result = null).phase)
    }
    @Test fun missingWithoutCalibrationStillReportsTheObservedProblem() {
        assertEquals(TrackingPhase.MISSING, status(calibrated = false,
            result = sample(TrackingQuality.LOST, null), eye = EyeState.LOST).phase)
    }
}
