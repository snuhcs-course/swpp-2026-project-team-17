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

private data class CalibrationCheckResult(
    val targetX: Double,
    val targetY: Double,
    val dotX: Double,
    val dotY: Double,
    val madX: Double,
    val madY: Double,
    val residual: RetargetingResidual,
)

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var cameraButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var stopButton: Button
    private lateinit var resumeButton: Button
    private var recoveryAction: String? = null
    private var recoveryDeadline = 0L
    private val recoveryRequest = object : Runnable {
        override fun run() {
            when (recoveryAction) {
                RECOVERY_START -> {
                    if (TrackerState.running) {
                        if (SystemClock.uptimeMillis() < recoveryDeadline) { handler.postDelayed(this, 100); return }
                        recoveryAction = null
                        status.text = "추적 종료를 기다리고 있어요. 잠시 후 카메라 시작을 다시 선택해 주세요."
                        return
                    }
                    recoveryAction = null
                    startTracking()
                }
                RECOVERY_CALIBRATE -> {
                    if (TrackerState.running && !TrackerState.hasFreshFeatures(SystemClock.uptimeMillis()) &&
                        SystemClock.uptimeMillis() < recoveryDeadline) { handler.postDelayed(this, 100); return }
                    recoveryAction = null
                    startCalibration(fromRecovery = true)
                }
            }
        }
    }
    private var calibration: CalibrationView? = null
    private var calibrationCheck: CalibrationCheckView? = null
    private var tutorial: PostCalibrationTutorialView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val homeTargets = mutableListOf<View>()
    private val homeDwell = TutorialDwell()
    private var homeTarget: View? = null
    private var selectedHomeTarget: View? = null
    private val homeFreshness = object : Runnable {
        override fun run() {
            if (calibration == null && calibrationCheck == null && tutorial == null) {
                homeDwell.expire(SystemClock.uptimeMillis())
                TrackerState.localDwellProgress = homeDwell.progress
            }
            handler.postDelayed(this, 50)
        }
    }
    private fun showCalibrationTutorial() {
        if(!TrackerState.running) { showHome(); return }
        val bounds=DisplaySpace.bounds(this)
        tutorial=PostCalibrationTutorialView(this,bounds.width(),bounds.height(),
            onRecalibrate={startCalibration()}, onFinished={ completed ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showHome()
                if (completed) {
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            })
        setContentView(tutorial)
    }
    private val accessibilitySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ControlAccessibilityService.instance != null) startTracking()
        else status.text = "접근성 > 설치된 앱 > NoTouch 눈 조작에서 사용 설정을 켜 주세요."
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startTracking()
        else reportPermissionFault("다른 앱 위에 표시 권한이 필요해요", RecoverySetting.OVERLAY)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startTracking()
        else reportPermissionFault("카메라 권한이 필요해요", RecoverySetting.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") run {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        recoveryAction = if (savedInstanceState == null) intent.getStringExtra(EXTRA_RECOVERY_ACTION) else null
        if (recoveryAction == RECOVERY_START && TrackerState.running) {
            TrackerState.cancelActions?.invoke()
            stopService(Intent(this, TrackingService::class.java))
        }
        showHome()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (calibration != null) {
                    val returnToRecovery = calibration?.returnToRecovery == true
                    calibration?.close()
                    calibration = null
                    TrackerState.cancelCalibration()
                    TrackerState.status = "보정 취소됨"
                    if (returnToRecovery) finish() else showHome()
                } else if (calibrationCheck != null) {
                    calibrationCheck?.close()
                    calibrationCheck = null
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (intent.hasExtra(EXTRA_RECOVERY_ACTION)) finish() else showHome()
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
                else tutorial?.onFrame(if (TrackerState.controls && !TrackerState.helpVisible && TrackerState.fault == null) TrackerState.sample else null, TrackerState.eyeState)
            } else if (calibrationCheck != null) {
                if (!TrackerState.running) showHome()
                else calibrationCheck?.onFrame(TrackerState.sample)
            } else if (calibration != null && !TrackerState.running) {
                calibration?.close()
                calibration = null
                TrackerState.cancelCalibration()
                showHome()
            } else if (calibration != null) calibration?.onFrame(TrackerState.sample)
            else if (::status.isInitialized) {
                status.text = homeStatus()
                updateHomeButtons()
                onHomeGaze(TrackerState.gaze)
            }
        }
        if (calibration == null && calibrationCheck == null && tutorial == null) {
            status.text = homeStatus()
            updateHomeButtons()
        }
        handler.removeCallbacks(homeFreshness)
        handler.post(homeFreshness)
        if (recoveryAction != null) {
            recoveryDeadline = SystemClock.uptimeMillis() + 1_500
            handler.post(recoveryRequest)
        }
    }
    override fun onPause() {
        handler.removeCallbacks(homeFreshness)
        handler.removeCallbacks(recoveryRequest)
        recoveryAction = null
        homeDwell.reset()
        TrackerState.localDwellProgress = 0f
        TrackerState.listener = null
        super.onPause()
        if (tutorial != null) showHome()
        if (calibrationCheck != null) {
            calibrationCheck?.close()
            calibrationCheck = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            showHome()
        }
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
        calibrationCheck?.close()
        tutorial?.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showHome() {
        calibrationCheck?.close()
        calibrationCheck = null
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
        fun sp(n: Float) = n
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
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }

        val logo = TextView(this).apply {
            text = "NoTouch"; textSize = sp(30f); setTextColor(ink); gravity = Gravity.CENTER
            style(this, 800)
        }
        content.addView(logo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(40) })
        status = TextView(this).apply {
            text = homeStatus()
            textSize = sp(18f); setTextColor(ink); gravity = Gravity.CENTER
            style(this, 500)
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2))

        fun pill(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = sp(16f); setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(28).toFloat(); setColor(blue)
            }
            stateListAnimator = null
            minHeight = RecoveryUi.dp(this@MainActivity, 56); minWidth = 0
            setPadding(dp(16), RecoveryUi.dp(this@MainActivity, 12), dp(16), RecoveryUi.dp(this@MainActivity, 12))
            style(this, 700)
            setOnClickListener { action() }
            homeTargets += this
        }
        content.addView(pill("도움 · 인식 문제 해결하기") {
            homeDwell.reset()
            TrackerState.cancelActions?.invoke()
            startActivity(Intent(this, RecoveryActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
            row.addView(numeral, LinearLayout.LayoutParams(dp(54), -2))

            val title = TextView(this).apply {
                text = heading
                textSize = sp(29f)
                setTextColor(ink)
                gravity = Gravity.CENTER_VERTICAL
                style(this, 800)
            }
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

            content.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply {
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
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(10)
                    }
                )

                if (number == "1") {
                    cameraButton = button
                } else if (number == "2") {
                    calibrationButton = button
                }
            }
        }
        addStep(
            "1", "카메라 시작",
            "먼저 접근성 설정에서 'NoTouch 눈 조작'을 켜고, 카메라 및 다른 앱 위 표시 권한을 허용해 주세요.",
            2, "카메라 시작"
        ) { startTracking() }
        addStep("2", "시선 보정", "기기를 움직이지 말고 얼굴을 카메라에서 약\n30~50cm 떨어진 위치에 맞춰 주세요.", 26, "보정 시작") {
            startCalibration()
        }
        addStep("3", "시선 테스트", "필요할 때만 추가 검사·보정을 진행합니다.", 26, "정밀 보정") {
            if (TrackerState.model == null) status.text = "먼저 9점 보정을 완료해 주세요"
            else startActivity(Intent(this, AccuracyActivity::class.java))
        }

        stopButton = pill("추적 중지") {
            TrackerState.status = "추적 중지됨"
            stopService(Intent(this, TrackingService::class.java))
            status.text = homeStatus()
            updateHomeButtons()
        }
        fun addHomeAction(button: View) {
            content.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        resumeButton = pill("조작 재개") {
            if (TrackerState.running) startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_RESUME))
        }
        addHomeAction(resumeButton)
        addHomeAction(stopButton)
        addHomeAction(pill("설정 · 개발자 기능") {
            startActivity(Intent(this@MainActivity, DeveloperSettingsActivity::class.java))
        })
        setContentView(root)
        updateHomeButtons()

    }

    private fun updateHomeButtons() {
        if (!::cameraButton.isInitialized || !::calibrationButton.isInitialized || !::stopButton.isInitialized) return
        cameraButton.isEnabled = !TrackerState.running
        calibrationButton.text = if (TrackerState.model == null) "보정 시작" else "다시 보정하기"
        calibrationButton.isEnabled = TrackerState.running && TrackerState.fault == null && TrackerState.hasFreshFeatures(SystemClock.uptimeMillis())
        resumeButton.visibility = if (TrackerState.running && !TrackerState.controls) View.VISIBLE else View.GONE
        stopButton.isEnabled = TrackerState.running
        val disabled = Color.rgb(153, 158, 165)
        listOf(cameraButton, calibrationButton, stopButton).forEach { button ->
            val drawable = button.background.mutate() as android.graphics.drawable.GradientDrawable
            drawable.setColor(if (button.isEnabled) Color.rgb(6, 108, 236) else disabled)
        }
    }

    private fun onHomeGaze(gaze: ScreenGaze?) {
        val now = SystemClock.uptimeMillis()
        val valid = TrackerState.running && TrackerState.controls && TrackerState.fault == null &&
            !TrackerState.helpVisible && !TrackerState.collecting && !TrackerState.speaking && gaze?.fresh(now) == true
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

    private fun homeStatus(): String {
        val current = TrackerState.currentStatus(SystemClock.uptimeMillis())
        val calibrationMessage = TrackerState.status.takeIf { it.startsWith("보정 실패") || it.startsWith("보정 취소") || it.startsWith("보정이 중단") || it.startsWith("눈 표본이 부족") }
        return calibrationMessage ?: listOf(current.message, current.advice, if(TrackerState.running) TrackerState.inferenceStatus else "").filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun reportPermissionFault(message: String, setting: RecoverySetting) {
        TrackerState.fault = TrackingFault(message, "도움에서 관련 설정을 열거나 카메라 시작을 다시 선택해 주세요.", setting)
        status.text = homeStatus()
    }

    private fun startTracking() {
        if (TrackerState.running) { status.text = homeStatus(); return }
        if (ControlAccessibilityService.instance == null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("NoTouch 눈 조작 켜기")
                .setMessage("다음 화면에서 접근성 > 설치된 앱 > NoTouch 눈 조작을 열고 사용 설정을 켜 주세요. 완료 후 NoTouch로 돌아오면 나머지 권한 안내가 이어집니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("접근성 설정 열기") { _, _ ->
                    accessibilitySettings.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
            return
        }
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
        } catch (e: Exception) {
            TrackerState.fault = TrackingFault("추적을 시작하지 못했어요", "권한을 확인하고 카메라를 다시 시작해 주세요.")
            status.text = homeStatus()
        }
    }

    private fun startCalibration(fromRecovery: Boolean = false) {
        if(!TrackerState.running || TrackerState.fault!=null) { status.text="먼저 카메라를 시작하세요";return }
        tutorial?.close();tutorial=null
        startScreenCalibration(fromRecovery)
    }
    private fun startScreenCalibration(fromRecovery: Boolean = false) {
        if (!TrackerState.running || TrackerState.fault != null) {
            if (::status.isInitialized) status.text = "보정을 시작할 수 없어요. 카메라를 시작하고 눈이 검출되면 다시 선택해 주세요."
            return
        }
        tutorial?.close()
        tutorial = null
        calibrationCheck?.close()
        calibrationCheck = null
        homeDwell.reset()
        val session = TrackerState.beginCalibration()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Capture navigation intent for this session, before a new model is applied.
        calibration = CalibrationView(session, fromRecovery && TrackerState.model != null)
        setContentView(calibration)
    }

    private inner class CalibrationView(private val session: Long, val returnToRecovery: Boolean) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val acquisition = ScreenCalibration(SystemClock.uptimeMillis())
        private var closed = false
        private val buttons = mutableListOf<Pair<RectF,()->Unit>>()
        private var reportSaved = false
        private val tick = object : Runnable {
            override fun run() {
                if (closed || calibration !== this@CalibrationView) return
                invalidate();postDelayed(this,100)
            }
        }
        init { setBackgroundColor(Color.rgb(15,23,42));post(tick) }
        fun close() { closed=true;removeCallbacks(tick) }
        override fun onDetachedFromWindow() { close();super.onDetachedFromWindow() }
        fun onFrame(sample:GazeSample?) {
            if(closed || width==0 || height==0) return
            val xy=acquisition.targets[acquisition.index]
            val origin=IntArray(2);getLocationOnScreen(origin)
            val bounds=DisplaySpace.bounds(this@MainActivity)
            val target=DisplayCoordinates(bounds.width(),bounds.height()).normalize(
                origin[0]+width*xy.first,origin[1]+height*xy.second)
            val reason=when(TrackerState.eyeState) {
                EyeState.CLOSED,EyeState.LEFT_WINK,EyeState.RIGHT_WINK -> "눈 감김 · 잠시 대기"
                EyeState.LOST -> "눈 특징을 찾는 중"
                else -> "새 눈 표본 대기"
            }
            acquisition.observe(sample,SystemClock.uptimeMillis(),target,reason)
            if(acquisition.review!=null && !reportSaved) {
                reportSaved=true
                val result=acquisition.review!!
                getSharedPreferences("personal",MODE_PRIVATE).edit().putString("calibration_audit_v30",result.report).apply()
                val rows=acquisition.fixations().flatMap { g -> g.samples.map { f ->
                    listOf(g.x,g.y,f.leftX,f.leftY,f.rightX,f.rightY,f.faceX,f.faceY,f.scale)
                } }
                runCatching { java.io.File(filesDir,"last-calibration-v44.json").writeText(
                    org.json.JSONObject().put("version",44).put("report",result.report)
                        .put("rows",org.json.JSONArray(rows)).toString()) }
            }
            invalidate()
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(event.actionMasked==MotionEvent.ACTION_UP) {
                buttons.firstOrNull { it.first.contains(event.x,event.y) }?.second?.invoke()
                performClick()
            }
            return true
        }
        override fun performClick():Boolean { super.performClick();return true }
        private fun finish(model: GazeModel?, message: String) {
            val saved = runCatching {
                TrackerState.finishCalibration(session, model)
            }
            TrackerState.status = saved.exceptionOrNull()?.let { "보정 적용 실패: ${it.message}" } ?: message
            close()
            calibration = null
            if (saved.getOrDefault(false) && TrackerState.running) {
                val bounds = DisplaySpace.bounds(this@MainActivity)
                calibrationCheck = CalibrationCheckView(bounds.width(), bounds.height(), returnToRecovery)
                setContentView(calibrationCheck)
            } else if (returnToRecovery) finish() else {
                showHome()
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("새 보정이 적용되지 않았습니다")
                    .setMessage(message + if(TrackerState.model!=null) "\n이전 보정은 유지됩니다." else "\n적용된 보정이 없습니다.")
                    .setPositiveButton("확인",null).show()
            }
        }
        override fun onDraw(canvas: Canvas) {
            buttons.clear()
            val d=resources.displayMetrics.density
            paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;paint.color=Color.WHITE
            val review=acquisition.review
            if(review!=null) {
                val d=minOf(d,height/720f)
                paint.textSize=23*d;canvas.drawText("보정 결과",width/2f,90*d,paint)
                paint.textSize=14*d
                review.report.split("\n").forEachIndexed { i,line ->
                    // Wrap long Korean lines to remain readable on narrow phones.
                    line.chunked(30).forEachIndexed { j,part -> canvas.drawText(part,width/2f,(140+i*56+j*22)*d,paint) }
                }
                var y=maxOf(height*.56f,390*d)
                fun button(label:String,action:()->Unit) {
                    val box=RectF(24*d,y,width-24*d,y+48*d)
                    paint.color=Color.rgb(6,108,236);canvas.drawRoundRect(box,12*d,12*d,paint)
                    paint.color=Color.WHITE;paint.textSize=16*d;canvas.drawText(label,width/2f,y+31*d,paint)
                    buttons += box to action;y+=60*d
                }
                if(review.model!=null) button("이 보정으로 시작") { finish(review.model,"보정 완료 · 실제 움직임을 확인해 주세요") }
                button("${review.worstTarget+1}번 점만 다시 측정") {
                    acquisition.retry(review.worstTarget,SystemClock.uptimeMillis());reportSaved=false;invalidate()
                }
                button("9개 점 전체 다시 측정") { acquisition.restart(SystemClock.uptimeMillis());reportSaved=false;invalidate() }
                return
            }
            val target=acquisition.targets[acquisition.index]
            paint.textSize=15*d;canvas.drawText("화면 맞춤 ${acquisition.completedCount+1}/9",width/2f,25*d,paint)
            paint.textSize=13*d;canvas.drawText(acquisition.hint,width/2f,height/2f+65*d,paint)
            paint.textSize=12*d;canvas.drawText("깜빡여도 괜찮아요 · 중단은 뒤로 가기",width/2f,height/2f+88*d,paint)
            val x=(target.first*width).toFloat();val y=(target.second*height).toFloat()
            paint.color=Color.rgb(96,165,250);canvas.drawCircle(x,y,13*d,paint)
            paint.color=Color.WHITE;canvas.drawCircle(x,y,3*d,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=3*d
            canvas.drawArc(x-23*d,y-23*d,x+23*d,y+23*d,-90f,360f*acquisition.progress,false,paint)
            paint.style=Paint.Style.FILL
        }
    }

    /** Measures post-calibration cursor bias and applies one bounded screen-space correction. */
    private inner class CalibrationCheckView(
        private val displayWidth: Int,
        private val displayHeight: Int,
        private val returnToRecovery: Boolean,
    ) : View(this@MainActivity) {
        private val targets = listOf(.2 to .2, .8 to .2, .8 to .8, .2 to .8, .5 to .5)
        private val results = mutableListOf<CalibrationCheckResult?>()
        private val samples = mutableListOf<Pair<Double, Double>>()
        private val targetLocation = IntArray(2)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private val settleMs = 900L
        private val minPointTimeMs = 2_000L
        private val targetTimeoutMs = 8_000L
        private val minSamples = 12
        private val minCorrectionPoints = 3
        private val maxCorrection = .08
        private var index = 0
        private var since = SystemClock.uptimeMillis()
        private var lastSampleAt = 0L
        private var report = false
        private var closed = false
        private var correctionX = 0.0
        private var correctionY = 0.0
        private var correctionApplied = false
        private var correctionLimited = false
        private var hint = "파란 점을 바라보고 빨간 점을 확인해 주세요"
        private val tick = object : Runnable {
            override fun run() {
                if (closed || calibrationCheck !== this@CalibrationCheckView) return
                if (!report && index < targets.size && SystemClock.uptimeMillis() - since >= targetTimeoutMs) savePoint()
                invalidate()
                postDelayed(this, 100)
            }
        }

        init {
            setBackgroundColor(Color.rgb(15, 23, 42))
            isClickable = true
            post(tick)
        }

        fun close() {
            closed = true
            removeCallbacks(tick)
            samples.clear()
        }

        override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }

        fun onFrame(sample: GazeSample?) {
            if (closed || report || index >= targets.size || width == 0 || height == 0) return
            val now = SystemClock.uptimeMillis()
            val elapsed = now - since
            val point = sample?.calibrationCheckPoint(now, since, settleMs)
            if (sample == null || sample.quality != TrackingQuality.TRACKING ||
                now - sample.timestampMs !in 0..250 || sample.timestampMs <= lastSampleAt ||
                point == null || !point.first.isFinite() || !point.second.isFinite()) {
                hint = "눈 검출 대기 · 화면과 얼굴을 향해 주세요"
                invalidate()
                return
            }
            lastSampleAt = sample.timestampMs
            if (elapsed >= settleMs) samples += point
            hint = if (elapsed < settleMs) "시선을 안정시키는 중…" else "빨간 점 위치를 측정 중…"
            if (elapsed >= minPointTimeMs && samples.size >= minSamples) savePoint()
            invalidate()
        }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }

        private fun savePoint() {
            if (index >= targets.size) return
            val result = if (samples.size < minSamples) null else {
                val xs = samples.map { it.first }
                val ys = samples.map { it.second }
                val medianX = median(xs)
                val medianY = median(ys)
                getLocationOnScreen(targetLocation)
                val target = targets[index]
                val targetX = (targetLocation[0] + width * target.first) / displayWidth
                val targetY = (targetLocation[1] + height * target.second) / displayHeight
                CalibrationCheckResult(targetX, targetY, medianX, medianY,
                    median(xs.map { kotlin.math.abs(it - medianX) }),
                    median(ys.map { kotlin.math.abs(it - medianY) }),
                    RetargetingResidual(
                        median(xs.take(xs.size/2))-targetX to median(ys.take(ys.size/2))-targetY,
                        median(xs.drop(xs.size/2))-targetX to median(ys.drop(ys.size/2))-targetY))
            }
            results += result
            index++
            samples.clear()
            since = SystemClock.uptimeMillis()
            report = index >= targets.size
            if (report) applyMeasuredCorrection()
            hint = if (report) "측정이 끝났습니다" else "다음 파란 점을 바라봐 주세요"
        }

        private fun applyMeasuredCorrection() {
            val valid = results.filterNotNull()
            if (valid.size < minCorrectionPoints) return
            val model = TrackerState.model ?: return
            val offset = RetargetingOffset.choose(valid.map { it.residual }, maxCorrection) ?: return
            correctionX = offset.first
            correctionY = offset.second
            correctionLimited = kotlin.math.abs(correctionX) >= maxCorrection || kotlin.math.abs(correctionY) >= maxCorrection
            TrackerState.model = model.withScreenCorrection(correctionX, correctionY) ?: return
            correctionApplied = true
        }

        private fun percent(value: Double, signed: Boolean = false): String {
            val sign = if (!signed) "" else if (value > 0) "+" else if (value < 0) "−" else ""
            return "$sign${"%.1f".format(java.util.Locale.ROOT, kotlin.math.abs(value) * 100)}%"
        }

        private fun continueToTutorial() {
            if (closed || !report) return
            close()
            calibrationCheck = null
            if (returnToRecovery) finish() else showCalibrationTutorial()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val left = 24 * density
            val right = width - 24 * density
            val top = height - 92 * density
            val bottom = height - 36 * density
            if (event.action == MotionEvent.ACTION_UP && report && event.x in left..right && event.y in top..bottom)
                continueToTutorial()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.WHITE
            paint.textSize = 15 * density
            canvas.drawText(if (report) "시선 보정 확인" else "시선 보정 확인 ${index + 1}/${targets.size}",
                width / 2f, 55 * density, paint)
            if (!report) {
                paint.textSize = 12 * density
                canvas.drawText(hint, width / 2f, height - 70 * density, paint)
                canvas.drawText("머리와 기기를 편하게 고정해 주세요", width / 2f, height - 45 * density, paint)
                val target = targets.getOrNull(index) ?: return
                val x = (target.first * width).toFloat()
                val y = (target.second * height).toFloat()
                paint.color = Color.rgb(96, 165, 250)
                canvas.drawCircle(x, y, 13 * density, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 3 * density, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3 * density
                canvas.drawArc(x - 23*density, y - 23*density, x + 23*density, y + 23*density,
                    -90f, 360f * (samples.size / minSamples.toFloat()).coerceAtMost(1f), false, paint)
                paint.style = Paint.Style.FILL
                return
            }
            val valid = results.filterNotNull()
            val lines = if (valid.isEmpty()) {
                listOf("측정할 시선 표본이 부족했습니다.", "조명과 카메라 각도를 확인한 뒤 다시 보정해 주세요.",
                    "뒤로 가기를 눌러 보정 화면으로 돌아갈 수 있습니다.")
            } else {
                val biasX = valid.map { it.dotX - it.targetX }.average()
                val biasY = valid.map { it.dotY - it.targetY }.average()
                val madX = valid.map { it.madX }.average()
                val madY = valid.map { it.madY }.average()
                listOf("평균 위치 오차", "가로 ${percent(biasX, true)} · 세로 ${percent(biasY, true)}",
                    "(+ 오른쪽/아래 · − 왼쪽/위)", "",
                    if (correctionApplied) "적용한 위치 보정" else "자동 위치 보정 미적용",
                    if (correctionApplied) "가로 ${percent(correctionX, true)} · 세로 ${percent(correctionY, true)}"
                    else if (valid.size < minCorrectionPoints) "표본이 ${minCorrectionPoints}곳 미만입니다."
                    else "검증에서 개선이 확인되지 않아 기존 보정을 유지합니다.",
                    if (correctionLimited) "큰 편차는 8% 이내로 제한했습니다." else "", "",
                    "시선 고정 중 중앙 편차", "가로 ${percent(madX)} · 세로 ${percent(madY)}",
                    "화면 너비/높이 기준 · ${valid.size}/${targets.size}곳 측정")
            }
            val lineHeight = 26 * density
            var y = height / 2f - lines.count { it.isNotEmpty() } * lineHeight / 2f
            lines.filter { it.isNotEmpty() }.forEach { line ->
                paint.textSize = if (line == "평균 위치 오차" || line == "시선 고정 중 중앙 편차" ||
                    line == "적용한 위치 보정" || line == "자동 위치 보정 미적용") 17 * density else 14 * density
                canvas.drawText(line, width / 2f, y, paint)
                y += lineHeight
            }
            val button = RectF(24 * density, height - 92 * density, width - 24 * density, height - 36 * density)
            paint.color = Color.rgb(6, 108, 236)
            canvas.drawRoundRect(button, 28 * density, 28 * density, paint)
            paint.color = Color.WHITE
            paint.textSize = 15 * density
            canvas.drawText("튜토리얼 시작", width / 2f, button.centerY() + 5 * density, paint)
        }
    }

    companion object {
        const val EXTRA_RECOVERY_ACTION = "dev.gazedot.RECOVERY_ACTION"
        const val RECOVERY_START = "start"
        const val RECOVERY_CALIBRATE = "calibrate"
    }

}
