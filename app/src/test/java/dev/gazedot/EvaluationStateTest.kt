package dev.gazedot

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Evaluation must suppress every gaze action, leave models untouched and clean up after itself. */
class EvaluationStateTest {
    private val resets = mutableListOf<String>()
    private var cancelled = 0

    @Before fun setUp() {
        EvaluationState.end()
        TrackerState.cancelCalibration()
        TrackerState.trackingStopped()
        TrackerState.cancelActions = { cancelled++ }
        TrackerState.resetPipeline = { resets += it }
        TrackerState.running = true; TrackerState.controls = true; TrackerState.interactionAllowed = true
        TrackerState.speaking = false
        resets.clear(); cancelled = 0
    }

    @After fun tearDown() {
        EvaluationState.end()
        TrackerState.cancelActions = null; TrackerState.resetPipeline = null
        TrackerState.trackingStopped()
        TrackerState.model = null; TrackerState.interactionAllowed = false
    }

    @Test fun evaluationDisablesActionDispatchAndRestoresIt() {
        assertTrue(TrackerState.actionsEnabled(accessibilityConnected = true))
        EvaluationState.begin(EvaluationMode.BLIND_MEASUREMENT)
        assertFalse(TrackerState.actionsEnabled(accessibilityConnected = true))
        assertTrue(EvaluationState.hidesPointer)
        EvaluationState.end()
        assertTrue(TrackerState.actionsEnabled(accessibilityConnected = true))
        assertFalse(EvaluationState.hidesPointer)
        EvaluationState.begin(EvaluationMode.LIVE_PREVIEW)
        assertFalse(EvaluationState.hidesPointer); assertFalse(TrackerState.actionsEnabled(true))
    }

    @Test fun entryCancelsPendingActionsAndDisablesCloudWithoutTouchingTheModel() {
        val model = SyntheticEvaluation.model()
        TrackerState.model = model
        CloudSession.configure("https://example.invalid", "a".repeat(43), pose = true, context = true, auto = true)
        assertTrue(CloudSession.enabled)
        TrackerState.localDwellProgress = .6f; TrackerState.localScrollMode = true
        EvaluationState.begin(EvaluationMode.BLIND_MEASUREMENT)
        assertFalse(CloudSession.enabled); assertEquals("", CloudSession.serverToken)
        assertEquals(1, cancelled)
        assertEquals(0f, TrackerState.localDwellProgress, 0f); assertFalse(TrackerState.localScrollMode)
        assertSame(model, TrackerState.model)
        assertTrue("entry must not reset the filter before the run clock starts", resets.isEmpty())
    }

    @Test fun exitCancelsInteractionOnceWithoutDiscardingInFlightResults() {
        EvaluationState.begin(EvaluationMode.BLIND_MEASUREMENT)
        TrackerState.localDwellProgress = .3f
        EvaluationState.end(); EvaluationState.end()
        assertTrue("a filter reset would discard the last accepted frames of a completed run", resets.isEmpty())
        assertEquals(2, cancelled)
        assertEquals(0f, TrackerState.localDwellProgress, 0f)
        assertFalse(EvaluationState.active)
    }

    @Test fun disabledActionsLeaveTheMeasuredPointUnchangedAndEmitNoGestures() {
        val features = EyeFeatures(0.0, .02, .5, .5, .3)
        val predictor = object : GazePredictor { override fun predict(f: EyeFeatures) = .41 + f.x to .6 }
        val enabled = GazePipeline(); val disabled = GazePipeline()
        var t = 1_000L
        val states = List(40) { EyeState.OPEN } + EyeState.LEFT_WINK + List(5) { EyeState.OPEN } + EyeState.CLOSED + List(30) { EyeState.OPEN }
        for (state in states) {
            val obs = EyeObservation(state, features.copy(x = (t % 7) * .001).takeIf { state == EyeState.OPEN }, .2, .2)
            val a = enabled.process(t, t, obs, predictor, true)
            val b = disabled.process(t, t, obs, predictor, false)
            assertEquals(a.point, b.point); assertEquals(a.rawPoint, b.rawPoint); assertEquals(a.quality, b.quality)
            assertTrue(b.actions.all { it == EyeAction.Cancel })
            assertEquals(0f, b.dwellProgress, 0f)
            t += 50
        }
    }

