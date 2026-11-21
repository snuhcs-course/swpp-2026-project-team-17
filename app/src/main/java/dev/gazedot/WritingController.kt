package dev.gazedot

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.*

/** Local, touchable accessibility windows. No gesture dispatch, Enter, Send or IME action. */
internal class WritingController(private val service: ControlAccessibilityService) {
    private enum class Screen { MODE, PHRASES, SLOTS, CATEGORIES, EDIT, DIRECT, CONSENT, RESTORE }
    private val main = Handler(Looper.getMainLooper())
    private val manager = service.getSystemService(WindowManager::class.java)
    private val store = WritingStore(service)
    private val dwell = WritingDwell()
    private val controls = linkedMapOf<String, Button>()
    private val predictions = WritingPredictions { refreshCandidates() }
    private var prompt: Button? = null
    private var root: LinearLayout? = null
    private var body: LinearLayout? = null
    private var contentScroll: ScrollView? = null
    private var footer: LinearLayout? = null
    private var draftLabel: TextView? = null
    private var statusLabel: TextView? = null
    private var predictionLabel: TextView? = null
    private var candidateColumn: LinearLayout? = null
    private var target: WritingTarget? = null
    private var draft = WritingDraft()
    private var editing = WritingDraft()
    private var editSlot = 0
    private var screen = Screen.MODE
    private var status = ""
    private var uncertain = false
    private var inFlight = false
    private var openedAt = 0L
    private var lastProbe = 0L
    private var promptSnapshot: EditorSnapshot? = null
    private var promptPackage: String? = null
    private var exitBounds: Rect? = null
    private var clickedBounds: Rect? = null
    private var candidateGaze = false
    private var candidateTouch = false
    private var lastWriteAt = -2_000L
    private var lastWriteCompletedAt = -2_000L
    private var memoryDraft: String? = null
    private var memoryUncertain = false
    private var closed = false
    private val observer: () -> Unit = { onGaze() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            val now = SystemClock.uptimeMillis()
            dwell.expire(now)
            if (root != null) {
                TrackerState.localDwellProgress = dwell.progress
                if (!inFlight && target?.invalid == false && target?.unchanged() != true) invalidateTarget()
            } else refreshEntry()
            main.postDelayed(this, 250)
        }
    }

    init { TrackerState.observe(observer); main.post(heartbeat) }
    private fun dp(n: Int) = RecoveryUi.dp(service, n)
    private fun column() = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float = 18f) = RecoveryUi.text(service, value, size)
    private fun appName(pkg: String) = when (pkg) {
        "com.kakao.talk" -> "카카오톡"
        "com.samsung.android.messaging" -> "삼성 메시지"
        else -> "Google 메시지"
    }
    private fun params(full: Boolean) = WindowManager.LayoutParams(
        if (full) -1 else -2, if (full) -1 else -2,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = if (full) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.END
        if (!full) { x = dp(12); y = dp(72) }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(android.view.WindowInsets.Type.systemBars())
            setFitInsetsIgnoringVisibility(true)
        }
    }
    private fun button(key: String, label: String, parent: LinearLayout, action: () -> Unit): Button {
        lateinit var button: Button
        button = RecoveryUi.button(service, label) {
            val bounds = Rect()
            clickedBounds = if (button.getGlobalVisibleRect(bounds)) bounds else null
            dwell.selected(key)
            TrackerState.cancelActions?.invoke()
            action()
        }
        button.foreground = WritingFeedback(service.resources.displayMetrics.density)
        controls[key] = button
        parent.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return button
    }
    private fun contains(view: View, gaze: ScreenGaze): Boolean {
        val rect = Rect()
        val display = DisplaySpace.bounds(service)
        return view.isShown && view.getGlobalVisibleRect(rect) && rect.contains((gaze.x * display.width()).toInt(), (gaze.y * display.height()).toInt())
    }
    private fun validGaze(now: Long) = TrackerState.running && TrackerState.controls && !TrackerState.collecting &&
        !TrackerState.speaking && !TrackerState.helpVisible && !TrackerState.recoveryOpening &&
        TrackerState.fault == null && !TrackerState.signalPending && TrackerState.gaze?.fresh(now) == true

    private fun onGaze() {
        if (closed) return
        val now = SystemClock.uptimeMillis()
        val gaze = TrackerState.gaze
        val valid = validGaze(now)
        val leaving = exitBounds
        if (root == null && leaving != null) {
            if (valid) {
                val display = DisplaySpace.bounds(service)
                if (!leaving.contains((gaze!!.x * display.width()).toInt(), (gaze.y * display.height()).toInt())) {
                    exitBounds = null; TrackerState.writingOwnsInput = false; dwell.screenChanged()
                }
            }
            return
        }
        val entries = if (root != null) controls.toList() else prompt?.let { listOf("entry" to it) }.orEmpty()
        if (root != null) {
            // Keep candidate text frozen through a loss of tracking; release only after valid gaze leaves.
            if (valid) candidateGaze = entries.any { (key, view) -> key.startsWith("candidate:") && contains(view, gaze!!) }
            predictions.hold(candidateGaze || candidateTouch)
        }
        if (valid && clickedBounds != null) {
            val display = DisplaySpace.bounds(service)
            if (clickedBounds!!.contains((gaze!!.x * display.width()).toInt(), (gaze.y * display.height()).toInt())) {
                dwell.reset(); TrackerState.localDwellProgress = 0f
                entries.forEach { (_, view) -> (view.foreground as? WritingFeedback)?.update(false, 0f) }
                return
            }
            clickedBounds = null
        }
        val key = if (!valid) null else entries.firstOrNull { (_, view) -> view.isEnabled && contains(view, gaze!!) }?.first
        if (root == null) TrackerState.writingOwnsInput = key != null
        if (dwell.update(key, valid, gaze?.uptimeMillis ?: 0, now) && key != null) entries.firstOrNull { it.first == key }?.second?.performClick()
        entries.forEach { (id, view) -> (view.foreground as? WritingFeedback)?.update(id == key, dwell.progress) }
        if (root != null || key != null) TrackerState.localDwellProgress = dwell.progress
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (event == null || closed) return
        if (root != null && event.eventTime > openedAt && event.packageName?.toString() == target?.expected?.packageName &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) invalidateTarget()
        // A reused activity/editor and an identical room title cannot establish room identity.
        // Fail closed on external content changes, including incoming messages, until device adapters can narrow this safely.
        if (root != null && event.eventTime > openedAt && event.packageName?.toString() == target?.expected?.packageName &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !inFlight &&
            event.eventTime !in lastWriteAt..lastWriteCompletedAt) invalidateTarget()
        if (root == null && event.packageName?.toString() != service.packageName) main.post { refreshEntry() }
    }
    private fun refreshEntry() {
        if (root != null || closed || exitBounds != null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastProbe < 300) return
        lastProbe = now
        if (TrackerState.collecting || TrackerState.helpVisible || TrackerState.recoveryOpening) { removePrompt(); return }
        val packageName = foregroundPackage()
        if (packageName == null || !MessagingEditors.supportsPackage(packageName)) { removePrompt(); return }
        val found = runCatching { WritingTarget.capture(service) }.getOrNull()
        val snapshot = found?.expected
        found?.close()
        if (promptPackage != packageName ||
            (snapshot != null && promptSnapshot?.sameTarget(snapshot) == false)) {
            removePrompt(); dwell.screenChanged()
        }
        // Discovery depends on the app, while binding/writing still requires a verified editor.
        // A missing editor, header or input focus must not silently hide the entire feature.
        promptPackage = packageName
        promptSnapshot = snapshot
        if (prompt != null) return
        val entry = RecoveryUi.button(service, "AI 텍스팅 · NoTouch로 작성") { open() }
        entry.foreground = WritingFeedback(service.resources.displayMetrics.density)
        if (runCatching { manager.addView(entry, params(false)) }.isSuccess) {
            prompt = entry; dwell.screenChanged()
        }
    }
    @Suppress("DEPRECATION")
    private fun foregroundPackage(): String? {
        val active = service.rootInActiveWindow ?: return null
        return try { active.packageName?.toString() } finally { active.recycle() }
    }
    private fun removePrompt() {
        prompt?.let { if (it.isAttachedToWindow) runCatching { manager.removeView(it) } }
        prompt = null; promptSnapshot = null; promptPackage = null
        if (root == null && exitBounds == null) TrackerState.writingOwnsInput = false
    }
    private fun open() {
        val packageName = foregroundPackage()
        if (packageName == null || packageName != promptPackage || !MessagingEditors.supportsPackage(packageName)) {
            removePrompt(); return
        }
        val captured = runCatching { WritingTarget.capture(service) }.getOrNull() ?: run {
            Toast.makeText(service,
                "대화방의 메시지 입력칸을 누른 뒤 다시 선택해 주세요. 이미 눌렀다면 현재 화면의 입력칸을 인식하지 못한 상태입니다.",
                Toast.LENGTH_LONG).show()
            return
        }
        if (captured.expected.packageName != packageName ||
            (promptSnapshot != null && promptSnapshot?.unchanged(captured.expected) != true)) {
            captured.close(); removePrompt(); Toast.makeText(service, "입력칸이 바뀌었어요. 다시 선택해 주세요.", Toast.LENGTH_SHORT).show(); return
        }
        removePrompt(); target = captured
        draft = WritingDraft(memoryDraft ?: store.savedDraft)
        uncertain = if (memoryDraft != null) memoryUncertain else store.uncertain
        inFlight = false; openedAt = SystemClock.uptimeMillis()
        status = "${appName(captured.expected.packageName)} 입력칸에 추가합니다. 메시지는 직접 전송해 주세요."
        TrackerState.writingOwnsInput = true
        val window = column().apply {
            setBackgroundColor(Color.WHITE); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root = window
        if (runCatching { manager.addView(window, params(true)) }.isFailure) {
            root = null; captured.close(); target = null; TrackerState.writingOwnsInput = false; return
        }
        navigate(if (draft.text.isNotEmpty()) Screen.RESTORE else Screen.MODE)
    }
    private fun navigate(next: Screen) {
        if (screen == Screen.DIRECT && next != Screen.DIRECT && next != Screen.CONSENT) predictions.stop()
        screen = next; dwell.screenChanged(); candidateGaze = false; candidateTouch = false; predictions.hold(false)
        val window = root ?: return
        window.removeAllViews(); controls.clear(); candidateColumn = null; predictionLabel = null; draftLabel = null
        val largeFont = service.resources.configuration.fontScale >= 1.3f
        val header = LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = if (largeFont) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val off = button("off", "NoTouch 작성 끄기", header) { hide() }.apply { textSize = 16f; minHeight = dp(48) }
        off.layoutParams = LinearLayout.LayoutParams(if (largeFont) -1 else -2, -2)
        if (next != Screen.MODE && next != Screen.RESTORE) {
            button("back", "이전 화면", header) {
                navigate(if (screen in listOf(Screen.EDIT, Screen.CATEGORIES, Screen.SLOTS)) Screen.PHRASES else Screen.MODE)
            }.layoutParams = if (largeFont) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)
        }
        window.addView(header)
        body = column()
        statusLabel = text(status, 16f).also { body!!.addView(it) }
        contentScroll = ScrollView(service).apply { isFillViewport = true; addView(body) }
        window.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = column().also { window.addView(it) }
        val body = body!!
        when (next) {
            Screen.MODE -> {
                body.addView(text("어떻게 작성할까요?", 26f))
                button("phrases", "자주 쓰는 문장 선택", body) { navigate(Screen.PHRASES) }.minHeight = dp(100)
                button("direct", "직접 입력하기", body) { navigate(Screen.DIRECT); resumePredictions() }.minHeight = dp(100)
                if (draft.text.isNotEmpty()) body.addView(text("보관 중인 초안이 있어요. 직접 입력에서 이어 쓸 수 있어요."))
            }
            Screen.PHRASES -> {
                body.addView(text("자주 쓰는 문장", 26f))
                phraseGrid(body, false)
                body.addView(text("‘도와주세요.’는 문구만 추가합니다. 긴급 신고나 자동 연락은 하지 않습니다.", 16f))
                button("edit-phrases", "문장 바꾸기", footer!!) { navigate(Screen.SLOTS) }
            }
            Screen.SLOTS -> {
                body.addView(text("교체할 문장을 선택해 주세요", 24f)); phraseGrid(body, true)
                button("cancel-edit", "편집 취소", footer!!) { navigate(Screen.PHRASES) }
            }
            Screen.CATEGORIES -> {
                body.addView(text("어떤 문장으로 바꿀까요?", 24f))
                WritingPhrases.categories.forEach { (category, phrases) ->
                    button("category:$category", category, body) {
                        showPreparedPhrases(category, phrases)
                    }
                }
                button("custom", "직접 작성", body) { editing = WritingDraft(); navigate(Screen.EDIT) }
                button("cancel-edit", "편집 취소", footer!!) { navigate(Screen.PHRASES) }
            }
            Screen.EDIT -> {
                body.addView(text("바꿀 문장을 작성해 주세요", 24f)); addEditor(body, editing, true)
                button("save-phrase", "저장", footer!!) {
                    if (store.savePhrase(editSlot, editing.text)) { status = "문장을 저장했어요."; navigate(Screen.PHRASES) }
                    else setStatus("1~200자의 문장을 입력해 주세요. 저장하지 못하면 기존 문장을 유지합니다.")
                }
                button("cancel-edit", "편집 취소", footer!!) { navigate(Screen.PHRASES) }
            }
            Screen.DIRECT -> {
                body.addView(text("직접 입력하기", 24f))
                body.addView(text("기존 내용은 유지하고, 작성한 초안만 선택 위치 뒤에 추가합니다.", 16f))
                button("online", "온라인 예측 설정", body) { showOnlineSettings() }
                addEditor(body, draft, false)
                button("apply", "입력칸에 추가", footer!!) { write(draft.text, true) }
                refreshCandidates()
            }
            Screen.CONSENT -> showConsent(body)
            Screen.RESTORE -> {
                body.addView(text("보관한 초안이 있어요", 26f))
                body.addView(text(if (uncertain) "이전에 입력칸에 추가되었는지 확인하지 못한 내용입니다. 원래 앱의 메시지를 먼저 확인해 중복 추가를 피해 주세요."
                    else "초안은 보관되어 있어요. 현재 선택한 ${appName(capturedPackage())} 입력칸에서 이어 쓸지 선택해 주세요."))
                body.addView(text(draft.text, 22f))
                button("restore", if (uncertain) "입력칸을 확인했어요 · 초안 이어쓰기" else "이 입력칸에서 초안 이어쓰기", body) {
                    uncertain = false
                    if (!persist(draft.text)) setStatus("기기 저장이 되지 않아 초안을 메모리에 보관하고 있어요.")
                    navigate(Screen.MODE)
                }
                button("later", "나중에 작성 · 원래 앱으로", body) { hide() }
            }
        }
        if (next != Screen.MODE) {
            val scroll = LinearLayout(service)
            button("page-up", "위로 보기", scroll) { contentScroll?.smoothScrollBy(0, -(contentScroll!!.height * .65).toInt()) }
                .layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            button("page-down", "아래로 보기", scroll) { contentScroll?.smoothScrollBy(0, (contentScroll!!.height * .65).toInt()) }
                .layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            footer!!.addView(scroll)
        }
    }
    private fun capturedPackage() = target?.expected?.packageName.orEmpty()
    private fun phraseGrid(parent: LinearLayout, edit: Boolean) {
        val phrases = store.phrases()
        repeat(3) { row ->
            val line = LinearLayout(service)
            repeat(2) { col ->
                val index = row * 2 + col
                button("phrase:$index", phrases[index], line) {
                    if (edit) { editSlot = index; navigate(Screen.CATEGORIES) } else write(phrases[index], false)
                }.apply {
                    minHeight = dp(104); textSize = 22f
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
                }
            }
            parent.addView(line)
        }
    }
    private fun showPreparedPhrases(category: String, phrases: List<String>) {
        navigate(Screen.CATEGORIES)
        val body = body ?: return
        body.removeAllViews()
        controls.keys.filter { it.startsWith("category:") || it == "custom" }.toList().forEach { controls.remove(it) }
        body.addView(text(category, 24f))
        phrases.forEachIndexed { i, phrase ->
            button("prepared:$i", phrase, body) { editing = WritingDraft(phrase); navigate(Screen.EDIT) }
        }
        button("custom", "직접 작성", body) { editing = WritingDraft(); navigate(Screen.EDIT) }
        dwell.screenChanged()
    }
    private fun addEditor(parent: LinearLayout, buffer: WritingDraft, editingPhrase: Boolean) {
        draftLabel = text(buffer.text.ifEmpty { "작성한 내용이 여기에 표시됩니다" }, 24f).also {
            it.contentDescription = "작성 내용"; parent.addView(it)
        }
        if (!editingPhrase) {
            predictionLabel = text(predictions.message, 16f).also { parent.addView(it) }
            candidateColumn = column().also { parent.addView(it) }
        }
        // A single flat list: no jamo-group selection or grouped keyboard screens.
        val letters = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ"
        val availableDp = DisplaySpace.bounds(service).width() / service.resources.displayMetrics.density - 32
        val columns = (availableDp / (64 * service.resources.configuration.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 4)
        letters.chunked(columns).forEach { row ->
            val line = LinearLayout(service)
            row.forEach { c ->
                button("jamo:$c", c.toString(), line) { edit(buffer, editingPhrase) { jamo(c) } }.apply {
                    textSize = 28f; minHeight = dp(68)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
                }
            }
            parent.addView(line)
        }
        val punctuation = LinearLayout(service)
        listOf(".", ",", "?", "!").forEach { value ->
            button("punctuation:$value", value, punctuation) { edit(buffer, editingPhrase) { literal(value) } }
                .layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        parent.addView(punctuation)
        val actions = LinearLayout(service)
        button("delete", "지우기", actions) { edit(buffer, editingPhrase) { backspace() } }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        button("space", "띄어쓰기", actions) { edit(buffer, editingPhrase) { space() } }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        button("undo", "실행 취소", actions) { edit(buffer, editingPhrase) { undo() } }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        footer!!.addView(actions)
    }
    private fun edit(buffer: WritingDraft, editingPhrase: Boolean, action: WritingDraft.() -> Unit) {
        if (inFlight) return
        buffer.action()
        draftLabel?.text = buffer.text.ifEmpty { "작성한 내용이 여기에 표시됩니다" }
        if (!editingPhrase) {
            if (!persist(draft.text, uncertain)) setStatus("기기 저장 실패 · 초안은 NoTouch 메모리에 보관 중입니다. 앱을 종료하기 전에 내용을 확인해 주세요.")
            predictions.input(PredictionInput(draft.version, draft.text))
        }
    }
    @SuppressLint("ClickableViewAccessibility") // Returning false leaves Button's normal accessible performClick intact.
    private fun refreshCandidates() {
        if (screen != Screen.DIRECT || root == null) return
        predictionLabel?.text = predictions.message
        val column = candidateColumn ?: return
        val items = predictions.candidates
        // Never rebuild a held slot. The coordinator also defers new results until gaze/touch leaves.
        val keys = items.mapIndexed { index, c -> "candidate:$index:${c.version}:${c.replacement}" }
        if (column.tag != keys) {
            controls.keys.filter { it.startsWith("candidate:") }.toList().forEach { controls.remove(it) }
            column.removeAllViews(); column.tag = keys
            repeat(3) { slot ->
                val candidate = items.filter { it.kind == if (slot < 2) CandidateKind.WORD else CandidateKind.CONTINUATION }
                    .getOrNull(if (slot < 2) slot else 0)
                val label = candidate?.let { (if (it.kind == CandidateKind.WORD) "단어: " else "이어쓰기: ") + it.replacement } ?: "후보 없음"
                val key = candidate?.let { "candidate:$slot:${it.version}:${it.replacement}" } ?: "candidate:empty:$slot"
                button(key, label, column) {
                    if (candidate != null && draft.apply(candidate)) {
                        draftLabel?.text = draft.text
                        if (!persist(draft.text, uncertain)) setStatus("기기 저장 실패 · 초안은 메모리에 보관 중입니다.")
                        predictions.input(PredictionInput(draft.version, draft.text))
                    }
                }.apply {
                    tag = candidate
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { candidateTouch = true; predictions.hold(true) }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.post {
                                candidateTouch = false; predictions.hold(candidateGaze)
                            }
                        }
                        false
                    }
                }
            }
        }
        for (i in 0 until column.childCount) {
            val button = column.getChildAt(i) as Button
            button.isEnabled = !inFlight && (button.tag as? WritingCandidate)?.let { draft.accepts(it) } == true
            button.alpha = if (button.isEnabled) 1f else .5f
        }
    }
    private fun resumePredictions() {
        if (predictions.active || !predictions.configured || store.consent.isBlank()) return
        predictions.loadPolicy { policy ->
            if (root != null && screen == Screen.DIRECT && policy != null && store.consent == consentKey(policy)) {
                predictions.enable(policy); predictions.input(PredictionInput(draft.version, draft.text))
            }
        }
    }
    private fun consentKey(policy: PredictionPolicy) = BuildConfig.PREDICTION_SERVER_URL + "|" + policy.id
    private fun showOnlineSettings() {
        if (!predictions.configured) { setStatus("AI 모델과 서버가 아직 설정되지 않았어요. 직접 입력은 계속할 수 있어요."); return }
        setStatus("온라인 예측 안내를 불러오고 있어요…")
        predictions.loadPolicy { policy -> if (root != null) { if (policy == null) setStatus(predictions.message) else navigate(Screen.CONSENT) } }
    }
    private fun showConsent(parent: LinearLayout) {
        val policy = predictions.policy ?: return
        parent.addView(text("온라인 예측을 사용할까요?", 24f))
        parent.addView(text("작성 중인 NoTouch 초안만 문장·단어 예측을 위해 중계 서버와 ${policy.provider}에 전송합니다. 상대방 메시지, 대화 내역, 카메라 영상과 시선 좌표는 보내지 않습니다."))
        parent.addView(text("제공자: ${policy.provider}\n모델: ${policy.model}\n${policy.notice}"))
        button("consent-yes", "안내를 확인했어요 · 온라인 예측 사용", parent) {
            if (store.consent(consentKey(policy))) {
                predictions.enable(policy); navigate(Screen.DIRECT)
                predictions.input(PredictionInput(draft.version, draft.text))
            } else {
                predictions.stop(); setStatus("동의 설정을 저장하지 못해 온라인 예측을 켜지 않았어요."); navigate(Screen.DIRECT)
            }
        }
        button("consent-no", "온라인 예측 없이 작성", parent) {
            val saved = store.consent("")
            predictions.stop()
            if (!saved) setStatus("온라인 예측은 껐지만 설정 저장이 되지 않았어요.")
            navigate(Screen.DIRECT)
        }
    }
    private fun invalidateTarget() {
        target?.invalidate()
        setStatus("입력칸이나 대화 화면이 바뀌었어요. 초안은 보관합니다. NoTouch 작성을 끄고 원하는 입력칸에서 다시 시작해 주세요.")
    }
    private fun setStatus(value: String) { status = value; statusLabel?.text = value }
    private fun persist(text: String, uncertain: Boolean = false): Boolean {
        memoryDraft = text; memoryUncertain = uncertain
        return store.saveDraft(text, uncertain)
    }
    private fun write(addition: String, fromDraft: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (inFlight || now - lastWriteAt < 1_000 || addition.isEmpty()) return
        if (uncertain) { setStatus("이전 반영 결과를 먼저 확인해 주세요. 초안은 보관되어 있어요."); return }
        val target = target ?: return
        if (!target.unchanged()) {
            if (!fromDraft) draft.literal(addition)
            persist(draft.text, uncertain)
            invalidateTarget(); return
        }
        val reserved = if (fromDraft) draft.text else draft.text + addition
        if (!persist(reserved, true)) {
            if (!fromDraft) draft.literal(addition)
            memoryDraft = draft.text; memoryUncertain = uncertain
            setStatus("초안을 기기에 보관하지 못해 추가하지 않았어요. 내용은 메모리에 보관합니다."); return
        }
        inFlight = true; lastWriteAt = now
        predictions.stop(); refreshCandidates(); setStatus("입력칸에 추가한 내용을 확인하고 있어요…")
        target.insert(addition) { result ->
            inFlight = false
            lastWriteCompletedAt = SystemClock.uptimeMillis()
            var saved = true
            when (result) {
                WriteResult.CONFIRMED, WriteResult.TEXT_CONFIRMED -> {
                    if (fromDraft) draft.accepted()
                    uncertain = false; saved = persist(draft.text)
                    setStatus(if (result == WriteResult.CONFIRMED) "입력칸에 추가했어요. 메시지는 직접 전송해 주세요."
                        else "글은 추가됐지만 커서를 확인하지 못했어요. 원래 앱에서 확인해 주세요.")
                }
                WriteResult.REJECTED, WriteResult.UNCERTAIN -> {
                    if (!fromDraft) draft.literal(addition)
                    uncertain = result == WriteResult.UNCERTAIN
                    saved = persist(draft.text, uncertain)
                    setStatus(if (uncertain) "반영 결과를 확인하지 못했어요. 초안은 보관했습니다. 원래 입력칸을 확인한 뒤 이어 써 주세요."
                        else "입력칸을 확인하지 못해 추가를 중단했어요. 초안은 보관했습니다.")
                }
            }
            if (!saved) setStatus(status + " 기기 저장은 실패해 초안을 메모리에 보관 중입니다.")
            if (screen == Screen.DIRECT) draftLabel?.text = draft.text.ifEmpty { "작성한 내용이 여기에 표시됩니다" }
            refreshCandidates()
            if (screen == Screen.DIRECT && !uncertain && !target.invalid) resumePredictions()
        }
    }

    fun hide() {
        val wasOpen = root != null
        val rect = Rect()
        val off = controls["off"]
        exitBounds = if (wasOpen && off?.getGlobalVisibleRect(rect) == true) rect else null
        val saved = if (wasOpen && !inFlight) persist(draft.text, uncertain) else true
        predictions.stop(); dwell.screenChanged(); controls.clear(); clickedBounds = null
        root?.let { if (it.isAttachedToWindow) runCatching { manager.removeView(it) } }
        root = null; body = null; footer = null; candidateColumn = null
        target?.close(); target = null; removePrompt()
        TrackerState.localDwellProgress = 0f
        TrackerState.writingOwnsInput = exitBounds != null
        TrackerState.cancelActions?.invoke()
        if (wasOpen && (inFlight || uncertain || draft.text.isNotEmpty()))
            Toast.makeText(service, if (!saved) "기기 저장 실패 · 초안은 NoTouch 메모리에 보관 중입니다."
                else if (inFlight || uncertain) "반영 여부를 확인할 초안을 보관했어요." else "미반영 초안을 보관했어요.", Toast.LENGTH_LONG).show()
    }
    fun close() {
        hide(); closed = true; exitBounds = null
        main.removeCallbacksAndMessages(null); predictions.close(); TrackerState.unobserve(observer)
        TrackerState.writingOwnsInput = false
    }
}
