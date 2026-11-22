package dev.gazedot

import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast

/** Entry point only. Writing always stays in the messaging app's own editor and keyboard. */
internal class WritingController(private val service: ControlAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = service.getSystemService(WindowManager::class.java)
    private val dwell = WritingDwell()
    private var prompt: Button? = null
    private var nativePanel: NativeWritingPanel? = null
    private var promptPackage: String? = null
    private var closed = false
    private var openingEditor: android.view.accessibility.AccessibilityNodeInfo? = null
    private var openingCheck: Runnable? = null
    private val observer: () -> Unit = { onGaze() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            dwell.expire(SystemClock.uptimeMillis())
            refreshEntry()
            main.postDelayed(this, 250)
        }
    }

    init { TrackerState.observe(observer); main.post(heartbeat) }
    private fun dp(n: Int) = RecoveryUi.dp(service, n)

    private fun onGaze() {
        if (closed || nativePanel != null) return
        val entry = prompt ?: return
        val now = SystemClock.uptimeMillis()
        val gaze = TrackerState.gaze
        val valid = TrackerState.running && TrackerState.controls && !TrackerState.collecting &&
            !TrackerState.speaking && !TrackerState.helpVisible && !TrackerState.recoveryOpening &&
            TrackerState.fault == null && !TrackerState.signalPending && gaze?.fresh(now) == true
        val display = DisplaySpace.bounds(service)
        val bounds = Rect()
        val location = IntArray(2)
        entry.getLocationOnScreen(location)
        val visible = entry.getLocalVisibleRect(bounds)
        bounds.offset(location[0], location[1])
        val inside = valid && visible &&
            bounds.contains((gaze!!.x * display.width()).toInt(), (gaze.y * display.height()).toInt())
        val key = if (inside) "entry" else null
        TrackerState.writingOwnsInput = inside
        if (dwell.update(key, valid, gaze?.uptimeMillis ?: 0, now)) entry.performClick()
        (entry.foreground as? WritingFeedback)?.update(inside, dwell.progress)
        if (inside) TrackerState.localDwellProgress = dwell.progress
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (closed || event == null) return
        nativePanel?.let { it.onEvent(event); return }
        if (event.packageName?.toString() != service.packageName) main.post { refreshEntry() }
    }

    private fun refreshEntry() {
        if (nativePanel != null || closed) return
        if (TrackerState.collecting || TrackerState.helpVisible || TrackerState.recoveryOpening) {
            removePrompt(); return
        }
        val pkg = foregroundPackage()
        if (pkg == null || !MessagingEditors.supportsPackage(pkg)) { removePrompt(); return }
        if (promptPackage != pkg) removePrompt()
        promptPackage = pkg
        if (prompt != null) return
        val entry = RecoveryUi.button(service, "AI 텍스팅 열기") {
            dwell.selected("entry")
            TrackerState.cancelActions?.invoke()
            open()
        }.apply { textSize = 16f }
        entry.foreground = WritingFeedback(service.resources.displayMetrics.density)
        val params = WindowManager.LayoutParams(-2, -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12); y = dp(72)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(android.view.WindowInsets.Type.systemBars())
                setFitInsetsIgnoringVisibility(true)
            }
        }
        if (runCatching { manager.addView(entry, params) }.isSuccess) {
            prompt = entry; dwell.screenChanged(); TrackerState.raiseGazeOverlay?.invoke()
        }
    }

    @Suppress("DEPRECATION")
    private fun foregroundPackage(): String? {
        val active = service.rootInActiveWindow ?: return null
        return try { active.packageName?.toString() } finally { active.recycle() }
    }

    private fun removePrompt() {
        cancelOpening()
        prompt?.let { if (it.isAttachedToWindow) runCatching { manager.removeView(it) } }
        prompt = null; promptPackage = null
        if (nativePanel == null) TrackerState.writingOwnsInput = false
    }

    private fun open() {
        val pkg = foregroundPackage()
        if (openingEditor != null) return
        if (pkg == null || pkg != promptPackage || !MessagingEditors.supportsPackage(pkg)) {
            removePrompt(); return
        }
        var failure = "입력칸 연결 중 오류가 발생했어요."
        val editor = runCatching { WritingTarget.activateEditor(service, pkg) { failure = it } }.getOrNull()
            ?: run { Toast.makeText(service, failure, Toast.LENGTH_LONG).show(); return }
        openingEditor = editor
        prompt?.isEnabled = false
        prompt?.text = "입력칸 여는 중…"
        val deadline = SystemClock.uptimeMillis() + 2_000
        val check = object : Runnable {
            override fun run() {
                if (closed || openingEditor !== editor) return
                if (foregroundPackage() != pkg) { cancelOpening(); refreshEntry(); return }
                val target = runCatching { WritingTarget.capture(service) { failure = it } }.getOrNull()
                if (target != null) {
                    val same = target.expected.packageName == pkg &&
                        target.expected.windowId == editor.windowId && target.expected.nodeKey == editor.hashCode()
                    if (same && WritingTarget.keyboardVisible(service)) {
                        cancelOpening()
                        showPanel(target)
                        return
                    }
                    if (same) failure = "키보드가 열리지 않았어요. 메시지 입력칸을 눌러 다시 시도해 주세요."
                    target.close()
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    cancelOpening()
                    Toast.makeText(service, failure, Toast.LENGTH_LONG).show()
                } else main.postDelayed(this, 75)
            }
        }
        openingCheck = check
        main.postDelayed(check, 100)
    }

    private fun cancelOpening() {
        openingCheck?.let { main.removeCallbacks(it) }
        openingCheck = null
        openingEditor?.recycle(); openingEditor = null
        prompt?.isEnabled = true
        prompt?.text = "AI 텍스팅 열기"
    }

    private fun showPanel(target: WritingTarget) {
        removePrompt()
        val panel = NativeWritingPanel(service, target) {
            nativePanel = null; dwell.screenChanged()
            // Touch can reopen immediately; no gaze-dependent exit latch.
            main.post { refreshEntry() }
        }
        nativePanel = panel
        panel.show()
    }

    fun hide() {
        nativePanel?.close(); nativePanel = null
        removePrompt(); dwell.screenChanged()
        TrackerState.writingOwnsInput = false; TrackerState.localDwellProgress = 0f
    }

    fun close() {
        closed = true; hide()
        main.removeCallbacksAndMessages(null); TrackerState.unobserve(observer)
    }
}
