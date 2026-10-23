package dev.gazedot

import android.content.Context
import android.graphics.*
import android.view.*

class DotOverlay(context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val displayBounds = DisplaySpace.bounds(context)
    private val viewOrigin = IntArray(2)
    private val dot = object : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var position: Pair<Double, Double>? = null
        var progress = 0f
        var scrollMode = false
        var label = ""
        var suggestion: Pair<Double, Double>? = null
        override fun onDraw(canvas: Canvas) {
            val d=resources.displayMetrics.density
            getLocationOnScreen(viewOrigin)
            suggestion?.let { p ->
                paint.color=Color.CYAN; paint.style=Paint.Style.STROKE; paint.strokeWidth=3*d
                canvas.drawCircle((p.first*displayBounds.width()-viewOrigin[0]).toFloat(),
                    (p.second*displayBounds.height()-viewOrigin[1]).toFloat(),19*d,paint)
                paint.style=Paint.Style.FILL
            }
            if(label.isNotBlank()) {
                paint.color=Color.BLACK; paint.textSize=14*d; paint.textAlign=Paint.Align.CENTER
                canvas.drawRoundRect(8*d,38*d,width-8*d,80*d,12*d,12*d,paint)
                paint.color=Color.WHITE
                canvas.drawText(label.take(44),width/2f,64*d,paint)
            }
            val p = position ?: return
            val x = (p.first.coerceIn(.015,.985) * displayBounds.width() - viewOrigin[0]).toFloat()
            val y = (p.second.coerceIn(.015,.985) * displayBounds.height() - viewOrigin[1]).toFloat()
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
    init {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ blocks touches through untrusted overlays above the opacity threshold.
            alpha = 0.7f
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        manager.addView(dot, params)
    }
    fun show(position: Pair<Double, Double>?, progress: Float=0f, label: String="", suggestion: Pair<Double,Double>?=null,
        scrollMode: Boolean = false) {
        dot.position = position; dot.progress=progress.coerceIn(0f,1f); dot.label=label
        dot.suggestion=suggestion; dot.scrollMode=scrollMode; dot.invalidate()
    }
    fun close() { if (dot.isAttachedToWindow) manager.removeView(dot) }
}
