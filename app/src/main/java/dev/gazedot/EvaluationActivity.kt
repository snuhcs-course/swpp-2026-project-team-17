package dev.gazedot

import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.util.concurrent.TimeUnit

/**
 * Calibration-comparison gaze evaluation (debug builds only), opened from 설정 · 개발자 기능 after a fresh calibration.
 * One session: three head-position blocks, each preceded by a preparation screen with a Ready button and a 3 s
 * countdown. Every exit finalizes the session; anything but completion is incomplete and still consumes the calibration.
 *
 * This screen polls TrackerState instead of taking TrackerState.listener: MainActivity clears that listener in onPause.
 */
class EvaluationActivity : ComponentActivity() {
    private class Setup(
        val planned: PlannedEvaluationSession, val mode: EvaluationMode,
        val distanceCm: Double, val lighting: String, val glasses: String, val positionMarkers: String, val notes: String,
    ) {
        // Fixed by the collection plan entry, never typed by hand.
        val participant get() = planned.participantId
        val label get() = planned.sessionLabel
        val order get() = planned.order
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("evaluation_calibration_comparison", MODE_PRIVATE) }
    private var runView: RunView? = null
    private var lastMessage = ""
    private var status: TextView? = null
    private val readinessPoll = object : Runnable {
        override fun run() {
            if (runView == null) status?.text = readiness()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!evaluationEntryEnabled(applicationInfo.flags)) { finish(); return }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (runView != null) endRun(EvaluationEndReason.BACK, "뒤로 가기로 평가를 중단했습니다 · 미완료로 저장됨")
                else finish()
            }
        })
        showSetup()
    }
    override fun onStart() { super.onStart(); TrackerState.settingsStarted() }
    override fun onStop() { TrackerState.settingsStopped(); super.onStop() }
    override fun onResume() { super.onResume(); handler.post(readinessPoll) }
    override fun onPause() {
        // Backgrounding, rotation and screen-off all pause this screen before anything else happens.
        if (runView != null) endRun(
            if (isChangingConfigurations) EvaluationEndReason.CONFIGURATION_CHANGED else EvaluationEndReason.BACKGROUNDED,
            "화면을 벗어나 평가가 미완료로 끝났습니다.")
        handler.removeCallbacks(readinessPoll)
        super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun currentBasis(): DisplayBasis? = if (Build.VERSION.SDK_INT >= 30)
        DisplaySpace.bounds(this).let { DisplayBasis(it.width(), it.height(), display?.rotation ?: -1) } else null

    private fun refusal(): EvaluationRefusal? = EvaluationGate.check(Build.VERSION.SDK_INT, TrackerState.running,
        TrackerState.features != null, TrackerState.model, TrackerState.calibrationRecord, TrackerState.calibrationSerial,
        TrackerState.evaluatedCalibrationSerial, currentBasis())

    private fun readiness(): String {
        val record = TrackerState.calibrationRecord
        val fresh = record != null && TrackerState.model === record.model
        val unused = TrackerState.calibrationSerial != TrackerState.evaluatedCalibrationSerial
        return listOf(
            "이 빌드의 보정 후보: ${CalibrationPlan.ID}",
            "카메라: ${if (TrackerState.running) "작동 중" else "꺼짐"} · 눈 검출: ${if (TrackerState.features != null) "예" else "아니오"}",
            "새 보정: " + when {
                record != null && fresh && unused -> "준비됨 · ${record.anchors.size}점 · ${record.durationMs / 1000}초 · 재측정 ${record.movementRetries}회"
                fresh -> "이미 평가에 사용됨 · 다시 보정 필요"
                else -> "필요 (홈에서 보정을 끝까지 성공해야 합니다)"
            },
            refusal()?.let { "시작 불가: ${it.message}" } ?: "평가를 시작할 수 있습니다.",
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
        /** [selected] < 0 leaves every option unchecked. */
        fun choices(options: List<String>, selected: Int) = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            options.forEachIndexed { i, label -> addView(RadioButton(this@EvaluationActivity).apply { id = i + 1; text = label }) }
            if (selected >= 0) check(selected + 1)
            root.addView(this)
        }
        text("시선 정확도 평가 · 보정 비교 (개발용)", 22f)
        text("기기를 거치대에 고정하고, 홈에서 새 보정(3회차 × 9점: 기본 → 조금 높게 → 조금 낮게)을 성공한 직후 시작합니다. 한 세션은 자세 3블록이며, " +
            "블록마다 32개 파란 점이 각 2.5초씩(80초) 나타납니다. 블록 전에는 안내된 자세로 옮긴 뒤 '준비 완료'를 누르고, " +
            "3초 후 시작합니다. 블라인드 측정에서는 시선 점이 보이지 않으며 클릭·스크롤·받아쓰기는 꺼집니다. " +
            "뒤로 가기·화면 이탈·추적 중지 시 미완료로 저장되며, 이 보정은 다시 평가에 쓸 수 없습니다.", 14f)
        status = text(readiness(), 15f)
        // Only the collection plan's sessions can be chosen; participant, label and block order come from the entry.
        // Nothing is preselected, so a session is never started under an earlier choice by accident.
        text("세션 선택 (수집 계획의 세션만 시작할 수 있습니다)", 16f)
        val session = choices(EvaluationPlan.sessions.map { it.label }, -1)
        text("공식 세션은 B1과 B2 각 한 번씩, 각자 새 보정 직후에 진행합니다. 기술 시험은 앱·절차 확인용이며 공식 결과에 포함되지 않습니다.", 12f)
        text("측정 조건 (평가 전에 기록)", 16f)
        val distanceField = field("기본 위치의 눈–화면 거리 cm (자로 측정)", prefs.getString("distance", "") ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val lightingField = field("조명 (예: 실내 천장등, 창가 낮)", prefs.getString("lighting", "") ?: "")
        text("안경", 14f)
        val glassesOptions = listOf("none", "glasses", "contact_lenses")
        val glasses = choices(listOf("없음", "안경", "콘택트렌즈"), glassesOptions.indexOf(prefs.getString("glasses", "none")).coerceAtLeast(0))
        val markersField = field("자세 기준 메모 (예: 기본=턱 표시 0, 위=+2cm 표시, 아래=-2cm 표시)", prefs.getString("markers", "") ?: "")
        val notesField = field("메모 (선택)", "")
        text("모드", 14f)
        val mode = choices(EvaluationMode.entries.map { it.label }, 0)
        root.addView(Button(this).apply {
            text = "평가 시작"; isAllCaps = false
            setOnClickListener {
                val planned = EvaluationPlan.sessions.getOrNull(session.checkedRadioButtonId - 1)
                val distance = distanceField.text.toString().trim().toDoubleOrNull()
                val lighting = lightingField.text.toString().trim()
                val markers = markersField.text.toString().trim()
                val problem = when {
                    planned == null -> "세션을 선택하세요 (ANNA · B1, ANNA · B2 또는 기술 시험)."
                    distance == null || distance !in 10.0..150.0 -> "눈–화면 거리를 10~150cm로 입력하세요."
                    lighting.isEmpty() -> "조명 조건을 입력하세요."
                    markers.isEmpty() -> "기본·위·아래 자세를 어떻게 맞췄는지 자세 기준 메모를 입력하세요."
                    else -> null
                }
                if (problem != null) { status?.text = problem; return@setOnClickListener }
                start(Setup(planned!!, EvaluationMode.entries[mode.checkedRadioButtonId - 1], distance!!, lighting,
                    glassesOptions[glasses.checkedRadioButtonId - 1], markers, notesField.text.toString().trim()))
            }
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(12) })
        text("기록 위치(앱 전용 저장소): files/evaluation/calibration-comparison/<참가자>/<세션 ID>/ · 내보내기 방법은 docs/calibration-comparison/README.md", 12f)
        root.addView(Button(this).apply { text = "돌아가기"; isAllCaps = false; setOnClickListener { finish() } },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start(setup: Setup) {
        val refused = refusal()
        val record = TrackerState.calibrationRecord
        if (refused != null || record == null) { status?.text = (refused ?: EvaluationRefusal.NOT_FRESHLY_CALIBRATED).message; return }
        // Declared conditions are remembered for the next session; the session choice deliberately is not.
        prefs.edit {
            putString("distance", setup.distanceCm.toString())
            putString("lighting", setup.lighting); putString("glasses", setup.glasses)
            putString("markers", setup.positionMarkers)
        }
        lastMessage = ""
        // Pointer and gaze actions follow the session from here on, including preparation and countdown.
        EvaluationState.begin(setup.mode)
        TrackerState.cancelActions?.invoke()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = RunView(setup, record, TrackerState.calibrationSerial)
        runView = view
        setContentView(view.screen)
    }

    /** Every session exit goes through here, so the session log is always finalized and normal state restored. */
    private fun endRun(reason: EvaluationEndReason, message: String) {
        val view = runView ?: return
        runView = null
        view.stop()
        val dir = view.session?.dir
        EvaluationLog.finish(reason, SystemClock.uptimeMillis())
        EvaluationState.end()
        TrackerState.cancelActions?.invoke()
        lastMessage = message + (dir?.let { "\n세션: ${it.parentFile?.name}/${it.name}" } ?: "")
        showSetup()
    }

    private fun packageInfo() = if (Build.VERSION.SDK_INT >= 33)
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)

    private fun sessionInfo(setup: Setup, geometry: EvaluationGeometry, basis: DisplayBasis, startMs: Long,
                            record: CalibrationRecord, serial: Int): EvaluationSessionInfo {
        val pkg = packageInfo()
        val overlay = TrackerState.overlayBounds?.invoke()
        return EvaluationSessionInfo(setup.participant, setup.label, BuildConfig.EVAL_PROTOCOL_SHA256, linkedMapOf(
            "build" to mapOf(
                "git_commit" to BuildConfig.GIT_COMMIT,
                "git_dirty_at_build" to BuildConfig.GIT_DIRTY,
                "source_base" to EvaluationProtocol.SOURCE_BASE,
                "source_base_is_ancestor" to BuildConfig.GIT_SOURCE_BASE_IS_ANCESTOR,
                "evaluation_reference" to EvaluationProtocol.EVALUATION_REFERENCE,
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
                "basis" to "WindowManager.maximumWindowMetrics bounds (calibration basis)",
                "width_px" to basis.width, "height_px" to basis.height,
                "rotation" to basis.rotation, "density" to resources.displayMetrics.density,
                "evaluation_view" to mapOf("left_px" to geometry.viewLeft, "top_px" to geometry.viewTop,
                    "width_px" to geometry.viewWidth, "height_px" to geometry.viewHeight),
                "calibration_view" to mapOf("left_px" to record.viewLeft, "top_px" to record.viewTop,
                    "width_px" to record.viewWidth, "height_px" to record.viewHeight),
                "overlay_view" to overlay?.let { mapOf("left_px" to it[0], "top_px" to it[1], "width_px" to it[2], "height_px" to it[3]) },
            ),
            "calibration" to linkedMapOf(
                "calibration_id" to record.calibrationId,
                "calibration_positions_by_pass" to CalibrationPlan.positions.map { it.id },
                "calibration_serial" to serial,
                "started_uptime_ms" to record.startedAtMs,
                "completed_uptime_ms" to record.completedAtMs,
                "duration_ms" to record.durationMs,
                "age_ms_at_session_start" to startMs - record.completedAtMs,
                "movement_retries" to record.movementRetries,
                "anchors" to record.anchors.size,
                "passes" to EvaluationProtocol.CALIBRATION_PASSES,
                "points_per_pass" to EvaluationProtocol.CALIBRATION_POINTS_PER_PASS,
                "calibration_inputs_sha256" to EvaluationModelSnapshot.inputsSha256(record.anchors),
                "fit_error" to record.model.fitError,
                "fit_error_note" to "calibration diagnostic (RMS residual of the fit inputs); not a measured gaze accuracy",
                "verified_snapshot_refit" to true,
                "evaluation_sessions_on_this_calibration_before" to 0,
                "snapshot_file" to EvaluationSession.SNAPSHOT_FILE,
            ),
            "predictor" to mapOf("kind" to "GazeModel", "calibration_id" to record.calibrationId),
            "conditions" to mapOf("viewing_distance_cm" to setup.distanceCm, "lighting" to setup.lighting,
                "glasses" to setup.glasses, "head_position_markers" to setup.positionMarkers, "notes" to setup.notes,
                "head_positions" to "instructed (neutral, slightly higher, slightly lower), not measured; face_x/face_y/scale are logged per attempt",
                "declared" to "entered by the operator before the session"),
            "pipeline" to mapOf(
                "variant" to EvaluationProtocol.PIPELINE_VARIANT,
                "frame_interval_min_ms" to TrackingService.FRAME_INTERVAL_MS,
                "delayed_result_rule" to "results handled more than ${TrackingService.DELAYED_RESULT_MS} ms after acceptance are dropped and reset the pipeline",
                "stale_rule" to "GazePipeline.process marks a timestamp older than 250 ms, in the future or not increasing as stale and resets",
                "watchdog_no_result_ms" to TrackingService.WATCHDOG_NO_RESULT_MS,
                "model_clamp" to "GazeModel.predict clamps each axis to [0, 1]",
                "smoothing" to "GazePipeline: s + 0.28 * (p - s) per axis; cleared by a null prediction, a gap over 250 ms, a stale result, a model change and every pipeline reset",
                "reset_policy" to "pipeline reset at session start and at each block start (first main-thread moment at or after it); results accepted before a block start and handled after it are dropped before smoothing",
                "session_start_lead_ms" to EvaluationProtocol.SESSION_START_LEAD_MS,
                "pointer_visible" to setup.mode.showsPointer,
                "gaze_actions" to "clicks, scrolling and dictation suppressed for the whole session (explicit guards)",
            ),
        ))
    }

    private inner class RunView(private val setup: Setup, val record: CalibrationRecord, private val serial: Int) : View(this@EvaluationActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var session: EvaluationSession? = null
            private set
        private var stopped = false
        private val ready = Button(this@EvaluationActivity).apply {
            text = "준비 완료"; isAllCaps = false; textSize = 20f; visibility = View.GONE
            setOnClickListener {
                val s = session ?: return@setOnClickListener
                val now = SystemClock.uptimeMillis()
                val at = s.schedule.locate(now) ?: return@setOnClickListener
                if (at.phase == EvaluationPhase.PREPARATION && s.markReady(at.blockIndex, now)) visibility = View.GONE
            }
        }
        val screen = FrameLayout(this@EvaluationActivity).apply {
            addView(this@RunView, FrameLayout.LayoutParams(-1, -1))
            val d = resources.displayMetrics.density
            addView(ready, FrameLayout.LayoutParams((220 * d).toInt(), (64 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = (120 * d).toInt() })
        }
        private val tick = object : Runnable {
            override fun run() {
                if (stopped || runView !== this@RunView) return
                if (!TrackerState.running) { endRun(EvaluationEndReason.SERVICE_STOPPED, "추적이 중지되어 평가가 미완료로 끝났습니다."); return }
                if (TrackerState.model !== record.model) { endRun(EvaluationEndReason.MODEL_CHANGED, "시선 모델이 바뀌어 평가가 미완료로 끝났습니다."); return }
                val s = session ?: begin() ?: return
                val now = SystemClock.uptimeMillis()
                s.dueBlockReset(now)?.let { TrackerState.resetSmoothing?.invoke(EvaluationSession.RESET_BLOCK_START) }
                if (s.schedule.isFinished(now)) {
                    endRun(EvaluationEndReason.COMPLETED, "평가 완료 · ${setup.participant} ${setup.label} · 3블록 96개 목표")
                    return
                }
                val at = s.schedule.locate(now)
                val preparing = at?.phase == EvaluationPhase.PREPARATION
                if (preparing) s.preparationShown(at!!.blockIndex, now)
                ready.visibility = if (preparing) View.VISIBLE else View.GONE
                invalidate(); postOnAnimation(this)
            }
        }
        init { setBackgroundColor(Color.rgb(15, 23, 42)); post(tick) }
        fun stop() { stopped = true; removeCallbacks(tick) }
        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

        private fun begin(): EvaluationSession? {
            if (width == 0 || height == 0) { postOnAnimation(tick); return null }
            val basis = currentBasis()
            val location = IntArray(2); getLocationOnScreen(location)
            val geometry = basis?.let { EvaluationGeometry(it.width, it.height, location[0], location[1], width, height) }
            if (basis == null || geometry == null || !geometry.containsAllTargets(16.0 * resources.displayMetrics.density)) {
                runView = null; stop(); EvaluationState.end()
                lastMessage = "평가 목표가 화면 밖에 놓입니다. 화면 설정을 확인해 주세요. (기록 없음)"
                showSetup()
                return null
            }
            val schedule = EvaluationSchedule(setup.mode, setup.order, SystemClock.uptimeMillis() + EvaluationProtocol.SESSION_START_LEAD_MS, geometry)
            val info = sessionInfo(setup, geometry, basis, schedule.startMs, record, serial)
            val id = EvaluationLog.newSessionId(System.currentTimeMillis())
            val writer = EvaluationLog.writer
            val s = EvaluationSession(id, schedule, EvaluationLog.sessionDir(filesDir, setup.participant, id), info, record.model,
                EvaluationModelSnapshot.json(record, serial), writer,
                { delayMs, block -> writer.schedule(Runnable { block() }, delayMs, TimeUnit.MILLISECONDS) },
                System::currentTimeMillis,
                { failed, error ->
                    // Never keep running an evaluation that is not being recorded.
                    handler.post { if (EvaluationLog.current === failed) endRun(EvaluationEndReason.LOGGING_FAILED, "평가 기록 실패 · $error") }
                })
            s.open()
            session = s
            EvaluationLog.start(s)
            // One session per calibration, even if this session is interrupted.
            TrackerState.evaluatedCalibrationSerial = serial
            // Start from an empty pipeline so replay has the complete smoothing context.
            TrackerState.resetSmoothing?.invoke(EvaluationSession.RESET_SESSION_START)
            return s
        }

        private fun drawLine(canvas: Canvas, text: String, y: Float, size: Float) {
            paint.textSize = size
            val available = width - 32 * resources.displayMetrics.density
            val measured = paint.measureText(text)
            if (available > 0 && measured > available) paint.textSize = size * available / measured
            canvas.drawText(text, width / 2f, y, paint)
        }

        override fun onDraw(canvas: Canvas) {
            val s = session ?: return
            val now = SystemClock.uptimeMillis()
            val at = s.schedule.locate(now)
            val d = resources.displayMetrics.density
            val k = at?.blockIndex ?: 0
            val position = s.schedule.position(k)
            val blocks = EvaluationProtocol.BLOCKS
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            when (at?.phase ?: EvaluationPhase.PREPARATION) {
                EvaluationPhase.PREPARATION -> {
                    drawLine(canvas, "블록 ${k + 1}/$blocks 준비 · ${position.label}", 55 * d, 15 * d)
                    paint.color = Color.rgb(147, 197, 253)
                    val role = if (setup.planned.role == EvaluationSessionRole.TECHNICAL_PILOT) " · 기술 시험(공식 아님)" else ""
                    drawLine(canvas, "${setup.participant} ${setup.label} · 순서 ${setup.order.label}$role", 80 * d, 12 * d)
                    paint.color = Color.WHITE
                    val top = height * .36f
                    drawLine(canvas, position.instructions[0], top, 20 * d)
                    drawLine(canvas, position.instructions[1], top + 32 * d, 14 * d)
                    drawLine(canvas, "자세 기준: ${setup.positionMarkers}", top + 60 * d, 13 * d)
                    drawLine(canvas, "준비되면 '준비 완료'를 누르세요 · 누른 뒤 3초 후 시작합니다", top + 88 * d, 13 * d)
                    drawLine(canvas, "기기는 움직이지 마세요 · 중단하려면 뒤로 가기", height - 45 * d, 12 * d)
                }
                EvaluationPhase.COUNTDOWN -> {
                    drawLine(canvas, "블록 ${k + 1}/$blocks · ${position.label}", 55 * d, 15 * d)
                    val secondsLeft = ((-(at!!.blockElapsedMs ?: 0L) + 999) / 1000).coerceAtLeast(1)
                    drawLine(canvas, secondsLeft.toString(), height / 2f, 48 * d)
                    drawLine(canvas, "점이 나타나면 머리를 지금 자세로 고정하고 바라보세요", height / 2f + 44 * d, 14 * d)
                }
                EvaluationPhase.SETTLING, EvaluationPhase.MEASUREMENT -> {
                    val p = at!!.presentation!!
                    drawLine(canvas, "${setup.mode.label} · 블록 ${k + 1}/$blocks · ${position.label} · ${p.pass}차 " +
                        "${p.indexInPass + 1}/${EvaluationProtocol.firstPass.size}", 55 * d, 13 * d)
                    drawLine(canvas, "머리는 지금 자세로 고정하고 파란 점을 바라보세요 · 중단하려면 뒤로 가기", height - 45 * d, 12 * d)
                    val (x, y) = s.schedule.geometry.toView(p.target.x, p.target.y)
                    paint.color = Color.rgb(96, 165, 250)
                    canvas.drawCircle(x.toFloat(), y.toFloat(), 12 * d, paint)
                    paint.color = Color.WHITE; canvas.drawCircle(x.toFloat(), y.toFloat(), 3 * d, paint)
                }
            }
        }
    }
}
