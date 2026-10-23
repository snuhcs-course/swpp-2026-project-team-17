package dev.gazedot

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TrackerStateIntegrationTest {
    private val previous = object : GazePredictor { override fun predict(f: EyeFeatures) = .2 to .3 }
    private val replacement = object : GazePredictor { override fun predict(f: EyeFeatures) = .7 to .8 }
    private var saved = "previous saved model"
    private var cancelled = 0

    @Before fun setUp() {
        TrackerState.listener = null
        TrackerState.cancelActions = null
        TrackerState.cancelCalibration()
        TrackerState.clearCoordinates()
        TrackerState.model = previous
        TrackerState.collecting = false
        TrackerState.cancelActions = { cancelled++ }
    }

    @After fun tearDown() {
        TrackerState.cancelCalibration()
        TrackerState.cancelActions = null
        TrackerState.listener = null
        TrackerState.clearCoordinates()
        TrackerState.model = null
        TrackerState.collecting = false
        TrackerState.running = false
        TrackerState.controls = false
    }

    @Test fun startingRecalibrationKeepsSelectedAndSavedModelsAndCancelsActions() {
        TrackerState.beginCalibration()
        assertTrue(TrackerState.collecting)
        assertSame(previous, TrackerState.model)
        assertEquals("previous saved model", saved)
        assertEquals(1, cancelled)
    }

    @Test fun successfulExplicitCalibrationSavesBeforeReplacingTheModel() {
        val session = TrackerState.beginCalibration()
        assertTrue(TrackerState.finishCalibration(session, replacement) {
            assertSame(previous, TrackerState.model)
            saved = "new calibration"
        })
        assertSame(replacement, TrackerState.model)
        assertEquals("new calibration", saved)
        assertFalse(TrackerState.collecting)
        assertEquals(2, cancelled)
    }

    @Test fun failedCalibrationKeepsPreviousModelsAndClearsCollection() {
        val session = TrackerState.beginCalibration()
        assertFalse(TrackerState.finishCalibration(session, null) { fail("Must not write on fit failure") })
        assertSame(previous, TrackerState.model)
        assertEquals("previous saved model", saved)
        assertFalse(TrackerState.collecting)
    }

    @Test fun cancelledOrInterruptedCalibrationCannotBeSavedByALateCallback() {
        val session = TrackerState.beginCalibration()
        TrackerState.cancelCalibration()
        assertFalse(TrackerState.finishCalibration(session, replacement) { fail("Cancelled session wrote storage") })
        assertSame(previous, TrackerState.model)
        assertEquals("previous saved model", saved)
        assertFalse(TrackerState.collecting)
    }

    @Test fun supersededSessionCannotReplaceTheModelOrEndNewCollection() {
        val abandoned = TrackerState.beginCalibration()
        val current = TrackerState.beginCalibration()
        assertFalse(TrackerState.finishCalibration(abandoned, replacement) { fail("Old session wrote storage") })
        assertTrue(TrackerState.collecting)
        assertSame(previous, TrackerState.model)
        assertTrue(TrackerState.finishCalibration(current, replacement) { saved = "new calibration" })
    }

    @Test fun storageFailureKeepsPreviousModelAndClearsCollection() {
        val session = TrackerState.beginCalibration()
        val result = runCatching {
            TrackerState.finishCalibration(session, replacement) { error("Storage unavailable") }
        }
        assertTrue(result.isFailure)
        assertSame(previous, TrackerState.model)
        assertEquals("previous saved model", saved)
        assertFalse(TrackerState.collecting)
    }

    @Test fun deliveryAndShutdownClearAllCoordinatesWithoutChangingSelectedModel() {
        TrackerState.localScrollMode = true
        val eye = EyeFeatures(0.0, .02, .5, .5, .3)
        val sample = GazePipeline().process(1_000, 1_000,
            EyeObservation(EyeState.OPEN, eye, .2, .2), previous, false)
        var delivered = 0
        TrackerState.listener = { delivered++ }
        TrackerState.publishSample(sample, "tracking")
        assertSame(sample, TrackerState.sample)
        assertSame(eye, TrackerState.features)
        assertEquals(1_000L, TrackerState.frameAt)
        assertEquals(sample.point, TrackerState.gaze!!.let { it.x to it.y })
        assertEquals(1, delivered)
        TrackerState.clearCoordinates()
        assertFalse(TrackerState.localScrollMode)
        assertNull(TrackerState.gaze)
        assertNull(TrackerState.sample)
        assertNull(TrackerState.features)
        assertEquals(0L, TrackerState.frameAt)
        assertEquals(EyeState.LOST, TrackerState.eyeState)
        assertSame(previous, TrackerState.model)
    }

    @Test fun homeAndSettingsBlockGeneralControlsAndCancelPendingActions() {
        TrackerState.interactionAllowed = true
        TrackerState.settingsStarted()
        try {
            assertFalse(TrackerState.interactionAllowed)
            assertEquals(1, cancelled)
            TrackerState.settingsStarted()
            TrackerState.settingsStopped()
            assertFalse(TrackerState.interactionAllowed)
        } finally { TrackerState.settingsStopped() }
        assertTrue(TrackerState.interactionAllowed)
    }

    @Test fun homeCannotStartCalibrationWithMissingStaleOrFutureEyeFeatures() {
        assertFalse(TrackerState.hasFreshFeatures(1_000))
        val eye = EyeFeatures(0.0, .02, .5, .5, .3)
        val sample = GazePipeline().process(1_000, 1_000,
            EyeObservation(EyeState.OPEN, eye, .2, .2), previous, false)
        TrackerState.publishSample(sample, "tracking")
        assertTrue(TrackerState.hasFreshFeatures(1_250))
        assertFalse(TrackerState.hasFreshFeatures(1_251))
        assertFalse(TrackerState.hasFreshFeatures(999))
        TrackerState.publish(null, "camera missing")
        assertFalse(TrackerState.hasFreshFeatures(1_100))
        assertNull(TrackerState.gaze)
    }

    @Test fun trackingShutdownCancelsCalibrationAndKeepsThePreviousModelAndSavedValues() {
        TrackerState.running = true
        TrackerState.controls = true
        val session = TrackerState.beginCalibration()
        TrackerState.trackingStopped()
        assertFalse(TrackerState.running)
        assertFalse(TrackerState.controls)
        assertFalse(TrackerState.collecting)
        assertSame(previous, TrackerState.model)
        assertEquals("previous saved model", saved)
        assertNull(TrackerState.gaze)
        assertFalse(TrackerState.finishCalibration(session, replacement) { fail("Stopped session saved a model") })
    }
}
