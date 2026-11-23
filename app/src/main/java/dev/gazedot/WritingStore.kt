package dev.gazedot

import android.content.Context
import org.json.JSONObject

/** Retain existing phrase choices, but no separate draft or draft-recovery state. */
internal class WritingStore(context: Context) {
    private val prefs = context.getSharedPreferences("notouch_writing", Context.MODE_PRIVATE)
    private val preferences=CompletionPreferences(runCatching {
        val json=JSONObject(prefs.getString("completion_counts", "{}") ?: "{}")
        json.keys().asSequence().associateWith { json.optInt(it,0) }
    }.getOrDefault(emptyMap()))
    fun rank(input: PredictionInput, candidates: List<WritingCandidate>) = preferences.rank(input,candidates)
    fun accepted(candidate: WritingCandidate) {
        preferences.record(candidate)
        prefs.edit().putString("completion_counts",JSONObject(preferences.snapshot()).toString()).apply()
    }
    fun clearLearned() {
        preferences.clear(); prefs.edit().remove("completion_counts").apply()
    }
    init { prefs.edit().remove("draft").remove("uncertain").remove("prediction_consent").apply() }
    fun phrases() = WritingPhrases.defaults.mapIndexed { index, fallback ->
        prefs.getString("phrase_$index", fallback)?.takeIf { it.isNotBlank() } ?: fallback
    }
}
