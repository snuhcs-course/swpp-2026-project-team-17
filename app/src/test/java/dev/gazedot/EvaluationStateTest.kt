package dev.gazedot

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Fresh-calibration gating, candidate identity, snapshot reconstruction, and the evaluation's pointer and action guards. */
class EvaluationStateTest {
    private fun resetState() {
        EvaluationState.end(); TrackerState.cancelCalibration(); TrackerState.clearCalibrationRecord(); TrackerState.model = null
        TrackerState.running = false; TrackerState.controls = false; TrackerState.interactionAllowed = false
        TrackerState.collecting = false; TrackerState.speaking = false
    }
    @Before fun setUp() = resetState()
    @After fun tearDown() = resetState()

    private val basis = DisplayBasis(SyntheticEvaluation.WIDTH, SyntheticEvaluation.HEIGHT, 0)
    private fun check(api: Int = 35, running: Boolean = true, features: Boolean = true, model: GazeModel? = null,
                      record: CalibrationRecord? = null, serial: Int = 2, evaluated: Int = 1, current: DisplayBasis? = basis) =
        EvaluationGate.check(api, running, features, model, record, serial, evaluated, current)

    @Test fun gateAcceptsOnlyAnUnusedFreshCalibrationOfThisCandidateOnItsOwnBasis() {
        val record = SyntheticEvaluation.record()
        val m = record.model
        assertNull(check(model = m, record = record))
        assertEquals(EvaluationRefusal.UNSUPPORTED_API, check(api = 29, model = m, record = record))
        assertEquals(EvaluationRefusal.NOT_RUNNING, check(running = false, model = m, record = record))
        assertEquals(EvaluationRefusal.NO_EYES, check(features = false, model = m, record = record))
        assertEquals(EvaluationRefusal.NOT_FRESHLY_CALIBRATED, check(model = m, record = null))
        assertEquals(EvaluationRefusal.NOT_FRESHLY_CALIBRATED, check(model = null, record = record))
        assertEquals("an equal but different model object", EvaluationRefusal.NOT_FRESHLY_CALIBRATED,
            check(model = SyntheticEvaluation.model(), record = record))
        assertEquals(EvaluationRefusal.CALIBRATION_ALREADY_USED, check(model = m, record = record, evaluated = 2))
        assertEquals(EvaluationRefusal.DISPLAY_CHANGED, check(model = m, record = record, current = basis.copy(rotation = 1)))
        assertEquals(EvaluationRefusal.DISPLAY_CHANGED, check(model = m, record = record, current = null))
        val tampered = SyntheticEvaluation.record(m, SyntheticEvaluation.anchors().map { it.copy(anchor = it.anchor.copy(x = it.anchor.x * .9 + .05)) })
        assertEquals(EvaluationRefusal.SNAPSHOT_MISMATCH, check(model = m, record = tampered))
    }

    @Test fun candidateIdentityAndTheTwentySevenAnchorFormAreRequired() {
        val record = SyntheticEvaluation.record()
        val m = record.model
        val other = SyntheticEvaluation.record(m, id = "calibration_b_three_pass_positions")
        assertEquals("a B calibration in an A build", EvaluationRefusal.WRONG_CANDIDATE, check(model = m, record = other))
        assertEquals(EvaluationRefusal.WRONG_CANDIDATE, check(model = m, record = SyntheticEvaluation.record(m, id = "calibration_nine_point")))
        val nine = SyntheticEvaluation.anchors().take(9)
        val nineModel = SyntheticEvaluation.model(nine)
        assertEquals("a nine-point calibration", EvaluationRefusal.INVALID_CALIBRATION,
            check(model = nineModel, record = SyntheticEvaluation.record(nineModel, nine)))
        val shuffled = SyntheticEvaluation.anchors().let { it.drop(9) + it.take(9) }
        val shuffledModel = SyntheticEvaluation.model(shuffled)
        assertEquals("passes out of order", EvaluationRefusal.INVALID_CALIBRATION,
            check(model = shuffledModel, record = SyntheticEvaluation.record(shuffledModel, shuffled)))
    }

