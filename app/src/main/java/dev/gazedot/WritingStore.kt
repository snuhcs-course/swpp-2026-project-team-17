package dev.gazedot

import android.content.Context
import android.annotation.SuppressLint

// Successful durable writes are required before external IPC; apply() cannot report storage failures.
@SuppressLint("ApplySharedPref")
internal class WritingStore(context: Context) {
    private val prefs = context.getSharedPreferences("notouch_writing", Context.MODE_PRIVATE)
    private var consentChoice = prefs.getString("prediction_consent", "").orEmpty()
    fun phrases() = WritingPhrases.defaults.mapIndexed { index, fallback ->
        prefs.getString("phrase_$index", fallback)?.takeIf { it.isNotBlank() } ?: fallback
    }
    fun savePhrase(index: Int, text: String): Boolean = index in 0..5 && text.isNotBlank() && text.length <= 200 &&
        prefs.edit().putString("phrase_$index", text.trim()).commit()
    val savedDraft get() = prefs.getString("draft", "").orEmpty()
    val uncertain get() = prefs.getBoolean("uncertain", false)
    fun saveDraft(text: String, uncertain: Boolean = false): Boolean = prefs.edit()
        .putString("draft", text).putBoolean("uncertain", uncertain).commit()
    val consent get() = consentChoice
    fun consent(value: String): Boolean {
        consentChoice = value
        return prefs.edit().putString("prediction_consent", value).commit()
    }
}
