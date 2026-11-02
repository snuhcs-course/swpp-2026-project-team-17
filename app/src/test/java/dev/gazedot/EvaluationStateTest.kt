package dev.gazedot

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Fresh-calibration gating, snapshot reconstruction and pointer state of the candidate evaluation. */
class EvaluationStateTest {
    @Before fun setUp() { EvaluationState.end(); TrackerState.clearCalibration(); TrackerState.model = null }
    @After fun tearDown() { EvaluationState.end(); TrackerState.clearCalibration(); TrackerState.model = null; TrackerState.running = false }

    @Test fun blindModeHidesOnlyThePointer() {
        assertFalse(EvaluationState.active); assertFalse(EvaluationState.hidesPointer)
        EvaluationState.begin(EvaluationMode.BLIND_MEASUREMENT)
        assertTrue(EvaluationState.active); assertTrue(EvaluationState.hidesPointer)
        EvaluationState.end(); EvaluationState.end()
        assertFalse(EvaluationState.active); assertFalse(EvaluationState.hidesPointer)
        EvaluationState.begin(EvaluationMode.LIVE_PREVIEW)
        assertFalse(EvaluationState.hidesPointer)
    }

    @Test fun calibrationRecordFreezesItsInputsAndCountsCalibrations() {
        val anchors = SyntheticEvaluation.anchors().toMutableList()
        val model = GazeModel.fit(anchors)!!
        val before = TrackerState.calibrationSerial
        TrackerState.calibrationCompleted(SyntheticEvaluation.record(model, anchors))
        anchors.clear() // the calibration screen's list is not the record's
        assertEquals(9, TrackerState.calibration!!.anchors.size)
        assertSame(model, TrackerState.calibration!!.model)
        assertEquals(before + 1, TrackerState.calibrationSerial)
        TrackerState.clearCalibration()
        assertNull("a stopped service needs a new calibration", TrackerState.calibration)
        assertEquals(before + 1, TrackerState.calibrationSerial)
    }

    private val basis = DisplayBasis(SyntheticEvaluation.WIDTH, SyntheticEvaluation.HEIGHT, 0)
    private fun check(api: Int = 35, running: Boolean = true, features: Boolean = true, model: GazeModel? = null,
                      record: CalibrationRecord? = null, serial: Int = 2, evaluated: Int = 1, current: DisplayBasis? = basis) =
        EvaluationGate.check(api, running, features, model, record, serial, evaluated, current)

    @Test fun gateAcceptsOnlyTheUnusedFreshCalibrationOnItsOwnDisplayBasis() {
        val record = SyntheticEvaluation.record()
        val m = record.model
        assertNull(check(model = m, record = record))
        assertEquals(EvaluationBlock.UNSUPPORTED_API, check(api = 29, model = m, record = record))
        assertEquals(EvaluationBlock.NOT_RUNNING, check(running = false, model = m, record = record))
        assertEquals(EvaluationBlock.NO_EYES, check(features = false, model = m, record = record))
        assertEquals(EvaluationBlock.NOT_FRESHLY_CALIBRATED, check(model = m, record = null))
        assertEquals("a cleared model (new calibration started)", EvaluationBlock.NOT_FRESHLY_CALIBRATED, check(model = null, record = record))
        assertEquals("an equal but different model object", EvaluationBlock.NOT_FRESHLY_CALIBRATED, check(model = SyntheticEvaluation.model(), record = record))
        assertEquals(EvaluationBlock.CALIBRATION_ALREADY_USED, check(model = m, record = record, evaluated = 2))
        assertEquals(EvaluationBlock.DISPLAY_CHANGED, check(model = m, record = record, current = basis.copy(rotation = 1)))
        assertEquals(EvaluationBlock.DISPLAY_CHANGED, check(model = m, record = record, current = basis.copy(height = 2340)))
        assertEquals(EvaluationBlock.DISPLAY_CHANGED, check(model = m, record = record, current = null))
        // Inputs that no longer reproduce the live model are refused rather than measured.
        val tampered = SyntheticEvaluation.record(m, SyntheticEvaluation.anchors().map { it.copy(x = it.x * .9 + .05) })
        assertEquals(EvaluationBlock.SNAPSHOT_MISMATCH, check(model = m, record = tampered))
    }

