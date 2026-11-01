package dev.gazedot

import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.pm.PackageInfoCompat
import java.util.concurrent.TimeUnit

/**
 * Iteration 3 gaze evaluation (debug builds only), opened from 설정 · 개발자 기능 after a fresh
 * nine-point calibration. Every exit finalizes the session; anything but completion is incomplete.
 */
class EvaluationActivity : ComponentActivity() {
    private class Setup(
        val participant: String, val label: String, val mode: EvaluationMode,
        val distanceCm: Double, val lighting: String, val glasses: String, val notes: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("evaluation", MODE_PRIVATE) }
    private var runView: RunView? = null
    private var lastMessage = ""
    private var status: TextView? = null
    private val listener: () -> Unit = {
        val view = runView
        if (view != null) {
            if (!TrackerState.running) endRun(EvaluationEndReason.SERVICE_STOPPED, "추적이 중지되어 평가가 미완료로 끝났습니다.")
            else if (TrackerState.model !== view.model) endRun(EvaluationEndReason.MODEL_CHANGED, "시선 모델이 바뀌어 평가가 미완료로 끝났습니다.")
        } else status?.text = readiness()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!evaluationEntryEnabled(applicationInfo.flags)) { finish(); return }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") run {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (runView != null) endRun(EvaluationEndReason.BACK, "뒤로 가기로 평가를 중단했습니다 · 미완료로 저장됨")
                else finish()
            }
        })
        showSetup()
    }
    override fun onStart() { super.onStart(); TrackerState.settingsStarted() }
    override fun onResume() {
        super.onResume()
        TrackerState.interactionAllowed = false
        TrackerState.cancelActions?.invoke()
        TrackerState.listener = listener
        status?.text = readiness()
    }
    override fun onPause() {
        // Backgrounding, rotation and screen-off all pause this screen before anything else happens.
        if (runView != null) endRun(
            if (isChangingConfigurations) EvaluationEndReason.CONFIGURATION_CHANGED else EvaluationEndReason.BACKGROUNDED,
            "화면을 벗어나 평가가 미완료로 끝났습니다.")
        if (TrackerState.listener === listener) TrackerState.listener = null
        super.onPause()
    }
    override fun onStop() { TrackerState.settingsStopped(); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun readiness(): String {
        val fresh = TrackerState.calibratedModel != null && TrackerState.model === TrackerState.calibratedModel
        val unused = TrackerState.calibrationSerial != TrackerState.evaluatedCalibrationSerial
        return listOf(
            "카메라: ${if (TrackerState.running) "작동 중" else "꺼짐"} · 눈 검출: ${if (TrackerState.hasFreshFeatures(SystemClock.uptimeMillis())) "예" else "아니오"}",
            "모델: ${TrackerState.modelLabel}",
            "새 9점 보정: ${if (fresh && unused) "준비됨" else if (fresh) "이미 평가에 사용됨 · 다시 보정 필요" else "필요"}",
            lastMessage,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun showSetup() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(36))
            setBackgroundColor(Color.rgb(245, 247, 251))
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(28, 37, 53))
            setPadding(0, dp(8), 0, dp(8)); root.addView(this)
        }
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine(true); inputType = type; root.addView(this)
        }
        fun choices(options: List<String>, selected: Int) = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            options.forEachIndexed { i, label -> addView(RadioButton(this@EvaluationActivity).apply { id = i + 1; text = label }) }
            check(selected + 1); root.addView(this)
        }
        text("시선 정확도 평가 · Iteration 3 (개발용)", 22f)
        text("기기를 거치대에 고정하고, 이번 추적에서 새 9점 보정을 마친 직후 시작합니다. 32개 파란 점이 각 2.5초씩(총 80초) 나타납니다. 머리는 움직이지 말고 점을 바라보세요. 블라인드 측정에서는 포인터가 보이지 않습니다. 평가 중에는 클릭·스크롤·받아쓰기·GPT가 꺼집니다. 뒤로 가기·화면 이탈·추적 중지 시 미완료로 저장됩니다.", 14f)
        status = text(readiness(), 15f)
        val participant = prefs.getString("participant", "P01") ?: "P01"
        text("참가자 코드 · 세션 이름", 16f)
        val participantField = field("참가자 코드 (예: P01)", participant)
        val labelField = field("세션 이름 (예: S1)", "S${existingSessions(participant) + 1}")
        text("측정 조건 (평가 전에 기록)", 16f)
        val distanceField = field("눈–화면 거리 cm (자로 측정)", prefs.getString("distance", "") ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val lightingField = field("조명 (예: 실내 천장등, 창가 낮)", prefs.getString("lighting", "") ?: "")
        text("안경", 14f)
        val glassesOptions = listOf("none", "glasses", "contact_lenses")
        val glasses = choices(listOf("없음", "안경", "콘택트렌즈"), glassesOptions.indexOf(prefs.getString("glasses", "none")).coerceAtLeast(0))
        val notesField = field("메모 (선택)", "")
        text("모드", 14f)
        val mode = choices(EvaluationMode.entries.map { it.label }, 0)
        root.addView(Button(this).apply {
            text = "평가 시작"; isAllCaps = false
            setOnClickListener {
                val p = participantField.text.toString().trim()
                val l = labelField.text.toString().trim()
                val distance = distanceField.text.toString().trim().toDoubleOrNull()
                val lighting = lightingField.text.toString().trim()
                val problem = when {
                    !validEvaluationIdentifier(p) -> "참가자 코드는 영문·숫자·_·- 1~32자로 입력하세요."
                    !validEvaluationIdentifier(l) -> "세션 이름은 영문·숫자·_·- 1~32자로 입력하세요."
                    distance == null || distance !in 10.0..150.0 -> "눈–화면 거리를 10~150cm로 입력하세요."
                    lighting.isEmpty() -> "조명 조건을 입력하세요."
                    else -> null
                }
                if (problem != null) { status?.text = problem; return@setOnClickListener }
                start(Setup(p, l, EvaluationMode.entries[mode.checkedRadioButtonId - 1], distance!!, lighting,
                    glassesOptions[glasses.checkedRadioButtonId - 1], notesField.text.toString().trim()))
            }
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(12) })
        text("기록 위치(앱 전용 저장소): files/evaluation/iter3/<참가자>/<세션 ID>/ · 내보내기 방법은 docs/evaluation/iteration3/README.md", 12f)
        root.addView(Button(this).apply { text = "돌아가기"; isAllCaps = false; setOnClickListener { finish() } },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun existingSessions(participant: String) = if (!validEvaluationIdentifier(participant)) 0 else
        java.io.File(EvaluationLog.root(filesDir), participant).listFiles()?.count { it.isDirectory } ?: 0

    private fun start(setup: Setup) {
        val saved = ModelStore.savedCalibration(this)
        val block = EvaluationGate.check(TrackerState.running, TrackerState.hasFreshFeatures(SystemClock.uptimeMillis()),
            TrackerState.model, TrackerState.calibratedModel, TrackerState.calibrationSerial, TrackerState.evaluatedCalibrationSerial,
            EvaluationModelSnapshot.refit(saved), EvaluationModelSnapshot.config(saved), ModelStore.config(this))
        if (block != null || saved == null) { status?.text = block?.message ?: EvaluationBlock.SAVED_CALIBRATION_MISMATCH.message; return }
        val model = TrackerState.model as GazeModel
        prefs.edit().putString("participant", setup.participant).putString("distance", setup.distanceCm.toString())
            .putString("lighting", setup.lighting).putString("glasses", setup.glasses).apply()
        lastMessage = ""
        // Suppress every gaze action before the view is laid out and the run clock starts.
        EvaluationState.begin(setup.mode)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runView = RunView(setup, model, saved).also { setContentView(it) }
    }

    /** Every run exit goes through here, so the session log is always finalized. */
    private fun endRun(reason: EvaluationEndReason, message: String) {
        val view = runView ?: return
        runView = null
        view.stop()
        val dir = EvaluationLog.current?.dir
        EvaluationLog.finish(reason, SystemClock.uptimeMillis())
        EvaluationState.end()
        lastMessage = message + (dir?.let { "\n세션: ${it.parentFile?.name}/${it.name}" } ?: "")
        showSetup()
    }

    private fun packageInfo() = if (Build.VERSION.SDK_INT >= 33)
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)

    private fun sessionInfo(setup: Setup, geometry: EvaluationGeometry, startMs: Long, model: GazeModel, saved: String): EvaluationSessionInfo {
        val pkg = packageInfo()
        val rotation = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        return EvaluationSessionInfo(setup.participant, setup.label, BuildConfig.EVAL_PROTOCOL_SHA256, linkedMapOf(
            "build" to mapOf(
                "git_commit" to BuildConfig.GIT_COMMIT,
                "git_dirty_at_build" to BuildConfig.GIT_DIRTY,
                "git_base_main" to BuildConfig.GIT_BASE_MAIN,
                "build_type" to BuildConfig.BUILD_TYPE,
            ),
            "app" to mapOf("application_id" to packageName, "version_name" to pkg.versionName,
                "version_code" to PackageInfoCompat.getLongVersionCode(pkg)),
            "libraries" to mapOf(
                "mediapipe_tasks_vision" to BuildConfig.MEDIAPIPE_VERSION,
                "camerax" to BuildConfig.CAMERAX_VERSION,
                "face_landmarker_task_sha256" to BuildConfig.FACE_LANDMARKER_SHA256,
            ),
            "device" to mapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                "api_level" to Build.VERSION.SDK_INT, "android_release" to Build.VERSION.RELEASE),
            "display" to mapOf(
                "basis" to "DisplaySpace.bounds",
                "width_px" to geometry.displayWidth, "height_px" to geometry.displayHeight,
                "rotation" to rotation, "density" to resources.displayMetrics.density,
                "evaluation_view" to mapOf("left_px" to geometry.viewLeft, "top_px" to geometry.viewTop,
                    "width_px" to geometry.viewWidth, "height_px" to geometry.viewHeight),
            ),
            "predictor" to mapOf(
                "kind" to "GazeModel",
                "source" to "personal_9_point_calibration",
                "label" to TrackerState.modelLabel,
                "config_key" to ModelStore.config(this),
                "calibration_serial" to TrackerState.calibrationSerial,
                "calibration_completed_uptime_ms" to TrackerState.calibratedAtMs,
                "calibration_age_ms_at_start" to startMs - TrackerState.calibratedAtMs,
                "evaluation_runs_on_this_calibration_before" to 0,
                "verified_against_saved_calibration" to true,
                "saved_calibration_sha256" to EvaluationChecksums.sha256(saved.toByteArray(Charsets.UTF_8)),
                "fit_error" to model.fitError,
                "snapshot_file" to EvaluationSession.SNAPSHOT_FILE,
            ),
            "conditions" to mapOf("viewing_distance_cm" to setup.distanceCm, "lighting" to setup.lighting,
                "glasses" to setup.glasses, "notes" to setup.notes, "declared" to "entered by the operator before the run"),
            "pipeline" to mapOf(
                "frame_interval_min_ms" to TrackingService.FRAME_INTERVAL_MS,
                "stale_result_ms" to TrackingService.STALE_RESULT_MS,
                "filter" to "GazeStabilizer",
                "filter_reset_at_run_start" to true,
                "actions_dispatched" to false,
                "cloud_enabled" to false,
                "pointer_visible" to setup.mode.showsPointer,
            ),
        ))
    }

    private inner class RunView(private val setup: Setup, val model: GazeModel, private val saved: String) : View(this@EvaluationActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var run: EvaluationRun? = null
        private var stopped = false
        private val tick = object : Runnable {
            override fun run() {
                if (stopped || runView !== this@RunView) return
                val current = run ?: begin() ?: return
                if (current.isFinished(SystemClock.uptimeMillis())) {
                    endRun(EvaluationEndReason.COMPLETED, "평가 완료 · ${setup.participant} ${setup.label} · 32개 목표")
                    return
                }
                invalidate(); postOnAnimation(this)
            }
        }
        init { setBackgroundColor(Color.rgb(15, 23, 42)); post(tick) }
        fun stop() { stopped = true; removeCallbacks(tick) }
        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

        private fun begin(): EvaluationRun? {
            if (width == 0 || height == 0) { postOnAnimation(tick); return null }
            val location = IntArray(2); getLocationOnScreen(location)
            val bounds = DisplaySpace.bounds(this@EvaluationActivity)
            val geometry = EvaluationGeometry(bounds.width(), bounds.height(), location[0], location[1], width, height)
            if (!geometry.containsAllTargets(16.0 * resources.displayMetrics.density)) {
                runView = null; stop(); EvaluationState.end()
                lastMessage = "평가 목표가 화면 밖에 놓입니다. 화면 설정을 확인해 주세요. (기록 없음)"
                showSetup()
                return null
            }
            val started = EvaluationRun(setup.mode, SystemClock.uptimeMillis(), geometry)
            val info = sessionInfo(setup, geometry, started.startMs, model, saved)
            val id = EvaluationLog.newSessionId(System.currentTimeMillis())
            val writer = EvaluationLog.writer
            val session = EvaluationSession(id, started, EvaluationLog.sessionDir(filesDir, setup.participant, id), info, model,
                EvaluationModelSnapshot.json(saved, model, TrackerState.modelLabel), writer,
                { delayMs, block -> writer.schedule(Runnable { block() }, delayMs, TimeUnit.MILLISECONDS) },
                System::currentTimeMillis,
                { s, error ->
                    // Never keep running an evaluation that is not being recorded.
                    handler.post { if (EvaluationLog.current === s) endRun(EvaluationEndReason.LOGGING_FAILED, "평가 기록 실패 · $error") }
                })
            session.open()
            EvaluationLog.start(session)
            // One run per calibration, even if this run is interrupted.
            TrackerState.evaluatedCalibrationSerial = TrackerState.calibrationSerial
            // Start from an empty filter so replay has the complete filter context.
            TrackerState.resetPipeline?.invoke("run_start")
            return started.also { run = it }
        }

        override fun onDraw(canvas: Canvas) {
            val current = run ?: return
            val p = current.presentationAt(SystemClock.uptimeMillis()) ?: return
            val d = resources.displayMetrics.density
            paint.color = Color.WHITE; paint.textSize = 13 * d; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${setup.mode.label} · ${p.pass}차 ${p.indexInPass + 1}/${EvaluationProtocol.firstPass.size}", width / 2f, 55 * d, paint)
            canvas.drawText("머리는 고정하고 파란 점을 바라보세요 · 중단하려면 뒤로 가기", width / 2f, height - 45 * d, paint)
            val (x, y) = current.geometry.toView(p.target.x, p.target.y)
            paint.color = Color.rgb(96, 165, 250)
            canvas.drawCircle(x.toFloat(), y.toFloat(), 12 * d, paint)
            paint.color = Color.WHITE; canvas.drawCircle(x.toFloat(), y.toFloat(), 3 * d, paint)
        }
    }
}
