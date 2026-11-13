package dev.gazedot

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.LinearLayout
import android.widget.ScrollView

class DotOverlay(context: Context) {
    // On Android 12+, use the connected accessibility service's trusted overlay type.
    // A full-screen TYPE_APPLICATION_OVERLAY can make system navigation touches get
    // rejected even though it is FLAG_NOT_TOUCHABLE and visually transparent there.
    private val accessibilityContext = if (android.os.Build.VERSION.SDK_INT >= 31) {
        ControlAccessibilityService.instance?.let { service ->
            runCatching {
                val display = service.getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                    ?: error("Default display is unavailable")
                service.createDisplayContext(display).createWindowContext(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            }.getOrNull()
        }
    } else null
    private val overlayContext = accessibilityContext ?: context
    private var overlayType = if (accessibilityContext != null)
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var manager = overlayContext.getSystemService(WindowManager::class.java)
    private val displayBounds = DisplaySpace.bounds(context)
    private val viewOrigin = IntArray(2)
    private val dot = object : View(overlayContext) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var position: Pair<Double, Double>? = null
        var progress = 0f
        var scrollMode = false
        override fun onDraw(canvas: Canvas) {
            val d=resources.displayMetrics.density
            getLocationOnScreen(viewOrigin)
            val p = position ?: return
            val x = (p.first.coerceIn(0.0,1.0) * displayBounds.width() - viewOrigin[0]).toFloat()
            val y = (p.second.coerceIn(0.0,1.0) * displayBounds.height() - viewOrigin[1]).toFloat()
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 10 * resources.displayMetrics.density, paint)
            paint.color = if (scrollMode) Color.rgb(6, 108, 236) else Color.RED
            canvas.drawCircle(x, y, 7 * resources.displayMetrics.density, paint)
            if(progress > 0f) {
                paint.style=Paint.Style.STROKE; paint.strokeWidth=3*d; paint.color=Color.rgb(6,108,236)
                canvas.drawArc(x-18*d,y-18*d,x+18*d,y+18*d,-90f,360f*progress,false,paint)
                paint.style=Paint.Style.FILL
            }
        }
    }
    private val status = RecoveryUi.text(overlayContext, "").apply {
        setPadding(RecoveryUi.dp(context, 16), RecoveryUi.dp(context, 8),
            RecoveryUi.dp(context, 16), RecoveryUi.dp(context, 8))
        setBackgroundColor(Color.rgb(245, 245, 255))
    }
    private var card: ScrollView? = null
    private var statusAttached = false
    init {
        try {
            manager.addView(dot, createParams(overlayType))
        } catch (e: RuntimeException) {
            if (accessibilityContext == null) throw e
            // Keep tracking available if this Android build rejects the accessibility window.
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            manager = context.getSystemService(WindowManager::class.java)
            manager.addView(dot, createParams(overlayType))
        }
    }

    private fun createParams(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = 0.7f
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(WindowInsets.Type.systemBars())
            setFitInsetsIgnoringVisibility(true)
        }
    }
    fun show(position: Pair<Double, Double>?, progress: Float=0f, label: String="",
        scrollMode: Boolean = false) {
        dot.position = position; dot.progress=progress.coerceIn(0f,1f)
        dot.scrollMode=scrollMode; dot.invalidate()
        if (label.isBlank() || card != null) {
            if (statusAttached) { manager.removeView(status); statusAttached = false }
            setDotOpacity(.7f)
        } else {
            status.text = label
            if (!statusAttached) {
                if (overlayType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) setDotOpacity(.3f)
                statusAttached = runCatching { manager.addView(status, panelParams(touchable = false)); true }.getOrDefault(false)
                if (!statusAttached) setDotOpacity(.7f)
            }
        }
    }

    private fun setDotOpacity(alpha: Float) {
        val params = dot.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.alpha != alpha && dot.isAttachedToWindow) {
            params.alpha = alpha
            manager.updateViewLayout(dot, params)
        }
    }

    private fun panelParams(touchable: Boolean) = WindowManager.LayoutParams(
        (displayBounds.width() - RecoveryUi.dp(overlayContext, 24)).coerceAtLeast(1),
        WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = RecoveryUi.dp(overlayContext, 12)
        // Two overlapping untrusted windows must still allow touches through to the app.
        alpha = if (touchable || overlayType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) 1f else .7f
    }

    fun showHelp(onTroubleshoot: () -> Unit, onPause: () -> Unit) {
        if (card != null) return
        show(null)
        val column = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            val padding = RecoveryUi.dp(context, 20)
            setPadding(padding, padding, padding, padding)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = padding.toFloat()
                setStroke(RecoveryUi.dp(context, 2), RecoveryUi.blue)
            }
        }
        column.addView(RecoveryUi.text(overlayContext, overlayContext.getString(R.string.recognition_help_title), 22f))
        column.addView(RecoveryUi.text(overlayContext,
            overlayContext.getString(R.string.recognition_help_body)))
        fun button(label: String, action: () -> Unit) {
            column.addView(RecoveryUi.button(overlayContext, label, action),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = RecoveryUi.dp(overlayContext, 12) })
        }
        button(overlayContext.getString(R.string.recognition_help_solve), onTroubleshoot)
        button(overlayContext.getString(R.string.recognition_help_pause), onPause)
        val panel = object : ScrollView(overlayContext) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (displayBounds.height() - RecoveryUi.dp(overlayContext, 96)).coerceAtLeast(1)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply { addView(column) }
        // Store only after addView succeeds; the service also offers notification/home fallback.
        manager.addView(panel, panelParams(touchable = true))
        card = panel
    }

    fun hideHelp() { card?.let { if (it.isAttachedToWindow) manager.removeView(it) }; card = null }
    fun close() {
        hideHelp()
        if (statusAttached) { manager.removeView(status); statusAttached = false }
        if (dot.isAttachedToWindow) manager.removeView(dot)
    }
}
