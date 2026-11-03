package dev.gazedot

import android.content.Context

/** The saved choice is separate from an overlay's temporary runtime/lifecycle state. */
internal object EasyControlSettings {
    private const val FILE = "interaction_preferences"
    private const val ENABLED = "easy_control_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        TrackerState.cancelActions?.invoke()
        ControlAccessibilityService.instance?.syncEasyPanelPreference()
    }
}
