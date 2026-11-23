package dev.gazedot

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class TrackerStateRecoveryTest {
    @Before @After fun clean() {
        TrackerState.cancelActions = null
        TrackerState.contextChanged = null
        TrackerState.listener = null
        TrackerState.trackingStopped()
        TrackerState.model = null
        TrackerState.fault = null
        TrackerState.speaking = false
        TrackerState.precisionTraining = false
        TrackerState.writingOwnsInput = false
        TrackerState.interactionAllowed = false
    }
    private fun enabled() {
        TrackerState.running = true
        TrackerState.controls = true
        TrackerState.interactionAllowed = true
        // A calibration fixture for gating tests, never a device tracking result.
        TrackerState.model = GazeModel.fit(listOf(-.05, 0.0, .05).flatMap { x ->
            listOf(-.04, 0.0, .04).map { y -> Anchor(EyeFeatures(x, y, .5, .5, .3), .5 + x * 6, .5 + y * 7) }
        })!!
    }
    @Test fun precisionMeasurementBlocksActionsAndRecoveryThenReleasesActions() {
        enabled()
        TrackerState.precisionTraining = true
        assertFalse(TrackerState.externalActionsAllowed())
        assertFalse(TrackerState.recognitionHelpEligible(true))
        TrackerState.precisionTraining = false
        assertTrue(TrackerState.externalActionsAllowed())
    }
    @Test fun helpAndRecoveryTransitionBlockExternalActions() {
        enabled()
        assertTrue(TrackerState.externalActionsAllowed())
        TrackerState.helpVisible = true
        assertFalse(TrackerState.externalActionsAllowed())
        // The card must stay eligible while it is visible, or it would instantly close itself.
        assertTrue(TrackerState.recognitionHelpEligible(true))
        TrackerState.helpVisible = false
        TrackerState.recoveryOpening = true
        assertFalse(TrackerState.externalActionsAllowed())
        assertFalse(TrackerState.recognitionHelpEligible(true))
    }
    @Test fun everyNonOperableContextSuppressesLossHelp() {
        val disable: List<() -> Unit> = listOf(
            { TrackerState.running = false }, { TrackerState.controls = false },
            { TrackerState.interactionAllowed = false }, { TrackerState.collecting = true },
            { TrackerState.speaking = true }, { TrackerState.model = null },
            { TrackerState.fault = TrackingFault("camera", "retry") },
            { TrackerState.signalPending = true }, { TrackerState.recoveryOpening = true },
        )
        disable.forEach { change ->
            clean(); enabled()
            assertTrue(TrackerState.recognitionHelpEligible(true))
            change()
            assertFalse(TrackerState.recognitionHelpEligible(true))
        }
        clean(); enabled()
        assertFalse(TrackerState.recognitionHelpEligible(false))
    }
    @Test fun onlyAnExplicitBeginStartsCalibrationAndItCancelsActions() {
        enabled()
        var cancellations = 0
        TrackerState.cancelActions = { cancellations++ }
        TrackerState.eyeState = EyeState.LOST
        TrackerState.publishSample(GazeSample(1_000, TrackingQuality.LOST, null, null, null, 0f, emptyList()), "lost")
        assertFalse(TrackerState.collecting)
        val session = TrackerState.beginCalibration()
        assertTrue(TrackerState.collecting)
        assertFalse(TrackerState.recognitionHelpEligible(true))
        assertTrue(cancellations > 0)
        TrackerState.cancelCalibration()
        assertFalse(TrackerState.finishCalibration(session, TrackerState.model))
    }
    @Test fun pauseNeverAutomaticallyResumesAndErrorHasItsOwnStatus() {
        enabled()
        TrackerState.controls = false
        TrackerState.eyeState = EyeState.OPEN
        TrackerState.publishSample(GazeSample(1_000, TrackingQuality.TRACKING, null,
            .5 to .5, .5 to .5, 0f, emptyList()), "fresh")
        assertFalse(TrackerState.controls)
        assertEquals(TrackingPhase.PAUSED, TrackerState.currentStatus(1_000).phase)
        TrackerState.fault = TrackingFault("accessibility interrupted", "settings", RecoverySetting.ACCESSIBILITY)
        assertEquals(TrackingPhase.ERROR, TrackerState.currentStatus(1_000).phase)
    }
    @Test fun settingsNavigationCancelsPendingActionsOnEntryAndExit() {
        enabled()
        var cancellations = 0
        TrackerState.cancelActions = { cancellations++ }
        TrackerState.settingsStarted()
        val entryCancellations = cancellations
        assertTrue(entryCancellations > 0)
        try {
            assertFalse(TrackerState.externalActionsAllowed())
            assertFalse(TrackerState.recognitionHelpEligible(true))
        } finally { TrackerState.settingsStopped() }
        assertTrue(TrackerState.externalActionsAllowed())
        assertTrue(cancellations > entryCancellations)
    }

    @Test fun accessibilityReconnectRestoresTrackingAndLossHelpAfterLeavingRecovery() {
        enabled()
        val model = TrackerState.model
        TrackerState.accessibilityDisconnected()
        // Android may interrupt and then destroy the same connection.
        TrackerState.accessibilityDisconnected()
        assertFalse(TrackerState.controls)
        assertFalse(TrackerState.recognitionHelpEligible(false))
        TrackerState.settingsStarted()
        try {
            TrackerState.accessibilityConnected()
            assertTrue(TrackerState.controls)
            assertNull(TrackerState.fault)
            assertSame(model, TrackerState.model)
            assertNull(TrackerState.gaze)
            assertFalse(TrackerState.externalActionsAllowed())
        } finally { TrackerState.settingsStopped() }
        assertTrue(TrackerState.recognitionHelpEligible(true))
        val policy = RecognitionLossPolicy()
        assertFalse(policy.update(0, 0, true, TrackerState.recognitionHelpEligible(true)))
        for (now in 100L..19_900L step 100) assertFalse(policy.update(now, now, true, true))
        assertTrue(policy.update(20_000, 20_000, true, true))
    }

    @Test fun accessibilityReconnectPreservesExplicitPauseBeforeOrDuringDisconnect() {
        enabled()
        TrackerState.pauseControls()
        TrackerState.accessibilityDisconnected()
        TrackerState.accessibilityConnected()
        assertFalse(TrackerState.controls)
        enabled()
        TrackerState.accessibilityDisconnected()
        TrackerState.pauseControls()
        TrackerState.accessibilityConnected()
        assertFalse(TrackerState.controls)
    }

    @Test fun reconnectCannotRestartStoppedTrackingOrClearCameraFault() {
        enabled()
        TrackerState.accessibilityDisconnected()
        TrackerState.trackingStopped()
        TrackerState.accessibilityConnected()
        assertFalse(TrackerState.running)
        assertFalse(TrackerState.controls)
        enabled()
        val cameraFault = TrackingFault("camera", "retry", RecoverySetting.CAMERA)
        TrackerState.fault = cameraFault
        TrackerState.accessibilityDisconnected()
        TrackerState.accessibilityConnected()
        assertSame(cameraFault, TrackerState.fault)
        assertFalse(TrackerState.controls)
    }
    @Test fun writingAndPrecisionMeasurementIndependentlyBlockUnderlyingActions() {
        enabled()
        TrackerState.writingOwnsInput = true
        assertFalse(TrackerState.externalActionsAllowed())
        assertFalse(TrackerState.recognitionHelpEligible(true))
        TrackerState.precisionTraining = true
        TrackerState.writingOwnsInput = false
        assertFalse(TrackerState.externalActionsAllowed())
        TrackerState.precisionTraining = false
        assertTrue(TrackerState.externalActionsAllowed())
    }
}
