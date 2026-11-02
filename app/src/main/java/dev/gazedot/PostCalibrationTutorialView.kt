package dev.gazedot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Calibration success → dwell click → wink-controlled scrolling → completion. */
@SuppressLint("ViewConstructor")
internal class PostCalibrationTutorialView(
    context: Context,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val onRecalibrate: () -> Unit,
    private val onFinished: () -> Unit,
) : FrameLayout(context) {
    private enum class Step { CALIBRATED, BUTTON, SCROLL, FINISHED }
    private var step = Step.CALIBRATED
    private var closed = false
    private val dwell = TutorialDwell()
    private val retryGesture = EyeGestureDetector()
    private val scrolling = ScrollController()
    private var practice = ScrollPractice()
    private val ink = Color.rgb(17, 18, 70)
    private val font = resources.getFont(R.font.geologica)
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val logo = label("NoTouch", 800, Gravity.START)
    private var target: GazeButton? = null
    private var scrollPanel: ScrollView? = null
    private var horizontalPanel: HorizontalScrollView? = null
    private var scrollStatus: TextView? = null
    private var animation: ValueAnimator? = null
    private val advance = Runnable {
        if (!closed && step == Step.CALIBRATED && TrackerState.running) showStep(Step.BUTTON)
    }
    private val freshnessCheck = object : Runnable {
        override fun run() {
            if (closed || step != Step.BUTTON) return
            dwell.expire(SystemClock.uptimeMillis())
            TrackerState.localDwellProgress = dwell.progress
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

    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }

    fun close() {
        closed = true
        removeCallbacks(advance); removeCallbacks(freshnessCheck)
        animation?.cancel(); animation = null
        dwell.reset(); retryGesture.reset(); scrolling.reset()
        TrackerState.localDwellProgress = 0f
        TrackerState.localScrollMode = false
        target?.setProgress(0f)
    }

    fun onFrame(sample: GazeSample?, eye: EyeState) {
        if (closed || step !in listOf(Step.BUTTON, Step.SCROLL)) return
        val now = SystemClock.uptimeMillis()
        if (sample == null || now - sample.timestampMs !in 0..250 || !TrackerState.running) {
            dwell.reset(); retryGesture.reset(); scrolling.reset()
            animation?.cancel(); animation = null
            TrackerState.localDwellProgress = 0f
            TrackerState.localScrollMode = false
            target?.setProgress(0f)
            updateScrollStatus()
            return
        }
        if (step == Step.SCROLL) {
            val result = scrolling.update(sample.timestampMs, now, eye, sample.point)
            TrackerState.localScrollMode = scrolling.enabled
            if (result.wink) result.modeChanged?.let(practice::modeChanged)
            if (result.modeChanged == false) { animation?.cancel(); animation = null }
            result.direction?.let(::animateScroll)
            updateScrollStatus()
            if (practice.complete(scrolling.enabled)) finishTutorial()
            return
        }
        if (retryGesture.update(sample.timestampMs, eye) == EyeGesture.DOUBLE_BLINK) {
            dwell.reset(); TrackerState.localDwellProgress = 0f
            onRecalibrate()
            return
        }
        val gaze = sample.toScreenGaze()
        val button = target ?: return
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        val visible = Rect()
        val inside = gaze != null && gaze.fresh(now) && button.isShown &&
            button.getLocalVisibleRect(visible) &&
            visible.contains((gaze.x * displayWidth - location[0]).toInt(),
                (gaze.y * displayHeight - location[1]).toInt()) &&
            TutorialButtonBounds(location[0], location[1], button.width, button.height)
                .contains(gaze, displayWidth, displayHeight, rounded = false)
        val selected = dwell.update(inside, sample.timestampMs, now)
        TrackerState.localDwellProgress = dwell.progress
        button.setProgress(dwell.progress)
        if (selected) showStep(Step.SCROLL)
    }

    private fun animateScroll(direction: ScrollDirection) {
        val panel = scrollPanel ?: return
        val horizontal = horizontalPanel ?: return
        if (animation != null) return
        val isHorizontal = direction.dx != 0
        val start = if (isHorizontal) horizontal.scrollX else panel.scrollY
        val distance = ((if (isHorizontal) displayWidth else displayHeight) * ScrollSettings.DISTANCE_FRACTION).toInt().coerceAtLeast(1)
        val maximum = if (isHorizontal) (horizontal.getChildAt(0).width - horizontal.width).coerceAtLeast(0)
            else (panel.getChildAt(0).height - panel.height).coerceAtLeast(0)
        val end = (start + (if (isHorizontal) direction.dx else direction.dy) * distance).coerceIn(0, maximum)
        if (end == start) return
        animation = ValueAnimator.ofInt(start, end).apply {
            duration = ScrollSettings.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val beforeX = horizontal.scrollX
                val beforeY = panel.scrollY
                if (isHorizontal) horizontal.scrollTo(it.animatedValue as Int, 0)
                else panel.scrollTo(0, it.animatedValue as Int)
                practice.moved(horizontal.scrollX - beforeX, panel.scrollY - beforeY)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    animation = null
                    if (!closed && step == Step.SCROLL) {
                        updateScrollStatus()
                        if (practice.complete(scrolling.enabled)) finishTutorial()
                    }
                }
            })
            start()
        }
    }

    private fun finishTutorial() {
        if (closed || step != Step.SCROLL) return
        showStep(Step.FINISHED)
        onFinished()
    }

    private fun updateScrollStatus() {
        scrollStatus?.text = "스크롤 모드 ${if (scrolling.enabled) "켜짐 · 파란 커서" else "꺼짐 · 빨간 커서"}\n" +
            "↑ ${if (practice.up) "✓" else "○"}  ↓ ${if (practice.down) "✓" else "○"}  " +
            "← ${if (practice.left) "✓" else "○"}  → ${if (practice.right) "✓" else "○"}" +
            if (practice.up && practice.down) "\n윙크로 모드를 끄면 완료됩니다." else "\n위·아래로 한 번씩 스크롤해 보세요."
    }

    private fun label(value: String, weight: Int = 700, alignment: Int = Gravity.CENTER) = TextView(context).apply {
        text = value; setTextColor(ink); gravity = alignment
        typeface = font; fontVariationSettings = "'wght' $weight"
        includeFontPadding = true
        if (android.os.Build.VERSION.SDK_INT >= 28) isFallbackLineSpacing = true
        setLineSpacing(0f, 1.1f)
        val verticalPadding = (2 * resources.displayMetrics.density).toInt()
        setPadding(0, verticalPadding, 0, verticalPadding)
    }

    private fun showStep(next: Step) {
        step = next
        removeCallbacks(advance); removeCallbacks(freshnessCheck)
        animation?.cancel(); animation = null
        dwell.reset(); retryGesture.reset(); scrolling.reset()
        TrackerState.localDwellProgress = 0f
        TrackerState.localScrollMode = false
        target = null; scrollPanel = null; horizontalPanel = null; scrollStatus = null
        column.removeAllViews()
        fun text(value: String, size: Int, weight: Int = 700): TextView {
            return label(value, weight).apply {
                tag = size
                // Give the first measure the real font size, before onSizeChanged rescales it.
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
            }.also {
                column.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        }
        when (next) {
            Step.CALIBRATED -> text("시선 보정이\n완료되었습니다!", 28).apply { minLines = 2 }
            Step.BUTTON -> {
                text("이제 시선으로 화면을 조작해\n볼까요?", 23)
                text("아래 버튼을 바라보고 잠시 시선을\n유지해 보세요.", 23)
                text("버튼이 선택될 때까지 계속 바라봐 주세요.", 23)
                text("잘 맞지 않으면 양눈을 빠르게 두 번\n깜빡여 다시 보정해 주세요.", 15, 500)
                target = GazeButton(context).also {
                    column.addView(it, LinearLayout.LayoutParams(BUTTON_WIDTH, BUTTON_HEIGHT))
                }
                post(freshnessCheck)
            }
            Step.SCROLL -> {
                practice = ScrollPractice()
                text("이제 시선으로 화면을 스크롤해 볼까요?", 23)
                text("왼쪽/오른쪽 눈을 깜빡이면 스크롤 모드가 켜집니다. 화면의 윗부분을 바라보면 위로, 아랫부분을 바라보면 아래로 스크롤할 수 있어요.", 18)
                text("모드가 켜지면 커서 중앙이 파란색으로 바뀝니다. 왼쪽/오른쪽 1/3을 바라보면 가로로도 스크롤해요.", 16, 500)
                text("스크롤 모드를 끄고 싶다면 다시 한번 왼쪽/오른쪽 눈을 깜빡여 주세요.", 18)
                text("주의 : Don't flirt while using the app", 16, 800)
                text("위·아래·좌·우 1/3: 해당 방향 · 중앙: 정지", 14, 500)
                val frame = FrameLayout(context).apply { tag = "scroll-demo"; setBackgroundColor(Color.rgb(245, 247, 251)) }
                val panel = ScrollView(context).apply { isFillViewport = true }
                val horizontal = HorizontalScrollView(context).apply { isFillViewport = true }
                val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                repeat(16) { index ->
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    repeat(4) { column ->
                        row.addView(label("연습 ${index + 1} · ${column + 1}", 500).apply {
                            textSize = 18f; setPadding(0, 20, 0, 20)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    rows.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
                panel.addView(rows)
                horizontal.addView(panel, LayoutParams((displayWidth * 1.8).toInt(), -1))
                frame.addView(horizontal, LayoutParams(-1, -1))
                column.addView(frame, LinearLayout.LayoutParams(-1, 190))
                scrollPanel = panel
                horizontalPanel = horizontal
                panel.post {
                    if (!closed && scrollPanel === panel) {
                        panel.scrollTo(0, (rows.height - panel.height).coerceAtLeast(0) / 2)
                        horizontal.scrollTo((panel.width - horizontal.width).coerceAtLeast(0) / 2, 0)
                    }
                }
                scrollStatus = text("", 15, 500)
                updateScrollStatus()
            }
            Step.FINISHED -> {
                text("튜토리얼이\n완료되었습니다!", 28)
                text("NoTouch의 기본 사용법을\n모두 익혔습니다.", 23)
                text("이제 직접 사용해 봅시다", 23)
            }
        }
        updateLayout()
        if (isAttachedToWindow) announceForAccessibility(when (next) {
            Step.CALIBRATED -> "시선 보정이 완료되었습니다!"
            Step.BUTTON -> "버튼을 1초 동안 바라보세요. 잘 맞지 않으면 양눈을 두 번 깜빡여 다시 보정합니다."
            Step.SCROLL -> "윙크로 모드를 켜면 커서가 파란색이 됩니다. 위·아래·왼쪽·오른쪽 1/3을 바라보면 스크롤합니다. 위아래 연습 후 윙크로 끄면 완료됩니다."
            Step.FINISHED -> "튜토리얼이 완료되었습니다!"
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLayout(); dwell.reset(); TrackerState.localDwellProgress = 0f
        target?.setProgress(0f)
    }

    private fun updateLayout() {
        if (width == 0 || height == 0) return
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val scale = minOf(availableWidth / 390f, availableHeight / if (step == Step.SCROLL) 1000f else 870f)
        fun px(value: Int) = (value * scale).toInt()
        logo.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * scale)
        (logo.layoutParams as LayoutParams).apply { leftMargin = px(24); topMargin = px(24) }
            .also { logo.layoutParams = it }
        val sideSpace = if (step == Step.CALIBRATED || step == Step.FINISHED) 48 else 80
        column.layoutParams = LayoutParams(availableWidth - px(sideSpace), -2, Gravity.CENTER)
        column.translationY = if (step == Step.CALIBRATED) -availableHeight * .04f else 0f
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            val params = child.layoutParams as LinearLayout.LayoutParams
            params.topMargin = px(when {
                i == 0 -> 0
                step == Step.SCROLL -> 16
                child is GazeButton -> 36
                step == Step.FINISHED && i == 1 -> 24
                else -> 30
            })
            if (child is TextView) child.setTextSize(TypedValue.COMPLEX_UNIT_PX, (child.tag as Int) * scale)
            if (child is GazeButton) { params.width = px(BUTTON_WIDTH); params.height = px(BUTTON_HEIGHT) }
            if (child.tag == "scroll-demo") params.height = px(190)
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
        const val BUTTON_WIDTH = 214
        const val BUTTON_HEIGHT = 54
        const val CALIBRATION_COMPLETE_DELAY_MS = 3_000L
    }
}
