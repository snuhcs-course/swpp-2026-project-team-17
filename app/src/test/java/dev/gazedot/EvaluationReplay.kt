package dev.gazedot

import org.json.JSONObject
import java.io.File

/**
 * Offline replay of the numerical gaze module for one recorded calibration-comparison session (log schema 4).
 *
 * Rebuilds the model with the app's own GazeModel.fit from the 27 frozen anchors in their recorded order, requires
 * every fitted parameter to equal the recorded one exactly, then feeds the logged observations through the app's own
 * GazePipeline.process (with the logged acceptance and handling times) in main-thread order (main_seq), applying every
 * logged pipeline reset. Each processed attempt must reproduce the logged quality, prediction rejection, linear,
 * predicted and smoothed coordinates exactly (string equality of Double.toString; no numerical tolerance). This checks
 * the numerical module only: camera, landmark detection and timing on the phone are measured by the physical session,
 * and replay timing is never a latency.
 */
object EvaluationReplay {
    const val TOOL_VERSION = "1.0.0"

    data class Mismatch(val attemptSeq: Long, val field: String, val logged: String, val replayed: String)
    data class Report(
        val sessionId: String, val participantId: String, val calibrationId: String,
        val parametersMatch: Boolean, val parameterDifferences: List<String>,
        val attemptRows: Int, val processedReplayed: Int, val notProcessed: Map<String, Int>, val resetsApplied: Int,
        val mismatches: List<Mismatch>, val errors: List<String>,
    ) {
        val ok get() = parametersMatch && errors.isEmpty() && mismatches.isEmpty()
    }

