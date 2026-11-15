package dev.gazedot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EvaluationProtocolTest {
    @get:Rule val tmp = TemporaryFolder()
    private val manifestFile = File(SyntheticEvaluation.repoRoot(), "docs/calibration-comparison/protocol.json")
    private val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
    private fun strings(a: org.json.JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun kotlinProtocolEqualsManifest() {
        assertEquals(EvaluationProtocol.ID, manifest.getString("protocol_id"))
        assertEquals(EvaluationProtocol.VERSION, manifest.getString("protocol_version"))
        assertEquals("notouch-gaze-eval-iter3", manifest.getJSONObject("derived_from").getString("protocol_id"))
        assertEquals(EvaluationProtocol.PIPELINE_VARIANT, manifest.getJSONObject("pipeline").getString("variant"))
        assertTrue(manifest.getJSONObject("pipeline").getString("source_base").startsWith(EvaluationProtocol.SOURCE_BASE))
        assertTrue(manifest.getJSONObject("pipeline").getString("evaluation_reference").startsWith(EvaluationProtocol.EVALUATION_REFERENCE))
        // This build evaluates exactly its own candidate, which the shared manifest declares.
        assertEquals(CalibrationPlan.ID, EvaluationProtocol.CALIBRATION_ID)
        assertEquals("calibration_b_three_pass_positions", EvaluationProtocol.CALIBRATION_ID)
        assertTrue(EvaluationProtocol.CALIBRATION_ID in strings(manifest.getJSONObject("candidates").getJSONArray("allowed_calibration_ids")))
        assertTrue(manifest.getJSONObject("candidates").has(EvaluationProtocol.CALIBRATION_ID))
        val timing = manifest.getJSONObject("timing")
        assertEquals(EvaluationProtocol.TARGET_MS, timing.getLong("presentation_ms"))
        assertEquals(EvaluationProtocol.SETTLING_MS, timing.getLong("settling_ms"))
        assertEquals(EvaluationProtocol.MEASUREMENT_MS, timing.getLong("measurement_ms"))
        assertEquals(EvaluationProtocol.BLOCK_MS, timing.getLong("block_ms"))
        assertEquals(80_000L, EvaluationProtocol.BLOCK_MS)
        assertEquals(EvaluationProtocol.BLOCKS, timing.getInt("blocks"))
        assertEquals(EvaluationProtocol.COUNTDOWN_MS, timing.getLong("countdown_ms"))
        assertEquals(EvaluationProtocol.SESSION_START_LEAD_MS, manifest.getJSONObject("session_structure").getLong("session_start_lead_ms"))
        val grid = timing.getJSONArray("grid_norm")
        assertEquals(EvaluationProtocol.GRID, (0 until grid.length()).map { grid.getDouble(it) })
        val cases = manifest.getJSONArray("cases")
        assertEquals(32, cases.length())
        EvaluationProtocol.presentations.forEachIndexed { i, p ->
            val c = cases.getJSONObject(i)
            assertEquals(p.caseSuffix, c.getString("case_suffix"))
            assertEquals(p.trialIndex, c.getInt("trial_index"))
            assertEquals(p.pass, c.getInt("pass_number"))
            assertEquals(p.indexInPass, c.getInt("index_in_pass"))
            assertEquals(p.target.id, c.getString("target_id"))
            assertEquals(p.target.x, c.getDouble("target_x_norm"), 0.0)
            assertEquals(p.target.y, c.getDouble("target_y_norm"), 0.0)
            assertEquals(i * 2500L, c.getLong("onset_ms"))
            assertEquals(i * 2500L + 1000, c.getLong("measurement_start_ms"))
            assertEquals(i * 2500L + 2500, c.getLong("end_ms"))
        }
        val orders = manifest.getJSONObject("block_orders")
        EvaluationOrder.entries.forEach { assertEquals(it.positions.map { p -> p.id }, strings(orders.getJSONArray(it.id))) }
        assertEquals(EvaluationOrder.entries.map { it.id }.toSet(), orders.keys().asSequence().toSet())
        val positions = manifest.getJSONObject("head_positions")
        EvaluationPosition.entries.forEach { assertEquals(it.label, positions.getJSONObject(it.id).getString("label_ko")) }
    }

    private val planFile = File(SyntheticEvaluation.repoRoot(), EvaluationPlan.FILE)
    private val plan = JSONObject(planFile.readText(Charsets.UTF_8))
    /** participant/label -> (calibration_id, block_order) of a plan array. */
    private fun entries(a: org.json.JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
        .associate { "${it.getString("participant_id")}/${it.getString("session_label")}" to (it.getString("calibration_id") to it.getString("block_order")) }
    private fun PlannedEvaluationSession.key() = "$participantId/$sessionLabel"

    @Test fun protocolDeclaresCandidateBsOfficialSessionsWithTheDeclaredOrders() {
        val official = entries(manifest.getJSONObject("collection_plan").getJSONArray("official"))
            .filterValues { it.first == EvaluationProtocol.CALIBRATION_ID }
        assertEquals(mapOf("ANNA/B1" to (EvaluationProtocol.CALIBRATION_ID to "nhl"), "ANNA/B2" to (EvaluationProtocol.CALIBRATION_ID to "lhn")), official)
    }

    @Test fun collectionPlanBIsTheProtocolsBSessionsPlusASeparatePilot() {
        assertEquals(EvaluationProtocol.CALIBRATION_ID, plan.getString("calibration_id"))
        val protocol = plan.getJSONObject("protocol")
        assertEquals(EvaluationProtocol.ID, protocol.getString("protocol_id"))
        assertEquals(EvaluationProtocol.VERSION, protocol.getString("protocol_version"))
        // The plan names the exact manifest this build embeds.
        assertEquals(BuildConfig.EVAL_PROTOCOL_SHA256, protocol.getString("manifest_sha256"))
        val official = entries(plan.getJSONArray("official"))
        val protocolOfficial = entries(manifest.getJSONObject("collection_plan").getJSONArray("official"))
        assertEquals(protocolOfficial.filterValues { it.first == EvaluationProtocol.CALIBRATION_ID }, official)
        val pilot = entries(plan.getJSONArray("technical_pilot"))
        assertEquals(mapOf("PILOT01/TB1" to (EvaluationProtocol.CALIBRATION_ID to "nhl")), pilot)
        assertTrue(pilot.keys.all { it.uppercase().startsWith("PILOT") })
        assertTrue("pilot and official keys are disjoint", pilot.keys.none { it in protocolOfficial })
    }

    @Test fun setupOffersExactlyThePlannedSessions() {
        val official = entries(plan.getJSONArray("official"))
        val pilot = entries(plan.getJSONArray("technical_pilot"))
        val offered = EvaluationPlan.sessions
        assertEquals(official.keys + pilot.keys, offered.map { it.key() }.toSet())
        assertEquals(offered.size, offered.map { it.key() }.toSet().size)
        offered.forEach { s ->
            val (role, entry) = if (s.key() in official) EvaluationSessionRole.OFFICIAL to official.getValue(s.key())
                else EvaluationSessionRole.TECHNICAL_PILOT to pilot.getValue(s.key())
            assertEquals(s.key(), role, s.role)
            assertEquals(s.key(), entry.second, s.order.id)
            assertTrue(validEvaluationIdentifier(s.participantId) && validEvaluationIdentifier(s.sessionLabel))
            assertEquals("pilot role follows the protocol's PILOT* rule", s.role == EvaluationSessionRole.TECHNICAL_PILOT,
                s.participantId.uppercase().startsWith("PILOT"))
            assertTrue(s.label.startsWith("${s.participantId} · ${s.sessionLabel} · "))
        }
        assertEquals(listOf("ANNA/B1", "ANNA/B2", "PILOT01/TB1"), offered.map { it.key() })
        assertEquals(listOf(EvaluationOrder.NHL, EvaluationOrder.LHN, EvaluationOrder.NHL), offered.map { it.order })
        // Nothing of the sibling candidate's plan is offered by this build.
        assertTrue(offered.none { it.sessionLabel.startsWith("A") || it.sessionLabel == "T1" })
    }

    @Test fun buildRecordsHashOfThisManifestWithLineEndingsNormalized() {
        val normalized = manifestFile.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        assertEquals(EvaluationChecksums.sha256(normalized), BuildConfig.EVAL_PROTOCOL_SHA256)
    }

    @Test fun predeclaredThresholdsAreUnchanged() {
        val criteria = manifest.getJSONObject("performance_criteria")
        val success = criteria.getJSONObject("successful_presentation")
        assertEquals(0.10, success.getDouble("max_mean_error_norm"), 0.0)
        assertEquals(0.80, success.getDouble("min_valid_coverage"), 0.0)
        assertEquals(10, success.getInt("min_valid_samples"))
        assertTrue(success.getBoolean("requires_complete_presentation"))
        val block = criteria.getJSONArray("block_criteria")
        val expected = listOf(
            Triple("successful_presentation_rate", ">=", 0.80), Triple("p95_presentation_error", "<=", 0.20),
            Triple("mean_jitter_rms", "<=", 0.05), Triple("tracking_availability", ">=", 0.90),
            Triple("valid_sample_rate", ">=", 10.0), Triple("p95_processing_latency", "<=", 200.0),
        )
        assertEquals(expected, (0 until block.length()).map { i ->
            block.getJSONObject(i).let { Triple(it.getString("id"), it.getString("comparison"), it.getDouble("threshold")) }
        })
        val sufficiency = manifest.getJSONObject("data_sufficiency")
        assertEquals(10, sufficiency.getInt("min_valid_samples"))
        assertEquals(0.5, sufficiency.getDouble("min_valid_coverage"), 0.0)
        assertEquals(0.8, sufficiency.getDouble("min_eligible_fraction_for_session_spatial_criteria"), 0.0)
        assertEquals(EVALUATION_SCHEMA_VERSION, manifest.getJSONObject("log_schema").getInt("version"))
    }

    @Test fun serpentineFirstPassReversedSecondPassAndPositionQualifiedCaseIds() {
        assertEquals(listOf("r0c0", "r0c1", "r0c2", "r0c3", "r1c3", "r1c2", "r1c1", "r1c0",
            "r2c0", "r2c1", "r2c2", "r2c3", "r3c3", "r3c2", "r3c1", "r3c0"), EvaluationProtocol.firstPass.map { it.id })
        assertEquals(EvaluationProtocol.firstPass.reversed(), EvaluationProtocol.secondPass)
        val session = EvaluationOrder.NHL.positions.flatMap { pos -> EvaluationProtocol.presentations.map { it.caseId(pos) } }
        assertEquals(96, session.size); assertEquals(96, session.toSet().size)
        assertEquals("neutral-pass1-r0c0", session.first()); assertEquals("lower-pass2-r0c0", session.last())
        assertEquals(16, EvaluationProtocol.presentations.map { it.target }.toSet().size)
    }

    @Test fun timelineAssignsPreparationCountdownAndBlockPhasesByAcceptanceTime() {
        val s = EvaluationSchedule(EvaluationMode.BLIND_MEASUREMENT, EvaluationOrder.LHN, 10_000, SyntheticEvaluation.GEOMETRY)
        assertNull(s.locate(9_999)); assertFalse(s.covers(9_999)); assertTrue(s.covers(10_000))
        assertEquals(EvaluationPhase.PREPARATION, s.locate(10_000)!!.phase)
        assertEquals(EvaluationPosition.LOWER, s.locate(10_000)!!.position)
        assertNull("preparation has no block clock", s.locate(50_000)!!.blockElapsedMs)
        assertNull(s.blockStartMs(0)); assertNull(s.endMs); assertFalse(s.isFinished(10_000_000))
        assertFalse("Ready before the preparation started", s.markReady(1, 10_500))
        assertTrue(s.markReady(0, 12_000)); assertFalse("Ready only once", s.markReady(0, 12_100))
        assertEquals(EvaluationPhase.PREPARATION, s.locate(11_999)!!.phase)
        assertEquals(EvaluationPhase.COUNTDOWN, s.locate(12_000)!!.phase)
        assertEquals(-1L, s.locate(14_999)!!.blockElapsedMs)
        assertEquals(15_000L, s.blockStartMs(0))
        val first = s.locate(15_000)!!
        assertEquals(EvaluationPhase.SETTLING, first.phase); assertEquals("lower-pass1-r0c0", first.caseId)
        assertEquals(EvaluationPhase.SETTLING, s.locate(15_999)!!.phase)
        assertEquals(EvaluationPhase.MEASUREMENT, s.locate(16_000)!!.phase)
        assertEquals(EvaluationPhase.MEASUREMENT, s.locate(17_499)!!.phase)
        assertEquals(1, s.locate(17_500)!!.presentation!!.trialIndex)
        assertEquals(31, s.locate(94_999)!!.presentation!!.trialIndex)
        // Block 2's preparation starts at block 1's planned end, however long it lasts.
        val prep = s.locate(95_000)!!
        assertEquals(1 to EvaluationPhase.PREPARATION, prep.blockIndex to prep.phase)
        assertEquals(EvaluationPosition.HIGHER, prep.position)
        assertEquals(95_000L, s.preparationStartMs(1))
        assertTrue(s.markReady(1, 140_000))
        assertEquals("higher-pass1-r0c0", s.locate(143_000)!!.caseId)
        assertTrue(s.markReady(2, 223_000 + 500))
        assertEquals(EvaluationPosition.NEUTRAL, s.locate(226_500)!!.position)
        assertEquals(306_500L, s.endMs)
        assertEquals("neutral-pass2-r0c0", s.locate(306_499)!!.caseId)
        assertNull(s.locate(306_500)); assertFalse(s.covers(306_500)); assertTrue(s.isFinished(306_500))
        assertEquals(2, s.startedBlockAt(306_499)); assertEquals(0, s.startedBlockAt(94_999)); assertNull(s.startedBlockAt(14_999))
        assertEquals(32, s.presentationsCompleted(0, 1_000_000)); assertEquals(3, s.presentationsCompleted(1, 143_000 + 7_600))
        assertEquals(0, s.presentationsCompleted(2, 200_000))
    }

    @Test fun delayedResultsKeepTheWindowOfTheirAcceptanceTime() {
        val dir = File(tmp.root, "late")
        val s = SyntheticEvaluation.session(dir); s.open()
        val d = SyntheticEvaluation.Driver(s)
        d.evaluationReset(SyntheticEvaluation.START_MS - 100, EvaluationSession.RESET_SESSION_START)
        assertTrue(s.markReady(0, SyntheticEvaluation.START_MS + 1_000))
        val start = s.schedule.blockStartMs(0)!!
        d.tick(start)
        val (ex, ey) = SyntheticEvaluation.eye(.2, .2)
        val f = SyntheticEvaluation.open(EyeFeatures(ex, ey, .5, .5, .3))
        // Accepted 1 ms before the end of presentation 0's measurement window, handled in presentation 1.
        d.handle(d.accept(start + 2_499, f), start + 2_700)
        // Accepted in the last settling millisecond, handled in the measurement window.
        d.handle(d.accept(start + 3_499, f), start + 3_560)
        s.finish(EvaluationEndReason.BACK, start + 4_000)
        val rows = EvaluationReplay.readCsv(File(dir, "attempts.csv"))
        assertEquals(listOf("neutral-pass1-r0c0/measurement", "neutral-pass1-r0c1/settling"), rows.map { "${it["case_id"]}/${it["phase"]}" })
        assertEquals((start + 2_700).toString(), rows[0]["handled_uptime_ms"])
        assertTrue(EvaluationReplay.replay(dir).ok)
    }

    @Test fun targetsCalibrationAndPredictionShareOneBasisIncludingViewOffsets() {
        val g = EvaluationGeometry(1080, 2400, 0, 96, 1080, 2304)
        assertEquals(216.0 to 384.0, g.toView(.2, .2))
        for (t in EvaluationProtocol.targets) {
            val (vx, vy) = g.toView(t.x, t.y)
            assertEquals(t.x, (g.viewLeft + vx) / g.displayWidth, 1e-12)
            assertEquals(t.y, (g.viewTop + vy) / g.displayHeight, 1e-12)
        }
        assertTrue(g.containsAllTargets(44.0))
        assertFalse(EvaluationGeometry(1080, 2400, 0, 0, 1080, 1000).containsAllTargets(44.0))
        // A model calibrated on this basis predicts each evaluation target from the features of looking at it.
        val model = SyntheticEvaluation.model()
        for (t in EvaluationProtocol.targets) {
            val (ex, ey) = SyntheticEvaluation.eye(t.x, t.y)
            val p = model.predict(EyeFeatures(ex, ey, .5, .5, .3))!!
            assertEquals(t.x, p.first, .005); assertEquals(t.y, p.second, .005)
        }
    }
}