    @Test fun oneSessionPerCalibrationIncludingInterruptedSessions() {
        val record = SyntheticEvaluation.record()
        TrackerState.calibrationCompleted(record)
        val serial = TrackerState.calibrationSerial
        assertNull(check(model = record.model, record = record, serial = serial, evaluated = TrackerState.evaluatedCalibrationSerial))
        TrackerState.evaluatedCalibrationSerial = serial // set when a session starts, before it can be interrupted
        assertEquals(EvaluationRefusal.CALIBRATION_ALREADY_USED,
            check(model = record.model, record = record, serial = serial, evaluated = TrackerState.evaluatedCalibrationSerial))
        val next = SyntheticEvaluation.record()
        TrackerState.calibrationCompleted(next)
        assertNull(check(model = next.model, record = next, serial = TrackerState.calibrationSerial, evaluated = TrackerState.evaluatedCalibrationSerial))
    }

    @Test fun aFailedRecalibrationCannotPassOffTheModelItKeeps() {
        val record = SyntheticEvaluation.record()
        TrackerState.model = record.model
        TrackerState.calibrationCompleted(record)
        assertSame(record, TrackerState.calibrationRecord)
        val attempt = TrackerState.beginCalibration()
        assertNull("starting a calibration voids the previous record", TrackerState.calibrationRecord)
        assertFalse(TrackerState.finishCalibration(attempt, null)) // the attempt failed
        assertSame("the app keeps using the previous model", record.model, TrackerState.model)
        assertEquals(EvaluationRefusal.NOT_FRESHLY_CALIBRATED, check(model = TrackerState.model, record = TrackerState.calibrationRecord,
            serial = TrackerState.calibrationSerial, evaluated = 0))
        // A cancelled attempt behaves the same way, and so does stopping tracking.
        TrackerState.calibrationCompleted(record)
        TrackerState.beginCalibration(); TrackerState.cancelCalibration()
        assertNull(TrackerState.calibrationRecord)
        TrackerState.calibrationCompleted(record)
        TrackerState.trackingStopped()
        assertNull("a stopped service needs a new calibration", TrackerState.calibrationRecord)
    }

    @Test fun calibrationRecordFreezesItsLabelledInputs() {
        val anchors = SyntheticEvaluation.anchors().toMutableList()
        val record = SyntheticEvaluation.record(SyntheticEvaluation.model(anchors), anchors)
        anchors.clear() // the calibration controller's list is not the record's
        assertEquals(27, record.anchors.size)
        assertEquals(1, record.movementRetries)
        assertEquals(75_000L, record.durationMs)
        assertEquals(record.anchors.map { it.anchor }, record.fitAnchors)
        assertTrue(EvaluationModelSnapshot.wellFormed(record))
    }

    @Test fun snapshotReconstructsTheLiveModelAndLabelsExactly() {
        val record = SyntheticEvaluation.record()
        val snap = JSONObject(EvaluationModelSnapshot.json(record, 4))
        val anchors = EvaluationReplay.anchors(snap)
        assertEquals(record.anchors, anchors) // labels, retries, acceptance times and fit inputs, in order
        assertEquals(snap.getString("calibration_inputs_sha256"), EvaluationModelSnapshot.inputsSha256(anchors))
        val refit = EvaluationModelSnapshot.refit(anchors)!!
        assertEquals(record.model.parameters(), refit.parameters())
        assertEquals(emptyList<String>(), EvaluationReplay.parameterDifferences(refit, snap.getJSONObject("parameters")))
        assertEquals(2, snap.getInt("snapshot_schema_version"))
        assertEquals(CalibrationPlan.ID, snap.getString("calibration_id"))
        assertEquals(27, snap.getJSONArray("anchors").length())
        assertEquals(listOf(1, 2, 3), (0 until 27).map { snap.getJSONArray("anchors").getJSONObject(it).getInt("pass") }.distinct())
        assertEquals("same", snap.getJSONArray("anchors").getJSONObject(26).getString("calibration_position"))
        assertEquals(75_000L, snap.getJSONObject("calibration_timing").getLong("duration_ms"))
        // The order is part of the identity: the same anchors in another order are another calibration.
        assertNotEquals(EvaluationModelSnapshot.inputsSha256(record.anchors), EvaluationModelSnapshot.inputsSha256(record.anchors.reversed()))
    }

