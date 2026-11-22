package dev.gazedot

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity

/** Ordinary, touch-accessible controls; gaze is optional and uses the existing dwell. */
class RecoveryActivity : ComponentActivity() {
    private lateinit var heading: TextView
    private lateinit var advice: TextView
    private lateinit var calibration: Button
    private lateinit var camera: Button
    private lateinit var resume: Button
    private lateinit var settings: Button
    private lateinit var calibrationHint: TextView
    private val buttons = mutableListOf<Button>()
    private val dwell = TutorialDwell()
    private var target: Button? = null
    private var selected: Button? = null
    private var lastUi: List<Any?>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val observer: () -> Unit = { update(); onGaze() }
    private val freshness = object : Runnable {
        override fun run() {
            dwell.expire(SystemClock.uptimeMillis())
            TrackerState.localDwellProgress = dwell.progress
            update()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = RecoveryUi.dp(this@RecoveryActivity, 24)
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        column.addView(RecoveryUi.text(this, "NoTouch 도움", 28f))
        heading = RecoveryUi.text(this, "", 22f).also { column.addView(it) }
        advice = RecoveryUi.text(this, "").also { column.addView(it) }
        column.addView(RecoveryUi.text(this,
            "눈이 인식되지 않으면 버튼을 터치해 주세요.\n카메라 가림을 없애고 얼굴을 정면에 맞추면, 버튼을 누르지 않아도 인식이 돌아올 수 있어요."))
        fun button(label: String, action: () -> Unit): Button {
            return RecoveryUi.button(this, label) {
                resetDwell()
                TrackerState.cancelActions?.invoke()
                action()
            }.also {
                buttons += it
                column.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = RecoveryUi.dp(this@RecoveryActivity, 12) })
            }
        }
        settings = button("관련 설정 열기") {
            val setting = if (ControlAccessibilityService.instance == null) RecoverySetting.ACCESSIBILITY else TrackerState.fault?.setting
            val intent = when (setting) {
                RecoverySetting.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                RecoverySetting.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        }
        camera = button("카메라 다시 시작") { openExistingFlow(MainActivity.RECOVERY_START) }
        calibrationHint = RecoveryUi.text(this, "").also { column.addView(it) }
        calibration = button(getString(R.string.recognition_help_recalibrate)) { openExistingFlow(MainActivity.RECOVERY_CALIBRATE) }
        resume = button(getString(R.string.recognition_help_resume)) {
            if (TrackerState.running) startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_RESUME))
        }
        button("안내 닫기 · 이전 화면으로") { finish() }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        })
        update()
    }

    override fun onStart() {
        super.onStart()
        TrackerState.settingsStarted()
        TrackerState.recoveryOpening = false
        TrackerState.contextChanged?.invoke()
    }
    override fun onResume() {
        super.onResume()
        TrackerState.observe(observer)
        update()
        handler.post(freshness)
    }
    override fun onPause() {
        TrackerState.unobserve(observer)
        handler.removeCallbacksAndMessages(null)
        resetDwell()
        TrackerState.cancelActions?.invoke()
        super.onPause()
    }
    override fun onStop() { TrackerState.settingsStopped(); super.onStop() }

    private fun update() {
        val now = SystemClock.uptimeMillis()
        val state = TrackerState.currentStatus(now)
        val canCalibrate = TrackerState.running && TrackerState.fault == null && TrackerState.hasFreshFeatures(now)
        val ui = listOf(state, canCalibrate, TrackerState.running, TrackerState.controls,
            TrackerState.fault?.setting, ControlAccessibilityService.instance != null)
        if (lastUi == ui) return
        lastUi = ui
        fun text(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
        text(heading, state.message)
        text(advice, state.advice)
        calibration.isEnabled = canCalibrate
        text(calibrationHint, if (canCalibrate)
            "시선 위치가 맞지 않으면 직접 다시 보정할 수 있어요. 자동으로 시작하지 않아요."
            else "다시 보정하려면 카메라가 실행 중이고 눈이 검출되어야 해요.")
        settings.visibility = if (TrackerState.fault != null || ControlAccessibilityService.instance == null) View.VISIBLE else View.GONE
        text(settings, when {
            ControlAccessibilityService.instance == null -> "접근성 설정 열기"
            TrackerState.fault?.setting == RecoverySetting.OVERLAY -> "다른 앱 위에 표시 설정"
            TrackerState.fault?.setting == RecoverySetting.CAMERA -> "카메라 권한 설정"
            else -> "앱 설정 열기"
        })
        // Resolve the same setting in the click handler, including a disconnected accessibility service.
        if (ControlAccessibilityService.instance == null && TrackerState.fault == null)
            text(advice, "NoTouch 눈 조작 접근성 서비스를 켜 주세요. 켜진 뒤 조작 재개를 선택해 주세요.")
        camera.visibility = if (!TrackerState.running || state.phase in listOf(TrackingPhase.ERROR, TrackingPhase.SIGNAL_PENDING)) View.VISIBLE else View.GONE
        resume.visibility = if (TrackerState.running && !TrackerState.controls) View.VISIBLE else View.GONE
        listOf(calibration, camera, resume, settings).forEach { it.alpha = if (it.isEnabled) 1f else .5f }
    }

    private fun openExistingFlow(action: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECOVERY_ACTION, action))
    }

    private fun resetDwell() {
        dwell.reset(); target = null
        TrackerState.localDwellProgress = 0f
    }

    private fun onGaze() {
        val now = SystemClock.uptimeMillis()
        val gaze = TrackerState.gaze
        val valid = TrackerState.running && TrackerState.controls && TrackerState.fault == null &&
            !TrackerState.collecting && gaze?.fresh(now) == true
        val bounds = DisplaySpace.bounds(this)
        val next = if (!valid) null else buttons.firstOrNull { button ->
            val visible = Rect()
            button.isShown && button.isEnabled && button.getGlobalVisibleRect(visible) &&
                visible.contains((gaze!!.x * bounds.width()).toInt(), (gaze.y * bounds.height()).toInt())
        }
        if (valid && next !== selected) selected = null
        if (next !== target) { dwell.reset(); target = next }
        val clicked = dwell.update(next != null && next !== selected, gaze?.uptimeMillis ?: 0L, now)
        TrackerState.localDwellProgress = dwell.progress
        if (clicked && next != null) { selected = next; resetDwell(); next.performClick() }
    }

    companion object {
        // A separate task for external entry lets Finish return to the previously used app.
        fun externalIntent(context: Context) = Intent(context, RecoveryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