    @Test fun calibrationTrackingSupportsTheFreshCalibrationRule() {
        val model = SyntheticEvaluation.model()
        var saved = 0
        val session = TrackerState.beginCalibration()
        assertTrue(TrackerState.finishCalibration(session, model) { saved++ })
        assertSame(model, TrackerState.calibratedModel); assertEquals(1, saved)
        val serial = TrackerState.calibrationSerial
        TrackerState.trackingStopped()
        assertNull("a restarted camera needs a new calibration", TrackerState.calibratedModel)
        assertSame("stopping tracking keeps the selected model", model, TrackerState.model)
        assertEquals(serial, TrackerState.calibrationSerial)
    }

    @Test fun gateAcceptsOnlyTheUnusedFreshPersonalCalibration() {
        val saved = SyntheticEvaluation.savedCalibration()
        val model = SyntheticEvaluation.model()
        val refit = EvaluationModelSnapshot.refit(saved)
        val config = SyntheticEvaluation.CONFIG
        fun check(running: Boolean = true, fresh: Boolean = true, active: GazePredictor? = model, calibrated: GazePredictor? = model,
                  serial: Int = 2, evaluated: Int = 1, savedRefit: GazeModel? = refit, savedConfig: String? = config) =
            EvaluationGate.check(running, fresh, active, calibrated, serial, evaluated, savedRefit, savedConfig, config)
        assertNull(check())
        assertEquals(EvaluationBlock.NOT_RUNNING, check(running = false))
        assertEquals(EvaluationBlock.NO_EYES, check(fresh = false))
        assertEquals(EvaluationBlock.NOT_FRESHLY_CALIBRATED, check(calibrated = null))
        assertEquals("no silent fallback to the basic pointer", EvaluationBlock.NOT_FRESHLY_CALIBRATED, check(active = BasicGazePredictor))
        assertEquals("no switch to another saved model", EvaluationBlock.NOT_FRESHLY_CALIBRATED, check(active = SyntheticEvaluation.model()))
        assertEquals(EvaluationBlock.CALIBRATION_ALREADY_USED, check(evaluated = 2))
        val other = object : GazePredictor { override fun predict(f: EyeFeatures) = .5 to .5 }
        assertEquals(EvaluationBlock.NOT_PERSONAL_CALIBRATION, check(active = other, calibrated = other))
        assertEquals(EvaluationBlock.SAVED_CALIBRATION_MISMATCH, check(savedRefit = null))
        val shifted = ModelStore.calibrationJson(config, SyntheticEvaluation.anchors().map { it.copy(x = it.x * .9 + .05) })
        assertEquals(EvaluationBlock.SAVED_CALIBRATION_MISMATCH, check(savedRefit = EvaluationModelSnapshot.refit(shifted)))
        assertEquals(EvaluationBlock.CONFIG_MISMATCH, check(savedConfig = "other|device|1080x2400|1|features-v2"))
    }

    @Test fun savedCalibrationRefitReproducesTheLiveModelExactly() {
        val anchors = SyntheticEvaluation.anchors()
        val live = GazeModel.fit(anchors)!!
        val refit = EvaluationModelSnapshot.refit(ModelStore.calibrationJson("cfg", anchors))!!
        assertEquals(live.parameters(), refit.parameters())
        assertEquals("cfg", EvaluationModelSnapshot.config(ModelStore.calibrationJson("cfg", anchors)))
        assertNull(EvaluationModelSnapshot.refit("not json")); assertNull(EvaluationModelSnapshot.refit(null))
    }
}
