package dev.gazedot

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Local dwell feedback stays visible above the opaque writing window and its buttons. */
internal class WritingFeedback(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 3 * density }
    private var focused = false
    private var progress = 0f
    fun update(focused: Boolean, progress: Float) {
        if (this.focused == focused && this.progress == progress) return
        this.focused = focused; this.progress = progress.coerceIn(0f, 1f); invalidateSelf()
    }
    override fun draw(canvas: Canvas) {
        if (!focused) return
        val rect = RectF(bounds).apply { inset(3 * density, 3 * density) }
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(rect, 24 * density, 24 * density, paint)
        val left = rect.left + 20 * density
        val right = (rect.right - 20 * density).coerceAtLeast(left)
        canvas.drawLine(left, rect.bottom - 5 * density, left + (right - left) * progress, rect.bottom - 5 * density, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
