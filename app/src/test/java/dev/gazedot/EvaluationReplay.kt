package dev.gazedot

import org.json.JSONObject
import java.io.File

/**
 * Offline replay of the numerical gaze module for one recorded evaluation session.
 *
 * Rebuilds the predictor with the app's own GazeModel.fit from the frozen saved calibration, then feeds
 * the logged observations through a fresh GazePipeline in main-thread order (main_seq), applying every
 * logged filter reset. Each processed attempt must reproduce the logged quality, raw and final coordinates
 * exactly. This checks the numerical module only: camera, landmark detection and timing on the phone are
 * measured by the physical session, and replay timing is never a latency measurement.
 */
object EvaluationReplay {
    const val TOOL_VERSION = "1.0.0"

    data class Mismatch(val attemptSeq: Long, val field: String, val logged: String, val replayed: String)
    data class Report(
        val sessionId: String, val participantId: String, val parametersMatch: Boolean,
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

    private fun observation(r: Map<String, String>): EyeObservation {
        val state = EyeState.valueOf(r.getValue("eye_state").uppercase())
        val features = if (r.getValue("f_x").isEmpty()) null else {
            val v = EvaluationCsv.FEATURES.map { r.getValue(it).toDouble() }
            EyeFeatures(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14])
        }
        return EyeObservation(state, features, r.getValue("left_open").toDouble(), r.getValue("right_open").toDouble())
    }

    fun replay(dir: File): Report {
        val errors = mutableListOf<String>()
        val metadata = JSONObject(File(dir, EvaluationSession.METADATA_FILE).readText(Charsets.UTF_8))
        val snapshot = JSONObject(File(dir, EvaluationSession.SNAPSHOT_FILE).readText(Charsets.UTF_8))
        val saved = snapshot.getString("saved_calibration_json")
        val savedSha = EvaluationChecksums.sha256(saved.toByteArray(Charsets.UTF_8))
        if (savedSha != snapshot.getString("saved_calibration_sha256")) errors += "model_snapshot: saved_calibration_sha256 mismatch"
        metadata.optJSONObject("predictor")?.optString("saved_calibration_sha256")?.let {
            if (it != savedSha) errors += "metadata.predictor.saved_calibration_sha256 differs from the snapshot"
        }
        val model = EvaluationModelSnapshot.refit(saved)
        val recorded = snapshot.getJSONObject("parameters")
        val parametersMatch = model != null && EvaluationModelSnapshot.parametersMap(model.parameters()).all { (key, value) ->
            when (value) {
                is List<*> -> recorded.getJSONArray(key).let { a -> a.length() == value.size && value.indices.all { a.getDouble(it) == value[it] } }
                else -> recorded.getDouble(key) == value
            }
        }
        if (model == null) errors += "GazeModel.fit rejected the frozen calibration"

        val attempts = readCsv(File(dir, EvaluationSession.ATTEMPTS_FILE))
        val events = readCsv(File(dir, EvaluationSession.EVENTS_FILE))
        val ordered = (attempts.filter { it.getValue("main_seq").isNotEmpty() }.map { it.getValue("main_seq").toLong() to it } +
            events.map { it.getValue("main_seq").toLong() to it }).sortedBy { it.first }
        ordered.map { it.first }.let { seqs ->
            if (seqs.toSet().size != seqs.size) errors += "duplicate main_seq values"
            if (seqs.isNotEmpty() && seqs != (0L until seqs.size).toList()) errors += "main_seq is not contiguous from 0 (rows lost?)"
        }
        val first = events.minByOrNull { it.getValue("main_seq").toLong() }
        if (first == null || first.getValue("event") != EvaluationSession.EVENT_PIPELINE_RESET || first.getValue("detail") != "run_start")
            errors += "first event is not the run_start pipeline reset; filter context before the run is unknown"

        val pipeline = GazePipeline()
        val notProcessed = sortedMapOf<String, Int>()
        val mismatches = mutableListOf<Mismatch>()
        var processed = 0; var resets = 0; var started = false
        for ((_, row) in ordered) {
            if ("event" in row) {
                if (row.getValue("event") == EvaluationSession.EVENT_PIPELINE_RESET) {
                    pipeline.reset(); resets++; started = true
                }
                continue
            }
            val outcome = row.getValue("outcome")
            if (outcome != EvaluationOutcome.PROCESSED.id) { notProcessed[outcome] = (notProcessed[outcome] ?: 0) + 1; continue }
            val seq = row.getValue("attempt_seq").toLong()
            if (!started) { errors += "attempt $seq processed before the run_start reset"; continue }
            if (model == null) continue
            val s = pipeline.process(row.getValue("accepted_uptime_ms").toLong(), row.getValue("result_uptime_ms").toLong(),
                observation(row), model, false)
            processed++
            fun check(field: String, replayed: String) {
                if (row.getValue(field) != replayed) mismatches += Mismatch(seq, field, row.getValue(field), replayed)
            }
            check("quality", s.quality.name.lowercase())
            check("raw_x_norm", s.rawPoint?.first?.toString() ?: ""); check("raw_y_norm", s.rawPoint?.second?.toString() ?: "")
            check("point_x_norm", s.point?.first?.toString() ?: ""); check("point_y_norm", s.point?.second?.toString() ?: "")
        }
        attempts.filter { it.getValue("main_seq").isEmpty() }.forEach {
            val o = it.getValue("outcome"); notProcessed[o] = (notProcessed[o] ?: 0) + 1
        }
        return Report(metadata.getString("session_id"), metadata.getString("participant_id"), parametersMatch,
            attempts.size, processed, notProcessed, resets, mismatches, errors)
    }

    fun json(r: Report): String = EvaluationJson.write(linkedMapOf(
        "tool" to "EvaluationReplay", "tool_version" to TOOL_VERSION,
        "scope" to "numerical gaze module only (GazeModel + GazePipeline/GazeStabilizer); not camera, detection or latency",
        "session_id" to r.sessionId, "participant_id" to r.participantId, "ok" to r.ok,
        "parameters_match" to r.parametersMatch, "attempt_rows" to r.attemptRows,
        "processed_replayed" to r.processedReplayed, "not_processed_by_outcome" to r.notProcessed.toMap(),
        "resets_applied" to r.resetsApplied, "mismatch_count" to r.mismatches.size,
        "mismatches" to r.mismatches.take(50).map { mapOf("attempt_seq" to it.attemptSeq, "field" to it.field,
            "logged" to it.logged, "replayed" to it.replayed) },
        "errors" to r.errors,
    )) + "\n"

    /** Session directories (those holding attempts.csv) under [root], in sorted order. */
    fun discover(root: File): List<File> =
        if (File(root, EvaluationSession.ATTEMPTS_FILE).isFile) listOf(root)
        else root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.flatMap { discover(it) }
}