    @Test fun oneRunPerCalibrationIncludingInterruptedRuns() {
        val record = SyntheticEvaluation.record()
        TrackerState.calibrationCompleted(record)
        val serial = TrackerState.calibrationSerial
        assertNull(check(model = record.model, record = record, serial = serial, evaluated = TrackerState.evaluatedCalibrationSerial))
        TrackerState.evaluatedCalibrationSerial = serial // set when a run starts, before it can be interrupted
        assertEquals(EvaluationBlock.CALIBRATION_ALREADY_USED,
            check(model = record.model, record = record, serial = serial, evaluated = TrackerState.evaluatedCalibrationSerial))
        val next = SyntheticEvaluation.record()
        TrackerState.calibrationCompleted(next)
        assertNull(check(model = next.model, record = next, serial = TrackerState.calibrationSerial, evaluated = TrackerState.evaluatedCalibrationSerial))
    }

    @Test fun snapshotReconstructsTheLiveModelExactly() {
        val record = SyntheticEvaluation.record()
        val snap = JSONObject(EvaluationModelSnapshot.json(record, 4))
        val anchors = EvaluationReplay.anchors(snap)
        // Exact round trip of every fit input, so the snapshot identifies the calibration.
        assertEquals(record.anchors, anchors)
        assertEquals(snap.getString("calibration_inputs_sha256"), EvaluationModelSnapshot.inputsSha256(anchors))
        val refit = EvaluationModelSnapshot.refit(anchors)!!
        assertEquals(record.model.parameters(), refit.parameters())
        assertEquals(emptyList<String>(), EvaluationReplay.parameterDifferences(refit, snap.getJSONObject("parameters")))
        assertEquals(EvaluationProtocol.PIPELINE_VARIANT, snap.getString("pipeline_variant"))
        assertEquals(4, snap.getInt("calibration_serial"))
        assertEquals(SyntheticEvaluation.WIDTH, snap.getJSONObject("calibration_basis").getInt("width_px"))
    }

    @Test fun snapshotOfNoisyRealisticInputsAlsoRoundTripsExactly() {
        val random = java.util.Random(5)
        repeat(50) {
            val anchors = SyntheticEvaluation.anchors().map { a ->
                Anchor(EyeFeatures(a.eye.x + random.nextGaussian() * 1e-3, a.eye.y + random.nextGaussian() * 1e-3,
                    .5 + random.nextGaussian() / 7, .5 + random.nextGaussian() / 11, .3 + random.nextGaussian() / 13), a.x, a.y)
            }
            val model = GazeModel.fit(anchors) ?: return@repeat
            val snap = JSONObject(EvaluationModelSnapshot.json(SyntheticEvaluation.record(model, anchors), 1))
            assertEquals(anchors, EvaluationReplay.anchors(snap))
            assertEquals(model.parameters(), EvaluationModelSnapshot.refit(EvaluationReplay.anchors(snap))!!.parameters())
        }
    }

    @Test fun parameterDifferencesAreReportedInUlpsNotHidden() {
        val record = SyntheticEvaluation.record()
        val snap = JSONObject(EvaluationModelSnapshot.json(record, 1))
        val params = snap.getJSONObject("parameters")
        params.put("std_x", Math.nextUp(params.getDouble("std_x")))
        val diffs = EvaluationReplay.parameterDifferences(record.model, params)
        assertEquals(1, diffs.size); assertTrue(diffs.single().startsWith("std_x:")); assertTrue(diffs.single().endsWith("(1 ulp)"))
    }

    @Test fun identifiers() {
        assertTrue(validEvaluationIdentifier("P01")); assertTrue(validEvaluationIdentifier("PILOT01"))
        assertFalse(validEvaluationIdentifier("../P01")); assertFalse(validEvaluationIdentifier("")); assertFalse(validEvaluationIdentifier("P 01"))
    }
}
