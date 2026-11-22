package dev.gazedot

import android.content.Context

/** Retain existing phrase choices, but no separate draft or draft-recovery state. */
internal class WritingStore(context: Context) {
    private val prefs = context.getSharedPreferences("notouch_writing", Context.MODE_PRIVATE)
    init { prefs.edit().remove("draft").remove("uncertain").remove("prediction_consent").apply() }
    fun phrases() = WritingPhrases.defaults.mapIndexed { index, fallback ->
        prefs.getString("phrase_$index", fallback)?.takeIf { it.isNotBlank() } ?: fallback
    }
}
