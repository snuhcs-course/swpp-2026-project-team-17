package dev.gazedot

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** A local accessibility overlay: actual source nodes are activated, never synthetic demo taps. */
internal class EasyControlPanel(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val screen = EasyScreenActions(service)
    private val selection = EasyPanelSelection()
    private val assistance = GazeAssistDetector()
    private val density = service.resources.displayMetrics.density
    private val ink = Color.rgb(17, 18, 70)
    private val blue = Color.rgb(6, 108, 236)
    private val bindings = mutableListOf<Binding>()
    private var window: View? = null
    private var hint: TextView? = null
    private var snapshot: EasyScreenSnapshot? = null
    private var focus: AssistFocus? = null
    private var suggested = emptyList<EasyScreenAction>()
    private var requirePanelExit = false
    private var choices: List<EasyScreenAction>? = null
    private var open = false
    private var lastSampleAt = Long.MIN_VALUE
    private var feedback = ""
    private data class Binding(
        val id: String, val view: View, val progress: ProgressBar?,
        val expands: Boolean, val action: () -> Unit,
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!TrackerState.easyPanelEnabled) return
            if (!TrackerState.running) { setEnabled(false); return }
            val available = TrackerState.controls && TrackerState.interactionAllowed &&
                !TrackerState.collecting && !TrackerState.speaking
            if (!available) {
                removeWindow()
                selection.reset()
                snapshot = null
                open = false
                setBlocking(false)
                assistance.reset(SystemClock.uptimeMillis(), GazeAssistDetector.COOLDOWN_MS)
            } else {
                if (window == null) showLauncher()
                if (open && snapshot?.let { !screen.isCurrent(it) } == true) invalidateSource()
                onGaze()
            }
            handler.postDelayed(this, 50)
        }
    }

    fun setEnabled(enabled: Boolean) {
        handler.removeCallbacksAndMessages(null)
        removeWindow()
        selection.reset()
        snapshot = null
        choices = null
        suggested = emptyList()
        focus = null
        assistance.reset()
        open = false
        TrackerState.easyPanelEnabled = enabled
        setBlocking(false)
        TrackerState.localDwellProgress = 0f
        TrackerState.cancelActions?.invoke()
        if (enabled) handler.post(tick)
    }

    fun close() = setEnabled(false)

    private fun setBlocking(blocking: Boolean) {
        if (TrackerState.easyPanelBlocking == blocking) return
        TrackerState.easyPanelBlocking = blocking
        // Never allow a partially completed background dwell to carry into/out of the panel.
        TrackerState.cancelActions?.invoke()
    }

    /** Called once per real camera result, before normal gestures are dispatched. */
    fun observe(sample: GazeSample, eye: EyeState) {
        if (!TrackerState.easyPanelEnabled) return
        val now = SystemClock.uptimeMillis()
        if (!TrackerState.running || !TrackerState.controls || !TrackerState.interactionAllowed ||
            TrackerState.collecting || TrackerState.speaking || open) {
            assistance.reset(now, GazeAssistDetector.COOLDOWN_MS)
            return
        }
        val bounds = DisplaySpace.bounds(service)
        val gaze = sample.toScreenGaze()
        val valid = eye == EyeState.OPEN && gaze?.fresh(now) == true &&
            gaze.x in 0.0..1.0 && gaze.y in 0.0..1.0
        val point = if (valid) AssistPoint(gaze!!.x * bounds.width(), gaze.y * bounds.height()) else null
        val overLauncher = point != null && bindings.mapNotNull(::geometry).any { it.contains(point.x, point.y) }
        setBlocking(overLauncher)
        // Preserve the preceding source-screen gaze while the user approaches the help launcher.
        if (overLauncher) return
        val interrupted = sample.scrollMode || sample.actions.any {
            it is EyeAction.Click || it is EyeAction.Scroll || it is EyeAction.ScrollMode || it is EyeAction.Dictate
        }
        val area = assistance.update(point, sample.timestampMs, now, minOf(bounds.width(), bounds.height()).toDouble(),
            sample.dwellProgress, interrupted, sample.dwellTargetKey)
        if (area != null) showNearby(area, automatic = true)
    }

    private fun nearbyActions(source: EasyScreenSnapshot, area: AssistFocus): List<EasyScreenAction> {
        val bounds = DisplaySpace.bounds(service)
        val candidates = source.actions.filter { it.kind in listOf(EasyActionKind.CLICK, EasyActionKind.FOCUS) }
        val ids = NearbyGazeCandidates.rank(area, candidates.mapNotNull { action ->
            val rect = android.graphics.Rect(action.bounds)
            if (!rect.intersect(bounds)) null else AssistTarget(action.id, rect.left.toDouble(), rect.top.toDouble(),
                rect.right.toDouble(), rect.bottom.toDouble())
        })
        return ids.mapNotNull { id -> candidates.firstOrNull { it.id == id } }
    }

    private fun showNearby(area: AssistFocus?, automatic: Boolean) {
        val source = screen.capture()
        val items = if (source != null && area != null) nearbyActions(source, area) else emptyList()
        if (automatic && items.size < 2) return
        focus = area
        snapshot = source
        suggested = items
        choices = null
        open = true
        requirePanelExit = true
        feedback = "주변 버튼을 찾지 못했어요. 접은 뒤 원하는 버튼 근처를 바라보고 다시 시도하세요."
        setBlocking(true)
        selection.reset()
        showPanel()
    }

    fun onWindowEvent(event: AccessibilityEvent) {
        if (!open) {
            // Ignore our own overlay's progress/label events; only source app changes reset history.
            if (service.windows.any { it.id == event.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }) {
                assistance.reset(SystemClock.uptimeMillis(), GazeAssistDetector.COOLDOWN_MS)
            }
            return
        }
        val source = snapshot ?: return
        // Overlay progress/label changes have their own window ID and must not invalidate the source.
        if (event.windowId == source.windowId && event.eventType in listOf(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            invalidateSource()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !screen.isCurrent(source)) {
            invalidateSource()
        }
    }

    private fun dp(value: Int) = (value * density).toInt()
    private fun background(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float, color: Int = ink) = TextView(service).apply {
        this.text = value; textSize = size; setTextColor(color)
        typeface = service.resources.getFont(R.font.geologica)
    }

    private fun removeWindow() {
        val old = window
        window = null
        hint = null
        bindings.clear()
        if (old?.isAttachedToWindow == true) runCatching { manager.removeView(old) }
        TrackerState.localDwellProgress = 0f
    }

    private fun attach(view: View, full: Boolean) {
        window = view
        val params = WindowManager.LayoutParams(
            if (full) WindowManager.LayoutParams.MATCH_PARENT else dp(150),
            if (full) WindowManager.LayoutParams.MATCH_PARENT else dp(64),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (full) Gravity.TOP or Gravity.START else Gravity.BOTTOM or Gravity.START
            if (!full) { x = dp(12); y = dp(24) }
            title = "NoTouch 쉬운 조작"
        }
        try { manager.addView(view, params) }
        catch (_: Exception) {
            window = null
            bindings.clear()
            TrackerState.easyPanelEnabled = false
            setBlocking(false)
            TrackerState.actionStatus = "쉬운 조작 창을 열지 못했습니다 · 접근성 설정을 확인하세요"
        }
    }

    private fun bind(id: String, view: View, progress: ProgressBar? = null,
        expands: Boolean = false, action: () -> Unit) {
        val binding = Binding(id, view, progress, expands, action)
        bindings += binding
        view.setOnClickListener {
            geometry(binding)?.let(selection::holdUntilExit)
            action()
        }
    }

    private fun geometry(binding: Binding): EasyPanelTarget? {
        val v = binding.view
        if (!v.isShown || !v.isEnabled || v.width <= 0 || v.height <= 0) return null
        val location = IntArray(2)
        v.getLocationOnScreen(location)
        return EasyPanelTarget(binding.id, location[0].toDouble(), location[1].toDouble(),
            (location[0] + v.width).toDouble(), (location[1] + v.height).toDouble(),
            if (binding.expands) dp(14).toDouble() else 0.0)
    }

    private fun onGaze() {
        val now = SystemClock.uptimeMillis()
        val gaze = TrackerState.gaze
        val valid = gaze?.fresh(now) == true && TrackerState.eyeState == EyeState.OPEN &&
            gaze.x in 0.0..1.0 && gaze.y in 0.0..1.0
        if (!valid) {
            selection.reset(clearLatch = false)
        } else if (gaze!!.uptimeMillis != lastSampleAt) {
            lastSampleAt = gaze.uptimeMillis
            val bounds = DisplaySpace.bounds(service)
            val targets = bindings.mapNotNull(::geometry)
            val x = gaze.x * bounds.width(); val y = gaze.y * bounds.height()
            val selected = if (open && requirePanelExit) {
                if (targets.none { it.contains(x, y, expanded = true) }) requirePanelExit = false
                selection.reset()
                null
            } else selection.update(x, y, gaze.uptimeMillis, now, targets)
            if (selected != null) {
                if (selected.size == 1) bindings.firstOrNull { it.id == selected.single() }?.action?.invoke()
                else {
                    val items = snapshot?.actions.orEmpty().filter { "action:${it.id}" in selected }
                    if (items.size == 2) { choices = items; requirePanelExit = true; showPanel() }
                }
            }
        } else selection.expire(now)

        if (TrackerState.easyPanelBlocking) TrackerState.localDwellProgress = selection.progress
        bindings.forEach { binding ->
            val active = binding.id in selection.highlighted
            binding.progress?.progress = if (active) (selection.progress * 100).toInt() else 0
            binding.view.alpha = if (active) 0.85f else 1f
        }
        val message = when {
            !valid && TrackerState.model == null -> "시선 보정 후 눈으로 선택할 수 있어요. 지금은 터치로도 체험할 수 있어요."
            !valid -> "눈 인식을 기다리고 있어요. 터치로도 선택할 수 있어요."
            requirePanelExit && open -> "안내 영역으로 시선을 옮긴 뒤 원하는 카드를 선택하세요."
            selection.waitingForExit -> "안내 영역으로 시선을 옮긴 뒤 다음 항목을 선택하세요."
            selection.highlighted.size == 2 -> "두 항목 사이에 있어요. 계속 바라보면 두 후보를 크게 보여드려요."
            snapshot == null && open -> "실행할 항목을 새로 불러오세요."
            !open -> "선택이 어려우면 시선 주변 버튼을 크게 보여드려요."
            else -> "원하는 카드를 1초 바라보세요. 버튼 주변도 선택 영역이에요."
        }
        if (hint?.text?.toString() != message) hint?.text = message
    }

    private fun showLauncher() {
        removeWindow()
        setBlocking(false)
        selection.reset(clearLatch = false)
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = background(blue, 28); elevation = dp(6).toFloat()
            isClickable = true; contentDescription = "쉬운 조작 열기"
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(text("쉬운 조작", 17f, Color.WHITE).apply { gravity = Gravity.CENTER })
        val progress = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
        bind("launcher", root, progress) {
            val bounds = DisplaySpace.bounds(service)
            showNearby(assistance.recentFocus(SystemClock.uptimeMillis(), minOf(bounds.width(), bounds.height()).toDouble()),
                automatic = false)
        }
        attach(root, false)
    }

    private fun invalidateSource() {
        snapshot = null
        suggested = emptyList()
        choices = null
        selection.reset()
        feedback = "화면이 바뀌어 선택을 중단했어요. 새로고침으로 다시 불러오세요."
        showPanel()
    }

    private fun activate(action: EasyScreenAction) {
        val source = snapshot ?: return
        // Remove the panel before dispatch; never click a screen coordinate behind the overlay.
        open = false
        snapshot = null
        choices = null
        removeWindow()
        val ok = screen.execute(source, action)
        TrackerState.actionStatus = if (ok) "${action.label.take(25)} · 실행됨" else "화면이 바뀌었거나 실행할 수 없는 항목입니다"
        feedback = TrackerState.actionStatus
        assistance.reset(SystemClock.uptimeMillis(), GazeAssistDetector.COOLDOWN_MS)
        showLauncher()
    }

    private fun showPanel() {
        removeWindow()
        selection.reset(clearLatch = false)
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            setBackgroundColor(Color.rgb(245, 247, 252))
        }
        val header = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("NoTouch · 쉬운 조작", 21f), LinearLayout.LayoutParams(0, -2, 1f))
        val exit = Button(service).apply { text = "일반 조작"; textSize = 13f; isAllCaps = false }
        header.addView(exit, LinearLayout.LayoutParams(dp(94), dp(52)))
        bind("disable", exit) { EasyControlSettings.setEnabled(service, false) }
        root.addView(header)

        root.addView(text(if (choices != null) "어느 항목을 선택할까요?" else "시선 주변 후보 · ${suggested.size}개", 18f),
            LinearLayout.LayoutParams(-1, dp(38)))
        hint = text("", 13f).apply { minLines = 2; maxLines = 3 }
        root.addView(hint, LinearLayout.LayoutParams(-1, dp(68)))
        val content = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val visible = choices ?: suggested
        if (visible.isEmpty()) {
            content.addView(text(feedback.ifBlank { "읽을 수 있는 항목이 없어요.\n다른 앱을 열고 다시 시도하세요." }, 20f).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -1))
        }
        visible.forEachIndexed { index, item ->
            val card = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = background(Color.WHITE)
                isClickable = true; isFocusable = true; contentDescription = item.label
                elevation = dp(1).toFloat()
            }
            card.addView(text(item.label, 23f).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, 0, 1f))
            val progress = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progressTintList = android.content.res.ColorStateList.valueOf(blue)
            }
            card.addView(progress, LinearLayout.LayoutParams(-1, dp(5)))
            content.addView(card, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                if (index > 0) topMargin = dp(16)
            })
            bind("action:${item.id}", card, progress, expands = choices == null) { activate(item) }
        }
        val footer = LinearLayout(service).apply { gravity = Gravity.CENTER }
        fun control(id: String, title: String, enabled: Boolean = true, action: () -> Unit) {
            val button = Button(service).apply { text = title; textSize = 13f; isAllCaps = false; isEnabled = enabled }
            footer.addView(button, LinearLayout.LayoutParams(0, dp(64), 1f))
            bind(id, button, action = action)
        }
        control("previous", "후보로", choices != null) {
            choices = null; requirePanelExit = true
            showPanel()
        }
        control("back", "뒤로", snapshot != null) {
            snapshot?.actions?.firstOrNull { it.kind == EasyActionKind.BACK }?.let(::activate)
        }
        control("refresh", "새로고침") {
            showNearby(focus, automatic = false)
        }
        control("close", "접기") {
            open = false; snapshot = null; choices = null
            assistance.reset(SystemClock.uptimeMillis(), GazeAssistDetector.COOLDOWN_MS)
            showLauncher()
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(12) })
        attach(root, true)
    }
}
