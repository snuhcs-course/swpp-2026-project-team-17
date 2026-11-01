package dev.gazedot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var cameraButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var stopButton: Button
    private var calibration: CalibrationView? = null
    private var tutorial: PostCalibrationTutorialView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val homeTargets = mutableListOf<View>()
    private val homeDwell = TutorialDwell()
    private var homeTarget: View? = null
    private var selectedHomeTarget: View? = null
    private val homeFreshness = object : Runnable {
        override fun run() {
            if (calibration == null && tutorial == null) {
                homeDwell.expire(SystemClock.uptimeMillis())
                TrackerState.localDwellProgress = homeDwell.progress
            }
            handler.postDelayed(this, 50)
        }
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startTracking()
        else status.text = "다른 앱 위에 표시 권한을 허용한 뒤 다시 시작하세요."
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startTracking()
        else { status.text = "시선 추적에는 카메라 권한이 필요합니다. 권한을 허용한 뒤 다시 시작하세요." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") run {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (TrackerState.model == null) TrackerState.model = ModelStore.load(this)
        showHome()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (calibration != null) {
                    calibration?.close()
                    calibration = null
                    TrackerState.cancelCalibration()
                    TrackerState.status = "보정 취소됨"
                    showHome()
                } else if (tutorial != null) {
                    showHome()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
    override fun onStart() {
        super.onStart()
        TrackerState.settingsStarted()
    }
    override fun onResume() {
        super.onResume()
        TrackerState.interactionAllowed = false
        TrackerState.cancelActions?.invoke()
        TrackerState.listener = {
            if (tutorial != null) {
                if (!TrackerState.running) showHome()
                else tutorial?.onFrame(TrackerState.sample, TrackerState.eyeState)
            } else if (calibration != null && !TrackerState.running) {
                calibration?.close()
                calibration = null
                TrackerState.cancelCalibration()
                showHome()
            } else if (calibration != null) calibration?.onFrame(TrackerState.sample)
            else if (::status.isInitialized) {
                status.text = TrackerState.status
                updateHomeButtons()
                onHomeGaze(TrackerState.gaze)
            }
        }
        if (calibration == null && tutorial == null) {
            status.text = TrackerState.status
            updateHomeButtons()
        }
        handler.removeCallbacks(homeFreshness)
        handler.post(homeFreshness)
    }
    override fun onPause() {
        handler.removeCallbacks(homeFreshness)
        homeDwell.reset()
        TrackerState.localDwellProgress = 0f
        TrackerState.listener = null
        super.onPause()
        if (tutorial != null) showHome()
        if (calibration != null) {
            calibration?.close()
            calibration = null
            TrackerState.cancelCalibration()
            TrackerState.status = "보정이 중단되었습니다. 다시 9점 보정을 시작하세요."
            showHome()
        }
    }
    override fun onStop() { TrackerState.settingsStopped(); super.onStop() }
    override fun onDestroy() {
        calibration?.close()
        tutorial?.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showHome() {
        tutorial?.close()
        tutorial = null
        TrackerState.cancelActions?.invoke()
        TrackerState.clearCoordinates()
        homeTargets.clear()
        homeDwell.reset()
        homeTarget = null
        selectedHomeTarget = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val density = resources.displayMetrics.density
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val screenHeightDp = resources.displayMetrics.heightPixels / density
        val scale = minOf(screenWidthDp / 390f, screenHeightDp / 780f).coerceIn(0.55f, 1f)
        fun dp(n: Int) = (n * density * scale).toInt()
        fun sp(n: Float) = n * scale
        val ink = Color.rgb(17, 18, 70)
        val blue = Color.rgb(6, 108, 236)
        val pale = Color.rgb(230, 230, 253)
        val font = resources.getFont(R.font.geologica)
        fun style(view: TextView, weight: Int) {
            view.typeface = font
            view.fontVariationSettings = "'wght' $weight"
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -2))

        val logo = TextView(this).apply {
            text = "NoTouch"; textSize = sp(30f); setTextColor(ink); gravity = Gravity.CENTER
            style(this, 800)
        }
        content.addView(logo, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(104) })
        status = TextView(this).apply {
            text = if (TrackerState.status.startsWith("시작 전")) "" else TrackerState.status
            textSize = sp(12f); setTextColor(ink); gravity = Gravity.CENTER
            maxLines = 2; style(this, 500)
        }
        content.addView(status, LinearLayout.LayoutParams(-1, dp(28)))

        fun pill(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = sp(16f); setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(28).toFloat(); setColor(blue)
            }
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0; minWidth = 0
            setPadding(dp(16), 0, dp(16), 0)
            style(this, 700)
            setOnClickListener { action() }
            homeTargets += this
        }
        fun addStep(
            number: String,
            heading: String,
            description: String,
            top: Int,
            actionLabel: String? = null,
            action: (() -> Unit)? = null
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val numeral = TextView(this).apply {
                text = number
                textSize = sp(62f)
                setTextColor(pale)
                gravity = Gravity.CENTER
                style(this, 900)
            }
            row.addView(numeral, LinearLayout.LayoutParams(dp(54), dp(64)))

            val title = TextView(this).apply {
                text = heading
                textSize = sp(29f)
                setTextColor(ink)
                gravity = Gravity.CENTER_VERTICAL
                style(this, 800)
            }
            row.addView(title)

            content.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(64)).apply {
                    topMargin = dp(top)
                }
            )

            val detail = TextView(this).apply {
                text = description
                textSize = sp(16f)
                setTextColor(ink)
                gravity = Gravity.CENTER
                style(this, 500)
            }
            content.addView(detail, LinearLayout.LayoutParams(-1, -2))

            if (actionLabel != null && action != null) {
                val button = pill(actionLabel, action)
                content.addView(
                    button,
                    LinearLayout.LayoutParams(dp(186), dp(46)).apply {
                        topMargin = dp(10)
                    }
                )

                if (number == "1") {
                    cameraButton = button
                } else {
                    calibrationButton = button
                }
            }
        }
        addStep("1", "카메라 시작", "카메라 및 오버레이 권한을 허용해 주세요.", 2, "카메라 시작") { startTracking() }
        addStep("2", "시선 보정", "기기를 움직이지 말고 얼굴을 카메라에서 약\n30~50cm 떨어진 위치에 맞춰 주세요.", 26, "보정 시작") {
            startCalibration()
        }
        addStep("3", "시선 테스트", "회색 점이 시선을 잘 따라오는지 확인해 주세요.\n잘 맞지 않는다면 다시 보정해 주세요.", 26)

        stopButton = pill("추적 중지") {
            TrackerState.status = "추적 중지됨"
            stopService(Intent(this, TrackingService::class.java))
            status.text = TrackerState.status
            updateHomeButtons()
        }
        root.addView(stopButton, FrameLayout.LayoutParams(dp(186), dp(46), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(72)
        })
        root.addView(TextView(this).apply {
            text = "설정 · 개발자 기능"
            textSize = sp(14f)
            setTextColor(blue)
            gravity = Gravity.CENTER
            style(this, 500)
            setOnClickListener { startActivity(Intent(this@MainActivity, DeveloperSettingsActivity::class.java)) }
            isFocusable = true
            homeTargets += this
        }, FrameLayout.LayoutParams(dp(186), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(16)
        })
        setContentView(root)
        updateHomeButtons()

    }

    private fun updateHomeButtons() {
        if (!::cameraButton.isInitialized || !::calibrationButton.isInitialized || !::stopButton.isInitialized) return
        cameraButton.isEnabled = !TrackerState.running
        calibrationButton.isEnabled = TrackerState.running && TrackerState.hasFreshFeatures(SystemClock.uptimeMillis())
        stopButton.isEnabled = TrackerState.running
        val disabled = Color.rgb(153, 158, 165)
        listOf(cameraButton, calibrationButton, stopButton).forEach { button ->
            val drawable = button.background.mutate() as android.graphics.drawable.GradientDrawable
            drawable.setColor(if (button.isEnabled) Color.rgb(6, 108, 236) else disabled)
        }
    }

    private fun onHomeGaze(gaze: ScreenGaze?) {
        val now = SystemClock.uptimeMillis()
        val valid = TrackerState.running && gaze?.fresh(now) == true
        val bounds = DisplaySpace.bounds(this)
        val target = if (!valid) null else homeTargets.firstOrNull { view ->
            val visible = Rect()
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            view.isShown && view.isEnabled && view.getGlobalVisibleRect(visible) &&
                visible.contains((gaze!!.x * bounds.width()).toInt(), (gaze.y * bounds.height()).toInt()) &&
                TutorialButtonBounds(location[0], location[1], view.width, view.height)
                    .contains(gaze, bounds.width(), bounds.height())
        }
        // Invalid input interrupts timing, but cannot release the post-click latch.
        if (valid && target !== selectedHomeTarget) selectedHomeTarget = null
        if (target !== homeTarget) { homeDwell.reset(); homeTarget = target }
        val selected = homeDwell.update(target != null && target !== selectedHomeTarget,
            gaze?.uptimeMillis ?: 0L, now)
        TrackerState.localDwellProgress = homeDwell.progress
        if (selected && target != null) {
            selectedHomeTarget = target
            homeDwell.reset()
            TrackerState.localDwellProgress = 0f
            target.performClick()
        }
    }

    private fun startTracking() {
        if (TrackerState.running) { status.text = TrackerState.status; return }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val requested = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(requested.toTypedArray())
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Camera foreground service still works if notification permission is declined.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        try {
            startForegroundService(Intent(this, TrackingService::class.java))
            status.text = "시작 중…"
        } catch (e: Exception) { status.text = "시작 실패: ${e.message}" }
    }

    private fun startCalibration() {
        if (!TrackerState.running || !TrackerState.hasFreshFeatures(SystemClock.uptimeMillis())) {
            if (::status.isInitialized) status.text = "카메라를 먼저 시작하고 눈 검출을 기다려 주세요."
            return
        }
        tutorial?.close()
        tutorial = null
        homeDwell.reset()
        val session = TrackerState.beginCalibration()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        calibration = CalibrationView(session)
        setContentView(calibration)
    }

    private inner class CalibrationView(private val session: Long) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val targets = listOf(.5 to .5, .15 to .15, .5 to .15, .85 to .15,
            .85 to .5, .85 to .85, .5 to .85, .15 to .85, .15 to .5)
        private var index = 0
        private var since = SystemClock.uptimeMillis()
        private val blinkBreakDurationMs = 2_000L
        private var blinkBreakUntil = 0L
        private val samples = mutableListOf<EyeFeatures>()
        private val anchors = mutableListOf<Anchor>()
        private var lastSampleAt = 0L
        private var closed = false
        private var hint = "머리는 고정하고 파란 점을 바라보세요"
        private val tick = object : Runnable {
            override fun run() {
                if (closed || calibration !== this@CalibrationView) return
                val now = SystemClock.uptimeMillis()
                if (!isTakingBlinkBreak(now) && now - since > 9000) {
                    finish(null, "눈 표본이 부족합니다. 조명·거리·안경 반사를 확인하고 다시 보정하세요.")
                    return
                }
                invalidate(); postDelayed(this, 100)
            }
        }
        init { setBackgroundColor(Color.rgb(15, 23, 42)); post(tick) }
        fun close() {
            closed = true
            removeCallbacks(tick)
            samples.clear()
            anchors.clear()
            blinkBreakUntil = 0L
        }
        override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
        private fun isTakingBlinkBreak(now: Long): Boolean {
            if (blinkBreakUntil == 0L) return false
            if (now < blinkBreakUntil) return true
            blinkBreakUntil = 0L
            // Start point five's settling and timeout clocks after the break, even without eye detection.
            since = now
            hint = "${index + 1} / 9 · 파란 점을 계속 바라보세요"
            return false
        }
        fun onFrame(sample: GazeSample?) {
            if (closed || index >= targets.size || width == 0 || height == 0) return
            val now = SystemClock.uptimeMillis()
            if (isTakingBlinkBreak(now)) return
            val elapsed = now - since
            // Keep collecting open-eye features even when the old model rejects this pose.
            val f = sample?.calibrationFeatures(now, lastSampleAt)
            if (f == null) { hint = "눈 검출 대기 · 눈을 뜨고 얼굴을 카메라로 향해 주세요"; return }
            lastSampleAt = sample.timestampMs
            hint = "${index + 1} / 9 · 파란 점을 계속 바라보세요"
            if (elapsed < 1000) return
            samples += f
            if (samples.size >= 22 && elapsed >= 2500) {
                val eye = medianFeatures(samples)
                // Reject motion within a fixation, rather than fitting mismatched samples.
                if (samples.any { kotlin.math.abs(it.faceX - eye.faceX) > .045 || kotlin.math.abs(it.faceY - eye.faceY) > .045 }) {
                    samples.clear(); since = SystemClock.uptimeMillis()
                    hint = "머리가 움직였습니다 · 같은 점에서 다시 측정합니다"
                    return
                }
                val xy = targets[index]
                val location = IntArray(2); getLocationOnScreen(location)
                val bounds = DisplaySpace.bounds(this@MainActivity)
                val point = DisplayCoordinates(bounds.width(), bounds.height())
                    .normalize(location[0] + width * xy.first, location[1] + height * xy.second)
                anchors += Anchor(eye, point.first, point.second)
                index++; samples.clear(); since = SystemClock.uptimeMillis()
                if (index == 4) blinkBreakUntil = since + blinkBreakDurationMs
                if (index == targets.size) {
                    val model = GazeModel.fit(anchors)
                    finish(model, if (model == null) "보정 실패 · 시선 변화가 불충분하거나 오차가 큽니다. 기기를 고정하고 다시 시도하세요."
                        else "보정 완료 · 빨간 점과 실제 시선이 맞는지 확인하세요. 홈으로 나가도 동작합니다.")
                }
            }
            invalidate()
        }
        private fun finish(model: GazeModel?, message: String) {
            val saved = runCatching {
                TrackerState.finishCalibration(session, model) {
                    ModelStore.saveCalibration(this@MainActivity, anchors)
                }
            }
            TrackerState.status = saved.exceptionOrNull()?.let { "보정 저장 실패: ${it.message}" } ?: message
            // Match the exact normalization dimensions used for the calibration anchors above.
            val bounds = DisplaySpace.bounds(this@MainActivity)
            close()
            calibration = null
            if (saved.getOrDefault(false)) TrackerState.calibratedAtMs = SystemClock.uptimeMillis()
            if (saved.getOrDefault(false) && TrackerState.running) {
                tutorial = PostCalibrationTutorialView(this@MainActivity, bounds.width(), bounds.height(),
                    onRecalibrate = { startCalibration() },
                    onFinished = { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) })
                setContentView(tutorial)
            } else showHome()
        }
        override fun onDraw(canvas: Canvas) {
            val target = targets.getOrNull(index) ?: return
            val d = resources.displayMetrics.density
            paint.color = Color.WHITE; paint.textSize = 15 * d; paint.textAlign = Paint.Align.CENTER
            if (blinkBreakUntil != 0L) {
                val secondsLeft = ((blinkBreakUntil - SystemClock.uptimeMillis() + 999) / 1000).coerceAtLeast(1)
                canvas.drawText("시선 보정 4/9 완료 · 잠시 쉬어가기", width / 2f, 55 * d, paint)
                paint.textSize = 20 * d
                canvas.drawText("눈을 편하게 깜빡여 주세요", width / 2f, height / 2f, paint)
                paint.textSize = 14 * d
                canvas.drawText("${secondsLeft}초 후 나머지 5개를 이어서 진행합니다", width / 2f, height / 2f + 32 * d, paint)
                paint.textSize = 12 * d
                canvas.drawText("머리와 기기는 그대로 유지해 주세요", width / 2f, height - 70 * d, paint)
                canvas.drawText("중단하려면 뒤로 가기", width / 2f, height - 45 * d, paint)
                return
            }
            canvas.drawText("시선 보정 ${index + 1}/9", width / 2f, 55 * d, paint)
            paint.textSize = 12 * d
            canvas.drawText(hint, width / 2f, height - 70 * d, paint)
            canvas.drawText("중단하려면 뒤로 가기", width / 2f, height - 45 * d, paint)
            val x = (target.first * width).toFloat(); val y = (target.second * height).toFloat()
            paint.color = Color.rgb(96, 165, 250)
            canvas.drawCircle(x, y, 13 * d, paint)
            paint.color = Color.WHITE; canvas.drawCircle(x, y, 3 * d, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d
            canvas.drawArc(x - 23*d, y - 23*d, x + 23*d, y + 23*d, -90f,
                360f * (samples.size / 22f).coerceAtMost(1f), false, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
