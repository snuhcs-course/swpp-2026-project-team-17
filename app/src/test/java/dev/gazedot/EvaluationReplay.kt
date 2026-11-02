package dev.gazedot

import org.json.JSONObject
import java.io.File

/**
 * Offline replay of the candidate's numerical gaze module for one recorded evaluation session.
 *
 * Rebuilds the model with the app's own GazeModel.fit from the frozen calibration inputs, requires every fitted
 * parameter to equal the recorded one exactly, then feeds the logged features through GazeModel.predict and a fresh
 * GazeSmoother in main-thread order (main_seq), applying every logged smoothing reset. Each processed attempt must
 * reproduce the logged quality, prediction rejection, linear, predicted and smoothed coordinates exactly (string
 * equality of Double.toString; no numerical tolerance). This checks the numerical module only: camera, landmark
 * detection and timing on the phone are measured by the physical session, and replay timing is never a latency.
 */
object EvaluationReplay {
    const val TOOL_VERSION = "2.0.0"

    data class Mismatch(val attemptSeq: Long, val field: String, val logged: String, val replayed: String)
    data class Report(
        val sessionId: String, val participantId: String, val parametersMatch: Boolean, val parameterDifferences: List<String>,
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

    /** Fit inputs exactly as frozen in model_snapshot.json. */
    fun anchors(snapshot: JSONObject): List<Anchor> {
        val a = snapshot.getJSONArray("anchors")
        return (0 until a.length()).map { i ->
            val row = a.getJSONObject(i); val e = row.getJSONObject("eye")
            Anchor(EyeFeatures(e.getDouble("x"), e.getDouble("y"), e.getDouble("face_x"), e.getDouble("face_y"), e.getDouble("scale")),
                row.getDouble("x"), row.getDouble("y"))
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

    private fun features(r: Map<String, String>): EyeFeatures? =
        if (r.getValue("f_x").isEmpty()) null
        else EvaluationCsv.FEATURES.map { r.getValue(it).toDouble() }.let { EyeFeatures(it[0], it[1], it[2], it[3], it[4]) }

    fun replay(dir: File): Report {
        val errors = mutableListOf<String>()
        val metadata = JSONObject(File(dir, EvaluationSession.METADATA_FILE).readText(Charsets.UTF_8))
        val snapshot = JSONObject(File(dir, EvaluationSession.SNAPSHOT_FILE).readText(Charsets.UTF_8))
        if (metadata.optInt("schema_version") != EVALUATION_SCHEMA_VERSION)
            errors += "metadata schema_version ${metadata.opt("schema_version")} is not $EVALUATION_SCHEMA_VERSION; replay supports the candidate log only"
        if (snapshot.optString("pipeline_variant") != EvaluationProtocol.PIPELINE_VARIANT ||
            metadata.optJSONObject("pipeline")?.optString("variant") != EvaluationProtocol.PIPELINE_VARIANT)
            errors += "pipeline variant is not ${EvaluationProtocol.PIPELINE_VARIANT}; never replayed with this candidate's model"
        val anchors = anchors(snapshot)
        val inputsSha = EvaluationModelSnapshot.inputsSha256(anchors)
        if (inputsSha != snapshot.getString("calibration_inputs_sha256"))
            errors += "model_snapshot: calibration inputs do not reproduce calibration_inputs_sha256"
        if (metadata.optJSONObject("predictor")?.optString("calibration_inputs_sha256") != inputsSha)
            errors += "metadata.predictor.calibration_inputs_sha256 differs from the snapshot inputs"
        val model = EvaluationModelSnapshot.refit(anchors)
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
        if (first == null || first.getValue("event") != EvaluationSession.EVENT_SMOOTHING_RESET || first.getValue("detail") != "run_start")
            errors += "first event is not the run_start smoothing reset; smoothing context before the run is unknown"

        val smoother = GazeSmoother()
        val notProcessed = sortedMapOf<String, Int>()
        val mismatches = mutableListOf<Mismatch>()
        var processed = 0; var resets = 0; var started = false; var contextLost = false
        for ((_, row) in ordered) {
            if ("event" in row) {
                if (row.getValue("event") == EvaluationSession.EVENT_SMOOTHING_RESET) { smoother.reset(); resets++; started = true }
                continue
            }
            val outcome = row.getValue("outcome")
            val seq = row.getValue("attempt_seq").toLong()
            if (outcome == EvaluationOutcome.DISCARDED_MODEL_CHANGED.id) contextLost = true // the app smoothed it with another model
            if (outcome != EvaluationOutcome.PROCESSED.id) { notProcessed[outcome] = (notProcessed[outcome] ?: 0) + 1; continue }
            if (!started) { errors += "attempt $seq processed before the run_start reset"; continue }
            if (contextLost) { errors += "attempt $seq processed after a model change; smoothing context unknown"; continue }
            if (model == null) continue
            val f = features(row)
            val position = if (f != null) model.predict(f) else null
            val smoothed = smoother.update(position)
            processed++
            fun check(field: String, replayed: String) {
                if (row.getValue(field) != replayed) mismatches += Mismatch(seq, field, row.getValue(field), replayed)
            }
            val quality = when { f == null -> EvaluationQuality.NO_FEATURES; position == null -> EvaluationQuality.PREDICTION_REJECTED
                else -> EvaluationQuality.TRACKING }
            check("quality", quality.id)
            check("prediction_rejection", f?.let { model.rejection(it) } ?: "")
            val linear = f?.let { model.linearOutput(it) }
            check("linear_x_norm", linear?.first?.toString() ?: ""); check("linear_y_norm", linear?.second?.toString() ?: "")
            check("predicted_x_norm", position?.first?.toString() ?: ""); check("predicted_y_norm", position?.second?.toString() ?: "")
            check("smoothed_x_norm", smoothed?.first?.toString() ?: ""); check("smoothed_y_norm", smoothed?.second?.toString() ?: "")
        }
        attempts.filter { it.getValue("main_seq").isEmpty() }.forEach {
            val o = it.getValue("outcome"); notProcessed[o] = (notProcessed[o] ?: 0) + 1
        }
        return Report(metadata.getString("session_id"), metadata.getString("participant_id"), differences.isEmpty(), differences,
            attempts.size, processed, notProcessed, resets, mismatches, errors)
    }

    fun json(r: Report): String = EvaluationJson.write(linkedMapOf(
        "tool" to "EvaluationReplay", "tool_version" to TOOL_VERSION,
        "pipeline_variant" to EvaluationProtocol.PIPELINE_VARIANT,
        "scope" to "numerical gaze module only (GazeModel.fit/predict + GazeSmoother); not camera, detection or latency",
        "tolerance" to "none: logged and replayed values are compared as exact Double.toString strings",
        "session_id" to r.sessionId, "participant_id" to r.participantId, "ok" to r.ok,
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
