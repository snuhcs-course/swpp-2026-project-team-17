package dev.gazedot

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/** Shared only by the recovery card and screen. Sizes follow the system font scale. */
internal object RecoveryUi {
    val ink = Color.rgb(17, 18, 70)
    val blue = Color.rgb(6, 108, 236)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun text(context: Context, value: String, size: Float = 18f) = TextView(context).apply {
        text = value; textSize = size; setTextColor(ink)
        typeface = resources.getFont(R.font.geologica)
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(context, 6), 0, dp(context, 6))
    }
    fun button(context: Context, value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 18f; isAllCaps = false
        setTextColor(Color.WHITE); typeface = resources.getFont(R.font.geologica)
        minHeight = dp(context, 56)
        setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(blue); cornerRadius = dp(context, 28).toFloat()
        }
        setOnClickListener { action() }
    }
}