    @Test fun snapshotOfNoisyRealisticInputsAlsoRoundTripsExactly() {
        val random = java.util.Random(5)
        repeat(50) {
            val anchors = SyntheticEvaluation.anchors().map { a ->
                a.copy(anchor = Anchor(EyeFeatures(a.anchor.eye.x + random.nextGaussian() * 1e-3, a.anchor.eye.y + random.nextGaussian() * 1e-3,
                    .5 + random.nextGaussian() / 7, .5 + random.nextGaussian() / 11, .3 + random.nextGaussian() / 13), a.anchor.x, a.anchor.y))
            }
            val model = GazeModel.fit(anchors.map { it.anchor }) ?: return@repeat
            val snap = JSONObject(EvaluationModelSnapshot.json(SyntheticEvaluation.record(model, anchors), 1))
            assertEquals(anchors, EvaluationReplay.anchors(snap))
            assertEquals(model.parameters(), EvaluationModelSnapshot.refit(EvaluationReplay.anchors(snap))!!.parameters())
        }
    }

    @Test fun parameterDifferencesAreReportedInUlpsNotHidden() {
        val record = SyntheticEvaluation.record()
        val params = JSONObject(EvaluationModelSnapshot.json(record, 1)).getJSONObject("parameters")
        params.put("std_x", Math.nextUp(params.getDouble("std_x")))
        val diffs = EvaluationReplay.parameterDifferences(record.model, params)
        assertEquals(1, diffs.size); assertTrue(diffs.single().startsWith("std_x:")); assertTrue(diffs.single().endsWith("(1 ulp)"))
    }

    @Test fun blindModeHidesThePointerAndEverySessionSuppressesGazeActions() {
        TrackerState.running = true; TrackerState.controls = true; TrackerState.interactionAllowed = true
        assertTrue("normal use", TrackerState.gazeActionsEnabled(accessibilityConnected = true))
        assertTrue(TrackerState.gazeControlAllowed())
        assertFalse(TrackerState.gazeActionsEnabled(accessibilityConnected = false))
        EvaluationState.begin(EvaluationMode.BLIND_MEASUREMENT)
        assertTrue(EvaluationState.active); assertTrue(EvaluationState.hidesPointer); assertTrue(EvaluationState.suppressesActions)
        assertFalse(TrackerState.gazeActionsEnabled(accessibilityConnected = true))
        assertFalse(TrackerState.gazeControlAllowed())
        EvaluationState.end()
        EvaluationState.begin(EvaluationMode.LIVE_PREVIEW)
        assertFalse(EvaluationState.hidesPointer); assertTrue("live preview also suppresses actions", EvaluationState.suppressesActions)
        assertFalse(TrackerState.gazeControlAllowed())
        EvaluationState.end(); EvaluationState.end()
        assertFalse(EvaluationState.active); assertFalse(EvaluationState.hidesPointer)
        assertTrue("restored after the session", TrackerState.gazeActionsEnabled(accessibilityConnected = true))
        assertTrue(TrackerState.gazeControlAllowed())
        // The original conditions are unchanged.
        TrackerState.collecting = true; assertFalse(TrackerState.gazeActionsEnabled(true)); assertFalse(TrackerState.gazeControlAllowed())
        TrackerState.collecting = false; TrackerState.speaking = true; assertFalse(TrackerState.gazeActionsEnabled(true))
        assertTrue("dictation in progress does not block the accessibility service", TrackerState.gazeControlAllowed())
    }

    @Test fun identifiers() {
        assertTrue(validEvaluationIdentifier("ANNA")); assertTrue(validEvaluationIdentifier("PILOT01")); assertTrue(validEvaluationIdentifier("A1"))
        assertFalse(validEvaluationIdentifier("../P01")); assertFalse(validEvaluationIdentifier("")); assertFalse(validEvaluationIdentifier("P 01"))
    }
}
