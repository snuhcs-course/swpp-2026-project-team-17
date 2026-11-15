package dev.gazedot

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.*
import android.provider.Settings
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors

class TrackingService : LifecycleService() {
    private val pipeline = GazePipeline()
    private lateinit var voice: VoiceInput
    @Volatile private var trackingEpoch = 0L
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closing = false
    private var landmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var overlay: DotOverlay? = null
    private var lastFrame = 0L
    private var lastResult = 0L
    private var initialRotation = Surface.ROTATION_0
    private var initialSize = ""
    private var receiverRegistered = false
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private fun rotation() = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
    private fun sizeKey(): String {
        return DisplaySpace.bounds(this).let { "${it.width()}x${it.height()}" }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (rotation() != initialRotation || sizeKey() != initialSize) fail("화면 크기·방향이 바뀌었습니다. 앱에서 다시 보정해 주세요.")
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { fail("화면 꺼짐 · 앱에서 다시 시작해 주세요") }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (closing) return
            if (!Settings.canDrawOverlays(this@TrackingService)) { fail("다른 앱 위에 표시 권한이 해제되었습니다"); return }
            if (SystemClock.uptimeMillis() - lastResult > WATCHDOG_NO_RESULT_MS) {
                resetTracking("watchdog_no_result")
                TrackerState.publish(null, "카메라 신호 대기 · 다른 카메라 앱을 닫아 주세요")
            }
            main.postDelayed(this, 400)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { closing = true; resetTracking("stop_action"); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_PAUSE) {
            TrackerState.controls = false
            resetTracking("pause_action")
            TrackerState.actionStatus = "조작 일시정지 · 앱에서 다시 켤 수 있습니다"
            return START_NOT_STICKY
        }
        if (TrackerState.running) {
            TrackerState.controls = true
            pipeline.cancelInteraction()
            TrackerState.actionStatus = "눈 조작 준비 · 연습 화면 또는 다른 앱에서 사용하세요"
            return START_NOT_STICKY
        }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("tracking", "시선 추적", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val pause = PendingIntent.getService(this, 2, Intent(this, TrackingService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "tracking")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("NoTouch 시선 추적 중")
                .setContentText("카메라 사용 중 · 눈 조작 / 음성 입력 준비")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "조작 일시정지", pause).build())
                .addAction(Notification.Action.Builder(null, "중지", stop).build()).build()
            TrackerState.microphoneReady = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (TrackerState.microphoneReady) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
            else startForeground(1, notification)
            voice = VoiceInput(this)
            TrackerState.cancelPendingClick = { pipeline.cancelPendingClick() }
            TrackerState.cancelActions = {
                pipeline.cancelInteraction()
                TrackerState.localDwellProgress = 0f
                TrackerState.localScrollMode = false
                if (::voice.isInitialized) voice.cancel()
                TrackerState.actionStatus = ""
            }
            overlay = DotOverlay(this)
            // Evaluation hooks: a logged pipeline reset and the overlay geometry. Unused outside an evaluation session.
            TrackerState.resetSmoothing = { reason -> evaluationReset(reason) }
            TrackerState.overlayBounds = { overlay?.bounds() }
            initialRotation = rotation(); initialSize = sizeKey()
            displayManager.registerDisplayListener(displayListener, main)
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            TrackerState.controls = true
            TrackerState.running = true
            TrackerState.publish(null, "카메라·눈 검출 모델 준비 중…")
            TrackerState.actionStatus = "눈 조작 준비 · 접근성 권한을 허용하고 연습 화면에서 확인하세요"
            main.postDelayed(watchdog, 3000)
            worker.execute {
                try {
                    landmarker = FaceLandmarker.createFromOptions(this,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                            .setRunningMode(RunningMode.VIDEO).setNumFaces(1)
                            .setMinFaceDetectionConfidence(0.6f).setMinFacePresenceConfidence(0.6f)
                            .setMinTrackingConfidence(0.6f).build())
                    main.post { if (!closing) bindCamera() }
                } catch (e: Exception) { main.post { fail("모델 초기화 실패: ${e.message}") } }
            }
        } catch (e: Exception) { fail("시작 실패: ${e.message}") }
        // Camera services must be explicitly started from a visible activity; never auto-restart.
        return START_NOT_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (closing) return@addListener
            try {
                val provider = future.get(); cameraProvider = provider
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) { fail("전면 카메라가 없습니다"); return@addListener }
                @Suppress("DEPRECATION")
                val analyzer = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480))
                    .setTargetRotation(initialRotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis = analyzer
                analyzer.setAnalyzer(worker) { frame -> process(frame) }
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analyzer)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    if (state.error != null && !closing) {
                        (EvaluationLog.current ?: EvaluationLog.draining)?.event(SystemClock.uptimeMillis(),
                            EvaluationSession.EVENT_CAMERA_ERROR, state.error?.type?.name ?: "")
                        resetTracking("camera_error")
                        TrackerState.publish(null, "카메라 사용 불가 · 다른 카메라 앱을 닫고 다시 시도해 주세요")
                    }
                }
            } catch (e: Exception) { fail("카메라 시작 실패: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(frame: ImageProxy) {
        var raw: Bitmap? = null; var upright: Bitmap? = null
        // Evaluation logging observes this path only; it stays null outside an evaluation session.
        var attempt: EvaluationAttempt? = null
        try {
            val now = SystemClock.uptimeMillis()
            val acceptedNanos = System.nanoTime()
            val epoch = trackingEpoch
            val log = EvaluationLog.sessionAt(now)
            log?.frameReceived()
            if (closing || now - lastFrame < FRAME_INTERVAL_MS) { log?.frameSkipped(closing); return }
            lastFrame = now
            attempt = log?.beginAttempt(now, acceptedNanos)
            raw = frame.toBitmap()
            val matrix = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
            upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            val image = BitmapImageBuilder(upright).build()
            val detectStart = System.nanoTime()
            val result = try { landmarker?.detectForVideo(image, now) } finally { image.close() }
            val detectNanos = System.nanoTime() - detectStart
            val landmarks = result?.faceLandmarks()?.firstOrNull()
            val observation = landmarks?.let {
                EyeObservations.observe(it, upright.width, upright.height)
            } ?: EyeObservation(EyeState.LOST, null, 0.0, 0.0)
            attempt?.observed(
                when { result == null -> EvaluationDetector.NOT_READY; landmarks == null -> EvaluationDetector.NO_FACE
                    else -> EvaluationDetector.FACE },
                if (observation.features == null && landmarks != null)
                    EvaluationDiagnostics.featureRejection(landmarks, upright.width, upright.height) else null,
                observation, if (result != null) detectNanos else null, System.nanoTime())
            val logged = attempt
            main.post {
                logged?.reachedMain(System.nanoTime())
                if (closing) { logged?.discarded(EvaluationOutcome.DISCARDED_CLOSING, null, SystemClock.uptimeMillis()); return@post }
                if (epoch != trackingEpoch) { logged?.discarded(EvaluationOutcome.DISCARDED_EPOCH, null, SystemClock.uptimeMillis()); return@post }
                val at = SystemClock.uptimeMillis()
                if (at - now > DELAYED_RESULT_MS) {
                    logged?.discarded(EvaluationOutcome.DISCARDED_DELAYED, at, at)
                    resetTracking("delayed_result")
                    TrackerState.publish(null, "영상 처리가 지연됩니다 · 조작 일시 대기")
                    return@post
                }
                lastResult = at
                // Evaluation only: the block-start reset, and dropping results in flight across a session or block start.
                EvaluationLog.current?.let { s -> if (!s.admit(logged, now, at) { reason -> evaluationReset(reason) }) return@post }
                TrackerState.frameAt = now
                TrackerState.eyeState = observation.state
                val model = TrackerState.model
                val enabled = TrackerState.gazeActionsEnabled(ControlAccessibilityService.instance != null)
                val sample = pipeline.process(now, at, observation, model, enabled,
                    ControlAccessibilityService.instance?.let { service -> { p -> service.dwellTargetKey(p) } })
                sample.actions.forEach(::perform)
                val message = when (sample.quality) {
                    TrackingQuality.LOST -> "얼굴 검출 대기"
                    TrackingQuality.CLOSED -> "눈 감김 감지"
                    TrackingQuality.UNCERTAIN -> "눈 상태 확인 중"
                    TrackingQuality.STALE -> "영상 처리가 지연됩니다 · 조작 일시 대기"
                    TrackingQuality.OUT_OF_RANGE -> if (model == null) "눈 검출됨 · 9점 보정을 시작해 주세요"
                        else "얼굴 위치가 달라졌습니다 · 원래 위치로 돌아오거나 재보정하세요"
                    TrackingQuality.TRACKING -> "추적 중 · 홈으로 나가도 빨간 점이 표시됩니다"
                }
                val label = when {
                    TrackerState.collecting -> ""
                    EvaluationState.active -> ""
                    TrackerState.speaking -> TrackerState.actionStatus
                    enabled && sample.scrollMode -> "스크롤 모드 · 위/아래/좌/우 1/3 응시 · 윙크하면 종료"
                    enabled -> TrackerState.actionStatus
                    else -> ""
                }
                // Blind evaluation withholds the pointer from the participant; the coordinate itself is unchanged.
                val visible = if (TrackerState.collecting || EvaluationState.hidesPointer) null else sample.point
                TrackerState.publishSample(sample, message)
                // UI listeners may select Stop or start calibration while handling this frame.
                if (!closing && TrackerState.running) overlay?.show(
                    if (TrackerState.collecting) null else visible,
                    if (enabled) sample.dwellProgress else if (EvaluationState.active) 0f else TrackerState.localDwellProgress,
                    label,
                    scrollMode = if (enabled) sample.scrollMode else TrackerState.localScrollMode && !EvaluationState.active)
                if (logged != null) {
                    val publishedNanos = System.nanoTime()
                    val result = SystemClock.uptimeMillis()
                    // A session measures exactly one model; the evaluation screen ends the session if it changes.
                    if (model == null || model !== logged.session.model) logged.discarded(EvaluationOutcome.DISCARDED_MODEL_CHANGED, at, result)
                    else logged.processed(at, result, sample.quality, sample.features?.let { model.rejection(it) },
                        sample.features?.let { model.linearOutput(it) }, sample.rawPoint, sample.point,
                        visible?.let { p -> overlay?.drawnAt(p) }, publishedNanos)
                }
            }
        } catch (e: Exception) {
            attempt?.failed()
            main.post { if (!closing) fail("영상 처리 실패: ${e.message}") }
        } finally {
            if (upright !== raw) upright?.recycle()
            raw?.recycle()
            frame.close()
        }
    }

    private fun perform(action: EyeAction) {
        // Explicit evaluation guard: no gaze click, scroll or dictation during a session, whatever produced the action.
        if (EvaluationState.suppressesActions) return
        val service = ControlAccessibilityService.instance ?: return
        val bounds = DisplaySpace.bounds(this)
        when(action) {
            is EyeAction.Click -> {
                val ok = service.tap((action.point.first * bounds.width()).toFloat(), (action.point.second * bounds.height()).toFloat())
                TrackerState.actionStatus = if(ok) "클릭 요청" else "클릭을 실행하지 못했습니다"
            }
            is EyeAction.Scroll -> {
                val ok = service.scroll(action.direction, bounds.width(), bounds.height())
                TrackerState.actionStatus = if(ok) when (action.direction) {
                    ScrollDirection.UP -> "위로 스크롤"
                    ScrollDirection.DOWN -> "아래로 스크롤"
                    ScrollDirection.LEFT -> "왼쪽으로 스크롤"
                    ScrollDirection.RIGHT -> "오른쪽으로 스크롤"
                } else "스크롤을 실행하지 못했습니다"
            }
            is EyeAction.ScrollMode -> {
                TrackerState.actionStatus = if (action.enabled) "스크롤 모드 켜짐" else "스크롤 모드 꺼짐"
            }
            EyeAction.Dictate -> voice.start()
            EyeAction.Cancel -> { TrackerState.actionStatus = "" }
        }
    }

    private fun resetTracking(reason: String) {
        // Replay needs every pipeline reset of an evaluation session, in main-thread order.
        (EvaluationLog.current ?: EvaluationLog.draining)?.pipelineReset(SystemClock.uptimeMillis(), reason)
        trackingEpoch++
        pipeline.reset()
        TrackerState.clearCoordinates()
        if (::voice.isInitialized) voice.cancel()
        overlay?.show(null)
    }

    /**
     * Evaluation only (session and block starts): clears the pipeline's smoothing state through its own reset, without
     * the epoch change, coordinate clearing or overlay hiding of [resetTracking]. Logged for replay.
     */
    private fun evaluationReset(reason: String) {
        (EvaluationLog.current ?: EvaluationLog.draining)?.pipelineReset(SystemClock.uptimeMillis(), reason)
        pipeline.reset()
    }

    private fun fail(message: String) {
        if (closing) return
        resetTracking("service_failure")
        closing = true
        TrackerState.publish(null, message)
        stopSelf()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (rotation() != initialRotation || sizeKey() != initialSize) fail("화면 변경 · 앱에서 다시 보정해 주세요")
    }
    override fun onDestroy() {
        closing = true
        // A session cannot continue without its camera; it is finalized as incomplete.
        EvaluationLog.finish(EvaluationEndReason.SERVICE_STOPPED, SystemClock.uptimeMillis())
        TrackerState.resetSmoothing = null; TrackerState.overlayBounds = null
        main.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(displayListener)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        analysis?.clearAnalyzer()
        analysis?.let { cameraProvider?.unbind(it) }
        pipeline.reset()
        TrackerState.trackingStopped()
        TrackerState.model = null
        if (::voice.isInitialized) voice.cancel()
        TrackerState.cancelActions = null
        TrackerState.cancelPendingClick = null
        TrackerState.microphoneReady = false
        TrackerState.controls = false
        overlay?.close()
        worker.execute { landmarker?.close(); landmarker = null }
        worker.shutdown()
        TrackerState.publish(null, if (TrackerState.status.startsWith("추적 중")) "추적 중지됨" else TrackerState.status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "dev.gazedot.STOP"; const val ACTION_PAUSE = "dev.gazedot.PAUSE"
        /** Minimum spacing between frames accepted for processing; later frames are skipped. */
        const val FRAME_INTERVAL_MS = 66L
        /** The watchdog resets the pipeline when no result arrived for longer than this. */
        const val WATCHDOG_NO_RESULT_MS = 800L
        /** Results handled later than this after acceptance are dropped and reset the pipeline. */
        const val DELAYED_RESULT_MS = 250L
    }
}
