package dev.gazedot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The three requested screens. Selection never uses a touch listener or a success timer. */
@SuppressLint("ViewConstructor") // Created only in code, with the successful calibration's dimensions.
internal class PostCalibrationTutorialView(
    context: Context,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val onFinished: () -> Unit,
) : FrameLayout(context) {
    private enum class Step { CALIBRATED, BUTTON, FINISHED }
    private var step = Step.CALIBRATED
    private var closed = false
    private val dwell = TutorialDwell()
    private val ink = Color.rgb(17, 18, 70)
    private val font = resources.getFont(R.font.geologica)
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val logo = label("NoTouch", 800, Gravity.START)
    private var target: GazeButton? = null
    private val advance = Runnable {
        if (!closed && step == Step.CALIBRATED && TrackerState.running) showStep(Step.BUTTON)
    }
    private val freshnessCheck = object : Runnable {
        override fun run() {
            if (closed || step != Step.BUTTON) return
            dwell.expire(SystemClock.uptimeMillis())
            target?.setProgress(dwell.progress)
            postDelayed(this, 50)
        }
    }

    init {
        setBackgroundColor(Color.WHITE)
        addView(logo, LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        addView(column, LayoutParams(-1, -2, Gravity.CENTER))
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            updateLayout()
            insets
        }
        showStep(Step.CALIBRATED)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        if (!closed && step == Step.CALIBRATED) postDelayed(advance, CALIBRATION_COMPLETE_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    fun close() {
        closed = true
        removeCallbacks(advance)
        removeCallbacks(freshnessCheck)
        dwell.reset()
        target?.setProgress(0f)
    }

    fun onGaze(gaze: ScreenGaze?) {
        if (closed || step != Step.BUTTON) return
        val button = target ?: return
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        val visible = Rect()
        val inside = gaze != null && TrackerState.running && button.isShown &&
            button.getLocalVisibleRect(visible) &&
            visible.contains((gaze.x * displayWidth - location[0]).toInt(),
                (gaze.y * displayHeight - location[1]).toInt()) &&
            TutorialButtonBounds(location[0], location[1], button.width, button.height)
                .contains(gaze, displayWidth, displayHeight)
        val selected = dwell.update(inside, gaze?.uptimeMillis ?: 0L, SystemClock.uptimeMillis())
        button.setProgress(dwell.progress)
        if (selected) {
            showStep(Step.FINISHED)
            onFinished()
        }
    }

    private fun label(value: String, weight: Int = 700, alignment: Int = Gravity.CENTER) = TextView(context).apply {
        text = value
        setTextColor(ink)
        gravity = alignment
        typeface = font
        fontVariationSettings = "'wght' $weight"
        includeFontPadding = false
    }

    private fun showStep(next: Step) {
        step = next
        removeCallbacks(advance)
        removeCallbacks(freshnessCheck)
        dwell.reset()
        target = null
        column.removeAllViews()
        fun text(value: String, size: Int, gap: Int = 0) {
            column.addView(label(value).apply { tag = size }, LinearLayout.LayoutParams(-1, -2).apply {
                // Stored as design units; updateLayout scales these for the available window.
                topMargin = gap
            })
        }
        when (next) {
            Step.CALIBRATED -> text("시선 보정이\n완료되었습니다!", 28)
            Step.BUTTON -> {
                text("이제 시선으로 화면을 조작해\n볼까요?", 23)
                text("아래 버튼을 바라보고 잠시 시선을\n유지해 보세요.", 23, 30)
                text("버튼이 선택될 때까지 계속 바라봐 주세요.", 23, 30)
                target = GazeButton(context).also {
                    column.addView(it, LinearLayout.LayoutParams(178, 44).apply { topMargin = 44 })
                }
                post(freshnessCheck)
            }
            Step.FINISHED -> {
                text("튜토리얼이\n완료되었습니다!", 28)
                text("NoTouch의 기본 사용법을\n모두 익혔습니다.", 23, 24)
                text("이제 직접 사용해 봅시다", 23, 30)
            }
        }
        updateLayout()
        if (isAttachedToWindow) announceForAccessibility(when (next) {
            Step.CALIBRATED -> "시선 보정이 완료되었습니다!"
            Step.BUTTON -> "버튼 선택 연습. 여기를 바라보세요 버튼을 1초 동안 바라보세요."
            Step.FINISHED -> "튜토리얼이 완료되었습니다!"
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLayout()
        dwell.reset()
        target?.setProgress(0f)
    }

    private fun updateLayout() {
        if (width == 0 || height == 0) return
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val scale = minOf(availableWidth / 390f, availableHeight / 870f)
        fun px(value: Int) = (value * scale).toInt()
        logo.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * scale)
        (logo.layoutParams as LayoutParams).apply {
            leftMargin = px(24)
            topMargin = px(24)
        }.also { logo.layoutParams = it }
        column.layoutParams = LayoutParams(availableWidth - px(80), -2, Gravity.CENTER)
        column.translationY = if (step == Step.CALIBRATED) -availableHeight * .04f else 0f
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            val params = child.layoutParams as LinearLayout.LayoutParams
            params.topMargin = px(when {
                i == 0 -> 0
                child is GazeButton -> 44
                step == Step.FINISHED && i == 1 -> 24
                else -> 30
            })
            if (child is TextView) child.setTextSize(TypedValue.COMPLEX_UNIT_PX, (child.tag as Int) * scale)
            if (child is GazeButton) {
                params.width = px(178)
                params.height = px(44)
            }
            child.layoutParams = params
        }
    }

    private class GazeButton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Path()
        private val outlineBounds = RectF()
        private val measure = PathMeasure()
        private val segment = Path()
        private var progress = 0f
        private val font = resources.getFont(R.font.geologica)
        init {
            contentDescription = "여기를 바라보세요. 연속 1초 응시하면 선택됩니다."
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        fun setProgress(value: Float) {
            progress = value
            invalidate()
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val inset = h * .09f
            val radius = h / 2f - inset
            outlineBounds.set(inset, inset, w - inset, h - inset)
            outline.reset()
            outline.addRoundRect(outlineBounds, radius, radius, Path.Direction.CW)
            measure.setPath(outline, true)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height / 2f
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(6, 108, 236)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            paint.color = Color.WHITE
            paint.typeface = font
            paint.isFakeBoldText = true
            paint.textSize = height * .32f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("여기를 바라보세요", width / 2f,
                height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
            if (progress > 0f) {
                segment.reset()
                measure.getSegment(0f, measure.length * progress, segment, true)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = height * .055f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawPath(segment, paint)
            }
        }
    }

    companion object {
        // Adjust the wait between the calibration success screen and button practice here.
        const val CALIBRATION_COMPLETE_DELAY_MS = 3_000L
    }
}
