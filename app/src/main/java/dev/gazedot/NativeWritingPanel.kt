package dev.gazedot

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** A non-focusable companion above the real editor/IME. Typing stays in the messaging app. */
internal class NativeWritingPanel(
    private val service: ControlAccessibilityService,
    private val target: WritingTarget,
    private val onClosed: () -> Unit,
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = service.getSharedPreferences("notouch_native_writing", 0)
    private val store = WritingStore(service)
    private val predictions = WritingPredictions { renderCandidates() }
    private val dwell = WritingDwell()
    private val buttons = linkedMapOf<String, Button>()
    private val panel = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(220, 225, 236))
        }
        elevation = dp(8).toFloat()
    }
    private val content = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(service).apply { addView(content); isFillViewport = false }
    private val status = text("", 13f)
    private val candidates = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val settings = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val settingsButton: Button
    private val closeButton: Button
    private val tabs = row()
    private var phrasesSelected = false
    private var settingsOpen = false
    private var loadingPolicy = false
    private var notice: String? = null
    private var noticeUntil = 0L
    private var closed = false
    private var inFlight = false
    private var windowOffsetY = 0
    private var version = 0L
    private var last = target.expected
    private var displayed: List<WritingCandidate> = emptyList()
    private var heldByTouch = false
    private var heldByGaze = false
    private var blockedBounds: Rect? = null
    private val openedAt = SystemClock.uptimeMillis()
    private val observer: () -> Unit = { onGaze() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) { return }
            syncEditor()
            if (closed) { return }
            position()
            dwell.expire(SystemClock.uptimeMillis())
            onGaze()
            if (notice != null && SystemClock.uptimeMillis() >= noticeUntil && !target.invalid) {
                notice = null; renderCandidates()
            }
            main.postDelayed(this, 150)
        }
    }

    init {
        // Previous builds persisted candidate results. No saved-text or recovery flow remains.
        prefs.edit().remove("pending").apply()
        val header = row()
        settingsButton = button("settings", "예측 설정", header) { toggleSettings() }
        closeButton = button("close", "닫기", header) { close() }
        closeButton.layoutParams = LinearLayout.LayoutParams(dp(if (service.resources.configuration.fontScale >= 1.3f) 96 else 72), -2)
        panel.addView(header)
        button("tab:candidates", "예측 후보", tabs) { selectTab(false) }
        button("tab:phrases", "자주 쓰는 문장", tabs) { selectTab(true) }
        content.addView(tabs)
        content.addView(settings)
        content.addView(status)
        content.addView(candidates)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        settings.visibility = View.GONE
        updateTabs()
        renderSettings()
    }

    private fun dp(n: Int) = RecoveryUi.dp(service, n)
    private fun text(value: String, size: Float) = RecoveryUi.text(service, value, size)
    private fun row() = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
    private fun button(key: String, label: String, parent: LinearLayout, action: () -> Unit): Button {
        val result = RecoveryUi.button(service, label) {
            buttons[key]?.let { button -> blockedBounds = screenBounds(button) }
            dwell.selected(key)
            TrackerState.cancelActions?.invoke()
            action()
        }.apply {
            textSize = 16f; minHeight = dp(48)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(239, 244, 255)); cornerRadius = dp(10).toFloat()
            }
            setTextColor(RecoveryUi.ink)
            foreground = WritingFeedback(service.resources.displayMetrics.density)
        }
        buttons[key] = result
        parent.addView(result, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        return result
    }

    fun show(): Boolean {
        renderCandidates()
        val params = WindowManager.LayoutParams(DisplaySpace.bounds(service).width() - dp(16), dp(240),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        panel.visibility = View.INVISIBLE
        if (runCatching { manager.addView(panel, params) }.isFailure) { close(); return false }
        TrackerState.raiseGazeOverlay?.invoke()
        TrackerState.observe(observer)
        main.post(heartbeat)
        loadSavedConsent()
        return true
    }

    private fun updateTabs() {
        buttons["tab:candidates"]?.isSelected = !phrasesSelected
        buttons["tab:phrases"]?.isSelected = phrasesSelected
        listOf("tab:candidates", "tab:phrases").forEach { key ->
            buttons[key]?.let { it.setTextColor(if (it.isSelected) RecoveryUi.blue else RecoveryUi.ink) }
        }
    }

    private fun selectTab(phrases: Boolean) {
        phrasesSelected = phrases; settingsOpen = false; settings.visibility = View.GONE
        notice = null; dwell.screenChanged(); updateTabs()
        displayed = emptyList(); candidates.removeAllViews()
        scroll.scrollTo(0, 0); renderCandidates(); position()
    }

    private fun consentKey(policy: PredictionPolicy) = "native-editor-v1|${BuildConfig.PREDICTION_SERVER_URL}|${policy.id}"
    private fun loadSavedConsent() {
        if (!predictions.configured || prefs.getString("consent", "").isNullOrBlank()) return
        loadingPolicy = true
        predictions.loadPolicy { policy ->
            if (closed) return@loadPolicy
            loadingPolicy = false
            if (policy != null && prefs.getString("consent", "") == consentKey(policy)) {
                predictions.enable(policy); submitInput()
            }
            renderCandidates(); renderSettings()
        }
    }

    private fun toggleSettings() {
        settingsOpen = !settingsOpen
        settings.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        scroll.scrollTo(0, 0)
        if (settingsOpen && predictions.configured && predictions.policy == null && !loadingPolicy) {
            loadingPolicy = true
            predictions.loadPolicy {
                if (closed) return@loadPolicy
                loadingPolicy = false; renderSettings(); renderCandidates(); position()
            }
        }
        renderSettings(); position()
    }

    private fun renderSettings() {
        settingsButton.text = if (service.resources.configuration.fontScale >= 1.3f) "예측 설정"
            else if (predictions.active) "예측 설정 · 켜짐" else "예측 설정 · 꺼짐"
        settingsButton.contentDescription = "온라인 예측 설정, " + if (predictions.active) "켜짐" else "꺼짐"
        settings.removeAllViews()
        buttons.keys.filter { it.startsWith("setting:") }.toList().forEach(buttons::remove)
        if (!settingsOpen) return
        val policy = predictions.policy
        when {
            !predictions.configured -> settings.addView(text("예측 서버가 설정되지 않았어요. 키보드와 자주 쓰는 문장은 사용할 수 있어요.", 14f))
            loadingPolicy -> settings.addView(text("온라인 예측 설정을 불러오는 중…", 14f))
            policy == null -> {
                settings.addView(text(predictions.message, 14f))
                val retry = row()
                button("setting:retry", "다시 불러오기", retry) { settingsOpen = false; toggleSettings() }
                settings.addView(retry)
            }
            else -> {
                settings.addView(text("선택한 메시지 입력칸의 작성 내용만 ${policy.provider}에 전송합니다. 대화 내역은 보내지 않습니다.\n모델: ${policy.model}\n${policy.notice}", 14f))
                val actions = row()
                if (predictions.active) button("setting:disable", "온라인 예측 끄기", actions) {
                    prefs.edit().remove("consent").apply(); predictions.stop()
                    renderSettings(); renderCandidates(); position()
                } else button("setting:allow", "동의하고 예측 켜기", actions) {
                    if (prefs.edit().putString("consent", consentKey(policy)).commit()) {
                        predictions.enable(policy); submitInput()
                        renderSettings(); renderCandidates(); position()
                    } else showNotice("설정을 저장하지 못했어요. 다시 시도해 주세요.")
                }
                settings.addView(actions)
            }
        }
        val fold = row()
        button("setting:fold", "설정 접기", fold) { toggleSettings() }
        settings.addView(fold)
    }

    private fun showNotice(message: String) {
        notice = message; noticeUntil = SystemClock.uptimeMillis() + 3_000
        renderCandidates()
    }

    private fun syncEditor() {
        if (inFlight || target.invalid) return
        val current = target.current() ?: run { close(); return }
        if (!last.unchanged(current)) {
            // Native typing and selection changes are expected; the actual node stays bound.
            last = current; target.expected = current; version++
            dwell.screenChanged(); submitInput()
        }
    }
    private fun submitInput() {
        val atEnd = last.validSelection && last.start == last.text.length && last.end == last.text.length
        if (!loadingPolicy) predictions.input(PredictionInput(version, if (atEnd) last.text else ""))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderCandidates() {
        if (closed) return
        status.text = when {
            notice != null -> notice
            target.invalid -> "메시지 입력칸에서 결과를 확인한 뒤 다시 열어 주세요."
            phrasesSelected -> ""
            !last.validSelection || last.start != last.text.length || last.end != last.text.length -> "문장 끝에 커서를 두면 후보가 표시됩니다."
            !predictions.active -> "온라인 예측을 켜면 단어와 이어쓰기를 추천합니다."
            last.text.isEmpty() -> "키보드로 내용을 입력해 주세요."
            else -> predictions.message
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        if (phrasesSelected) {
            if (candidates.childCount == 0) {
                buttons.keys.filter { it.startsWith("candidate:") || it.startsWith("phrase:") }.toList().forEach(buttons::remove)
                store.phrases().chunked(2).forEachIndexed { rowIndex, phrases ->
                    val line = row()
                    phrases.forEachIndexed { column, phrase ->
                        button("phrase:${rowIndex * 2 + column}", phrase, line) { insertPhrase(phrase) }
                    }
                    candidates.addView(line)
                }
            }
            buttons.filterKeys { it.startsWith("phrase:") }.values.forEach { it.isEnabled = !inFlight && !target.invalid }
            return
        }
        val items = predictions.candidates.filter { it.version == version && last.candidatePlan(it) != null }
        if (items != displayed || candidates.childCount == 0) {
            displayed = items
            buttons.keys.filter { it.startsWith("candidate:") || it.startsWith("phrase:") }.toList().forEach(buttons::remove)
            candidates.removeAllViews()
            if (items.isEmpty()) return
            val words = row()
            items.forEachIndexed { index, candidate ->
                val parent = if (candidate.kind == CandidateKind.WORD) words else row()
                val key = "candidate:$index"
                button(key, candidate.replacement, parent) { apply(candidate) }.apply {
                    textSize = 18f
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) heldByTouch = true
                        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) heldByTouch = false
                        predictions.hold(heldByTouch || heldByGaze)
                        false
                    }
                }
                if (candidate.kind == CandidateKind.CONTINUATION) {
                    if (words.parent == null && words.childCount > 0) candidates.addView(words)
                    candidates.addView(parent)
                }
            }
            if (words.parent == null && words.childCount > 0) candidates.addView(words, 0)
        }
        buttons.filterKeys { it.startsWith("candidate:") }.values.forEach { it.isEnabled = !inFlight && !target.invalid }
    }

    private fun insertPhrase(phrase: String) {
        if (inFlight || target.invalid) return
        syncEditor()
        if (closed || target.invalid) return
        inFlight = true; renderCandidates()
        target.insert(phrase, ::finishWrite)
    }

    private fun apply(candidate: WritingCandidate) {
        if (inFlight || target.invalid) return
        syncEditor()
        if (closed || candidate.version != version || last.candidatePlan(candidate) == null) return
        inFlight = true; renderCandidates()
        target.applyCandidate(candidate, ::finishWrite)
    }

    private fun finishWrite(result: WriteResult) {
        if (closed) return
        inFlight = false
        when (result) {
            WriteResult.CONFIRMED -> {
                last = target.expected; version++; submitInput()
                showNotice("입력칸에 추가했어요.")
            }
            WriteResult.REJECTED -> {
                syncEditor()
                showNotice("입력 내용이 바뀌었어요. 입력칸을 확인하고 다시 선택해 주세요.")
            }
            else -> {
                predictions.stop()
                showNotice("메시지 입력칸에서 결과를 확인해 주세요. 닫은 뒤 다시 열 수 있어요.")
            }
        }
        position()
    }

    @Suppress("DEPRECATION")
    private fun position() {
        if (!panel.isAttachedToWindow) return
        val windows = service.windows
        val keyboard = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .map { Rect().also(it::getBoundsInScreen) }.filter { !it.isEmpty }.minByOrNull { it.top }
        } finally { windows.forEach { it.recycle() } }
        if (keyboard == null) { close(); return }
        val screen = DisplaySpace.bounds(service)
        val editorTop = target.protectedBounds(keyboard.top).top
        val topInset = if (android.os.Build.VERSION.SDK_INT >= 30)
            manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()).top
            else dp(24)
        val width = screen.width() - dp(16)
        content.measure(View.MeasureSpec.makeMeasureSpec(width - dp(24), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val headerWidth = View.MeasureSpec.makeMeasureSpec(width - dp(24), View.MeasureSpec.EXACTLY)
        val unlimited = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        panel.getChildAt(0).measure(headerWidth, unlimited)
        val fixedHeight = panel.getChildAt(0).measuredHeight + dp(16)
        val desired = (content.measuredHeight + fixedHeight).coerceAtMost(dp(380).coerceAtLeast(fixedHeight + dp(64)))
        val placement = NativePanelPlacement.aboveEditor(keyboard.top, editorTop, topInset,
            desired, dp(12), fixedHeight + dp(48)) ?: run {
            Toast.makeText(service, "입력칸 위 공간이 부족해요. 키보드 안내를 닫은 뒤 다시 열어 주세요.", Toast.LENGTH_LONG).show()
            close(); return
        }
        val height = placement.height
        val params = panel.layoutParams as WindowManager.LayoutParams
        val windowY = placement.top - windowOffsetY
        if (params.width != width || params.height != height || params.y != windowY) {
            params.width = width; params.height = height; params.y = windowY
            panel.visibility = View.INVISIBLE
            manager.updateViewLayout(panel, params)
            return
        }
        // WindowManager can offset a window for system/IME insets. Compare real screen
        // coordinates, not only LayoutParams.y, before exposing it over another app.
        val actual = IntArray(2)
        panel.getLocationOnScreen(actual)
        if (panel.height == height && actual[1] != placement.top) {
            windowOffsetY += actual[1] - placement.top
            params.y = placement.top - windowOffsetY
            manager.updateViewLayout(panel, params)
            panel.visibility = View.INVISIBLE
            return
        }
        panel.visibility = if (panel.height == height && actual[1] + height <= editorTop - dp(12)) View.VISIBLE else View.INVISIBLE
    }

    private fun screenBounds(view: View): Rect? {
        if (!view.isShown) return null
        val bounds = Rect()
        if (!view.getLocalVisibleRect(bounds)) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        bounds.offset(location[0], location[1])
        return bounds
    }

    private fun onGaze() {
        if (closed) return
        val now = SystemClock.uptimeMillis()
        val gaze = TrackerState.gaze
        val valid = TrackerState.running && TrackerState.controls && !TrackerState.collecting &&
            !TrackerState.helpVisible && !TrackerState.recoveryOpening && !TrackerState.speaking &&
            TrackerState.fault == null && !TrackerState.signalPending && gaze?.fresh(now) == true
        val display = DisplaySpace.bounds(service)
        val x = ((gaze?.x ?: 0.0) * display.width()).toInt()
        val y = ((gaze?.y ?: 0.0) * display.height()).toInt()
        fun inside(view: View): Boolean = screenBounds(view)?.contains(x, y) == true
        val owns = valid && inside(panel)
        // Outside the panel the real keyboard keeps normal gaze click handling.
        TrackerState.writingOwnsInput = owns
        if (!owns) blockedBounds = null
        val blocked = blockedBounds?.contains(x, y) == true
        val entry = if (!owns || blocked) null else buttons.entries.firstOrNull { it.value.isEnabled && inside(it.value) }
        heldByGaze = entry?.key?.startsWith("candidate:") == true
        predictions.hold(heldByGaze || heldByTouch)
        if (dwell.update(entry?.key, valid, gaze?.uptimeMillis ?: 0, now)) entry?.value?.performClick()
        buttons.forEach { (key, button) -> (button.foreground as? WritingFeedback)?.update(key == entry?.key, dwell.progress) }
        if (owns) TrackerState.localDwellProgress = dwell.progress
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (closed || event == null) return
        if (event.eventTime > openedAt && event.packageName?.toString() == last.packageName &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { close(); return }
        if (!inFlight) syncEditor()
    }
    fun close() {
        if (closed) return
        closed = true; main.removeCallbacksAndMessages(null); TrackerState.unobserve(observer)
        predictions.close(); target.close()
        if (panel.isAttachedToWindow) runCatching { manager.removeView(panel) }
        TrackerState.writingOwnsInput = false; TrackerState.localDwellProgress = 0f
        TrackerState.cancelActions?.invoke(); onClosed()
    }
}