    /** Minimal RFC 4180 reader for the logger's output (fields never contain line breaks). */
    fun readCsv(file: File): List<Map<String, String>> {
        val lines = file.readText(Charsets.UTF_8).split("\n").filter { it.isNotEmpty() }
        val header = split(lines.first())
        return lines.drop(1).mapIndexed { i, line ->
            val cells = split(line)
            require(cells.size == header.size) { "${file.name} line ${i + 2}: ${cells.size} cells, expected ${header.size}" }
            header.zip(cells).toMap()
        }
    }
    private fun split(line: String): List<String> {
        val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    /** The labelled fit inputs exactly as frozen in model_snapshot.json, in their recorded order. */
    fun anchors(snapshot: JSONObject): List<CalibrationAnchor> {
        val a = snapshot.getJSONArray("anchors")
        return (0 until a.length()).map { i ->
            val row = a.getJSONObject(i); val e = row.getJSONObject("eye")
            val positionId = row.getString("calibration_position")
            val position = HeadPosition.entries.firstOrNull { it.id == positionId }
                ?: throw IllegalArgumentException("anchor $i: calibration position '$positionId' is not one of this build's candidate")
            CalibrationAnchor(row.getInt("pass"), row.getInt("point"), position,
                Anchor(EyeFeatures(e.getDouble("x"), e.getDouble("y"), e.getDouble("face_x"), e.getDouble("face_y"), e.getDouble("scale")),
                    row.getDouble("x"), row.getDouble("y")),
                row.getInt("movement_retries"), row.getLong("accepted_uptime_ms"))
        }
    }

    /** Parameter names whose refit value differs from the recorded one, with both values and their ULP distance. */
    fun parameterDifferences(model: GazeModel, recorded: JSONObject): List<String> {
        val out = mutableListOf<String>()
        fun compare(key: String, refit: Double, logged: Double) {
            if (refit.toRawBits() != logged.toRawBits()) out += "$key: recorded $logged, refit $refit (${Math.abs(refit.toRawBits() - logged.toRawBits())} ulp)"
        }
        EvaluationModelSnapshot.parametersMap(model.parameters()).forEach { (key, value) ->
            when (value) {
                is List<*> -> {
                    val a = recorded.getJSONArray(key)
                    if (a.length() != value.size) out += "$key: length ${a.length()} != ${value.size}"
                    else value.indices.forEach { compare("$key[$it]", value[it] as Double, a.getDouble(it)) }
                }
                else -> compare(key, value as Double, recorded.getDouble(key))
            }
        }
        return out
    }

    private fun observation(r: Map<String, String>): EyeObservation {
        val features = if (r.getValue("f_x").isEmpty()) null
            else EvaluationCsv.FEATURES.map { r.getValue(it).toDouble() }.let { EyeFeatures(it[0], it[1], it[2], it[3], it[4]) }
        return EyeObservation(EyeState.valueOf(r.getValue("eye_state")), features,
            r.getValue("left_open").toDouble(), r.getValue("right_open").toDouble())
    }

    fun replay(dir: File): Report {
        val errors = mutableListOf<String>()
        val metadata = JSONObject(File(dir, EvaluationSession.METADATA_FILE).readText(Charsets.UTF_8))
        val snapshot = JSONObject(File(dir, EvaluationSession.SNAPSHOT_FILE).readText(Charsets.UTF_8))
        if (metadata.optInt("schema_version") != EVALUATION_SCHEMA_VERSION)
            errors += "metadata schema_version ${metadata.opt("schema_version")} is not $EVALUATION_SCHEMA_VERSION; replay supports the calibration-comparison log only"
        if (metadata.optString("record_type") != EvaluationSession.RECORD_TYPE)
            errors += "metadata record_type is not ${EvaluationSession.RECORD_TYPE}"
        if (snapshot.optInt("snapshot_schema_version") != EvaluationModelSnapshot.SCHEMA_VERSION)
            errors += "model_snapshot schema ${snapshot.opt("snapshot_schema_version")} is not ${EvaluationModelSnapshot.SCHEMA_VERSION}"
        if (snapshot.optString("pipeline_variant") != EvaluationProtocol.PIPELINE_VARIANT ||
            metadata.optJSONObject("pipeline")?.optString("variant") != EvaluationProtocol.PIPELINE_VARIANT)
            errors += "pipeline variant is not ${EvaluationProtocol.PIPELINE_VARIANT}; never replayed with this pipeline"
        val calibrationId = snapshot.optString("calibration_id")
        // This build replays its own candidate only: its HeadPosition labels and CalibrationPlan define the snapshot.
        if (calibrationId != EvaluationProtocol.CALIBRATION_ID)
            errors += "calibration_id '$calibrationId' is not this build's candidate ${EvaluationProtocol.CALIBRATION_ID}"
        if (metadata.optJSONObject("calibration")?.optString("calibration_id") != calibrationId)
            errors += "metadata.calibration.calibration_id differs from the snapshot"
        val anchors = try { anchors(snapshot) } catch (e: Exception) { errors += "model_snapshot anchors unreadable: ${e.message}"; emptyList() }
        if (anchors.map { it.pass to it.point } != (1..EvaluationProtocol.CALIBRATION_PASSES).flatMap { p ->
                (1..EvaluationProtocol.CALIBRATION_POINTS_PER_PASS).map { p to it } })
            errors += "model_snapshot does not hold 27 anchors labelled pass 1..3 x point 1..9 in order"
        else if (!EvaluationModelSnapshot.wellFormed(anchors))
            errors += "model_snapshot calibration positions by pass are not ${CalibrationPlan.positions.map { it.id }}"
        val inputsSha = EvaluationModelSnapshot.inputsSha256(anchors)
        if (inputsSha != snapshot.optString("calibration_inputs_sha256"))
            errors += "model_snapshot: calibration inputs do not reproduce calibration_inputs_sha256"
        if (metadata.optJSONObject("calibration")?.optString("calibration_inputs_sha256") != inputsSha)
            errors += "metadata.calibration.calibration_inputs_sha256 differs from the snapshot inputs"
        val model = if (anchors.isEmpty()) null else EvaluationModelSnapshot.refit(anchors)
        val differences = if (model == null) listOf("GazeModel.fit rejected the frozen calibration inputs")
            else parameterDifferences(model, snapshot.getJSONObject("parameters"))

        val attempts = readCsv(File(dir, EvaluationSession.ATTEMPTS_FILE))
        val events = readCsv(File(dir, EvaluationSession.EVENTS_FILE))
        val ordered = (attempts.filter { it.getValue("main_seq").isNotEmpty() }.map { it.getValue("main_seq").toLong() to it } +
            events.map { it.getValue("main_seq").toLong() to it }).sortedBy { it.first }
        ordered.map { it.first }.let { seqs ->
            if (seqs.toSet().size != seqs.size) errors += "duplicate main_seq values"
            if (seqs.isNotEmpty() && seqs != (0L until seqs.size).toList()) errors += "main_seq is not contiguous from 0 (rows lost?)"
        }
        val first = events.minByOrNull { it.getValue("main_seq").toLong() }
        if (first == null || first.getValue("event") != EvaluationSession.EVENT_PIPELINE_RESET ||
            first.getValue("detail") != EvaluationSession.RESET_SESSION_START)
            errors += "first event is not the session_start pipeline reset; the pipeline state before the session is unknown"

        val pipeline = GazePipeline()
        val notProcessed = sortedMapOf<String, Int>()
        val mismatches = mutableListOf<Mismatch>()
        var processed = 0; var resets = 0; var started = false; var contextLost = false
        for ((_, row) in ordered) {
            if ("event" in row) {
                if (row.getValue("event") == EvaluationSession.EVENT_PIPELINE_RESET) { pipeline.reset(); resets++; started = true }
                continue
            }
            val outcome = row.getValue("outcome")
            val seq = row.getValue("attempt_seq").toLong()
            if (outcome == EvaluationOutcome.DISCARDED_MODEL_CHANGED.id) contextLost = true // the app processed it with another model
            if (outcome != EvaluationOutcome.PROCESSED.id) { notProcessed[outcome] = (notProcessed[outcome] ?: 0) + 1; continue }
            if (!started) { errors += "attempt $seq processed before the session_start reset"; continue }
            if (contextLost) { errors += "attempt $seq processed after a model change; pipeline context unknown"; continue }
            if (model == null) continue
            val sample = pipeline.process(row.getValue("accepted_uptime_ms").toLong(), row.getValue("handled_uptime_ms").toLong(),
                observation(row), model, false)
            processed++
            fun check(field: String, replayed: String) {
                if (row.getValue(field) != replayed) mismatches += Mismatch(seq, field, row.getValue(field), replayed)
            }
            check("quality", sample.quality.id)
            check("prediction_rejection", sample.features?.let { model.rejection(it) } ?: "")
            val linear = sample.features?.let { model.linearOutput(it) }
            check("linear_x_norm", linear?.first?.toString() ?: ""); check("linear_y_norm", linear?.second?.toString() ?: "")
            check("predicted_x_norm", sample.rawPoint?.first?.toString() ?: ""); check("predicted_y_norm", sample.rawPoint?.second?.toString() ?: "")
            check("smoothed_x_norm", sample.point?.first?.toString() ?: ""); check("smoothed_y_norm", sample.point?.second?.toString() ?: "")
        }
        attempts.filter { it.getValue("main_seq").isEmpty() }.forEach {
            val o = it.getValue("outcome"); notProcessed[o] = (notProcessed[o] ?: 0) + 1
        }
        return Report(metadata.optString("session_id"), metadata.optString("participant_id"), calibrationId,
            differences.isEmpty(), differences, attempts.size, processed, notProcessed, resets, mismatches, errors)
    }

    fun json(r: Report): String = EvaluationJson.write(linkedMapOf(
        "tool" to "EvaluationReplay", "tool_version" to TOOL_VERSION,
        "pipeline_variant" to EvaluationProtocol.PIPELINE_VARIANT,
        "scope" to "numerical gaze module only (GazeModel.fit/predict + GazePipeline.process); not camera, detection or latency",
        "tolerance" to "none: logged and replayed values are compared as exact Double.toString strings",
        "session_id" to r.sessionId, "participant_id" to r.participantId, "calibration_id" to r.calibrationId, "ok" to r.ok,
        "parameters_match" to r.parametersMatch, "parameter_differences" to r.parameterDifferences,
        "attempt_rows" to r.attemptRows, "processed_replayed" to r.processedReplayed,
        "not_processed_by_outcome" to r.notProcessed.toMap(), "resets_applied" to r.resetsApplied,
        "mismatch_count" to r.mismatches.size,
        "mismatches" to r.mismatches.take(50).map { mapOf("attempt_seq" to it.attemptSeq, "field" to it.field,
            "logged" to it.logged, "replayed" to it.replayed) },
        "errors" to r.errors,
    )) + "\n"

    /** Session directories (those holding attempts.csv) under [root], in sorted order. */
    fun discover(root: File): List<File> =
        if (File(root, EvaluationSession.ATTEMPTS_FILE).isFile) listOf(root)
        else root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.flatMap { discover(it) }
}
