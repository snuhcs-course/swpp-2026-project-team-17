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
    private val lossHelp = RecognitionLossPolicy()
    private var startedAt = 0L
    private var recoveryOpenedAt = 0L
    private var cameraFault: TrackingFault? = null
    private var helpGeneration = 0L
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
            if (!Settings.canDrawOverlays(this@TrackingService)) {
                fail("다른 앱 위에 표시 권한이 해제되었어요", RecoverySetting.OVERLAY); return
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                fail("카메라 권한이 필요해요", RecoverySetting.CAMERA); return
            }
            val now = SystemClock.uptimeMillis()
            if (TrackerState.recoveryOpening && now - recoveryOpenedAt > 5_000) {
                TrackerState.recoveryOpening = false
                TrackerState.cancelActions?.invoke()
            }
            val waiting = if (lastResult == 0L) now - startedAt > 3_000 else now - lastResult > 250
            if (waiting && !TrackerState.signalPending) {
                resetTracking()
                TrackerState.signalPending = true
                TrackerState.publish(null, "영상 입력을 기다리고 있어요")
            }
            updateHelp(now)
            renderOverlay(now)
            main.postDelayed(this, 100)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            TrackerState.fault = null
            closing = true; clearHelp(resetEpisode = true); resetTracking(); stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            if (!TrackerState.running) { stopSelf(); return START_NOT_STICKY }
            pauseControls()
            return START_NOT_STICKY
        }
        if (TrackerState.running) {
            if (TrackerState.fault?.setting == RecoverySetting.ACCESSIBILITY && ControlAccessibilityService.instance != null)
                TrackerState.fault = null
            TrackerState.controls = true
            clearHelp(resetEpisode = true)
            pipeline.cancelInteraction()
            TrackerState.actionStatus = "눈 조작 준비 · 연습 화면 또는 다른 앱에서 사용하세요"
            updateNotification()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) { stopSelf(); return START_NOT_STICKY }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("tracking", "시선 추적", NotificationManager.IMPORTANCE_LOW))
            notifications.cancel(ERROR_NOTIFICATION_ID)
            TrackerState.fault = null
            TrackerState.signalPending = false
            val notification = notification(paused = false)
            TrackerState.microphoneReady = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (TrackerState.microphoneReady) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
            else startForeground(1, notification)
            voice = VoiceInput(this)
            TrackerState.cancelPendingClick = { pipeline.cancelPendingClick() }
            TrackerState.cancelActions = {
                trackingEpoch++
                pipeline.cancelInteraction()
                TrackerState.localDwellProgress = 0f
                TrackerState.localScrollMode = false
                if (::voice.isInitialized) voice.cancel()
                TrackerState.actionStatus = ""
            }
            TrackerState.contextChanged = {
                if (!closing) {
                    updateHelp(SystemClock.uptimeMillis()); renderOverlay(SystemClock.uptimeMillis())
                    updateNotification()
                }
            }
            overlay = DotOverlay(this)
            initialRotation = rotation(); initialSize = sizeKey()
            displayManager.registerDisplayListener(displayListener, main)
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            TrackerState.controls = true
            TrackerState.running = true
            startedAt = SystemClock.uptimeMillis()
            TrackerState.publish(null, "카메라·눈 검출 모델 준비 중…")
            TrackerState.actionStatus = "눈 조작 준비 · 접근성 권한을 허용하고 연습 화면에서 확인하세요"
            main.postDelayed(watchdog, 100)
            worker.execute {
                try {
                    landmarker = FaceLandmarker.createFromOptions(this,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                            .setRunningMode(RunningMode.VIDEO).setNumFaces(1)
                            .setMinFaceDetectionConfidence(0.6f).setMinFacePresenceConfidence(0.6f)
                            .setMinTrackingConfidence(0.6f).build())
                    main.post { if (!closing) bindCamera() }
                } catch (e: Exception) { main.post { fail("눈 검출 모델을 시작하지 못했어요") } }
            }
        } catch (e: SecurityException) { fail("카메라·실행 권한을 확인해 주세요", RecoverySetting.CAMERA) }
        catch (e: Exception) { fail("추적을 시작하지 못했어요") }
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
                        val code = state.error!!.code
                        cameraFault = when (code) {
                            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> TrackingFault(
                                "카메라가 다른 곳에서 사용 중이에요", "카메라를 사용하는 다른 앱을 닫은 뒤 다시 시작해 주세요.")
                            CameraState.ERROR_CAMERA_DISABLED -> TrackingFault(
                                "시스템에서 카메라 사용을 막고 있어요", "기기의 카메라 사용 제한을 확인한 뒤 다시 시작해 주세요.", RecoverySetting.CAMERA)
                            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> TrackingFault(
                                "방해 금지 모드로 카메라를 열지 못했어요", "방해 금지 모드를 확인한 뒤 다시 시작해 주세요.")
                            else -> TrackingFault("카메라를 사용할 수 없어요",
                                "시스템 카메라 오류 ($code). 카메라를 다시 시작해 주세요.")
                        }
                        TrackerState.fault = cameraFault
                        clearHelp(resetEpisode = true)
                        resetTracking()
                        TrackerState.publish(null, TrackerState.fault!!.message)
                        renderOverlay(SystemClock.uptimeMillis())
                        updateNotification()
                    } else if (!closing && state.type == CameraState.Type.OPEN) {
                        if (TrackerState.fault === cameraFault) TrackerState.fault = null
                        cameraFault = null
                        updateNotification()
                    }
                }
            } catch (e: SecurityException) { fail("카메라 권한이 필요해요", RecoverySetting.CAMERA) }
            catch (e: Exception) { fail("카메라를 시작하지 못했어요") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(frame: ImageProxy) {
        var raw: Bitmap? = null; var upright: Bitmap? = null
        val epoch = trackingEpoch
        try {
            val now = SystemClock.uptimeMillis()
            if (closing || now - lastFrame < 66) return
            lastFrame = now
            raw = frame.toBitmap()
            val matrix = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
            upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            val image = BitmapImageBuilder(upright).build()
            val result = try { landmarker?.detectForVideo(image, now) } finally { image.close() }
            val observation = result?.faceLandmarks()?.firstOrNull()?.let {
                EyeObservations.observe(it, upright.width, upright.height)
            } ?: EyeObservation(EyeState.LOST, null, 0.0, 0.0)
            main.post {
                if (closing || epoch != trackingEpoch || cameraFault != null) return@post
                val at = SystemClock.uptimeMillis()
                if (at - now > 250) {
                    resetTracking()
                    TrackerState.signalPending = true
                    TrackerState.publish(null, "영상 처리가 지연됩니다 · 조작 일시 대기")
                    return@post
                }
                lastResult = at
                TrackerState.signalPending = false
                TrackerState.frameAt = now
                TrackerState.eyeState = observation.state
                val model = TrackerState.model
                val enabled = TrackerState.externalActionsAllowed() &&
                    !TrackerState.speaking && ControlAccessibilityService.instance != null
                val sample = pipeline.process(now, at, observation, model, enabled,
                    ControlAccessibilityService.instance?.let { service -> { p -> service.dwellTargetKey(p) } })
                val message = when (sample.quality) {
                    TrackingQuality.LOST -> "얼굴 검출 대기"
                    TrackingQuality.CLOSED -> "눈 감김 감지"
                    TrackingQuality.UNCERTAIN -> "눈 상태 확인 중"
                    TrackingQuality.STALE -> "영상 처리가 지연됩니다 · 조작 일시 대기"
                    TrackingQuality.OUT_OF_RANGE -> if (model == null) "눈 검출됨 · 9점 보정을 시작해 주세요"
                        else "시선 좌표를 사용할 수 없어요 · 얼굴 위치를 확인해 주세요"
                    TrackingQuality.TRACKING -> "추적 중 · 홈으로 나가도 빨간 점이 표시됩니다"
                }
                TrackerState.publishSample(sample, message)
                updateHelp(at)
                // Publishing can navigate, pause, or start calibration. Recheck before dispatch.
                if (!closing && epoch == trackingEpoch && TrackerState.externalActionsAllowed())
                    sample.actions.forEach(::perform)
                renderOverlay(at)
            }
        } catch (e: Exception) {
            main.post { if (!closing && epoch == trackingEpoch) fail("영상 처리 중 오류가 발생했어요") }
        } finally {
            if (upright !== raw) upright?.recycle()
            raw?.recycle()
            frame.close()
        }
    }

    private fun perform(action: EyeAction) {
        if (!TrackerState.externalActionsAllowed()) return
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

    private fun recoveryPendingIntent() = PendingIntent.getActivity(this, 3,
        RecoveryActivity.externalIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(paused: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val toggle = PendingIntent.getService(this, if (paused) 4 else 2,
            Intent(this, TrackingService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE), flags)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP), flags)
        return Notification.Builder(this, "tracking")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(if (paused) "NoTouch 조작 일시정지" else "NoTouch 시선 추적")
            .setContentText(TrackerState.fault?.message ?: if (paused) "‘조작 재개’를 누르면 다시 사용할 수 있어요" else "카메라 사용 중 · 도움이 필요하면 눌러 주세요")
            .setContentIntent(recoveryPendingIntent()).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "도움", recoveryPendingIntent()).build())
            .addAction(Notification.Action.Builder(null, if (paused) "조작 재개" else "조작 일시정지", toggle).build())
            .addAction(Notification.Action.Builder(null, "중지", stop).build()).build()
    }

    private fun updateNotification() {
        if (!closing) runCatching {
            getSystemService(NotificationManager::class.java).notify(1, notification(!TrackerState.controls))
        }
    }

    private fun clearHelp(resetEpisode: Boolean = false) {
        helpGeneration++
        val wasVisible = TrackerState.helpVisible
        TrackerState.helpVisible = false
        overlay?.hideHelp()
        if (resetEpisode) lossHelp.reset() else lossHelp.suspend()
        if (wasVisible) TrackerState.cancelActions?.invoke()
    }

    private fun updateHelp(now: Long) {
        val state = TrackerState.currentStatus(now)
        val eligible = !closing && TrackerState.recognitionHelpEligible(ControlAccessibilityService.instance != null)
        val missing = state.phase == TrackingPhase.MISSING
        val sampleAt = TrackerState.sample?.timestampMs
        if (!eligible || !missing) {
            if (TrackerState.helpVisible) clearHelp()
        }
        if (lossHelp.update(now, sampleAt, missing, eligible)) {
            val session = ++helpGeneration
            TrackerState.helpVisible = true
            TrackerState.cancelActions?.invoke()
            try {
                overlay?.showHelp(onTroubleshoot = openHelp@{
                    if (closing || !TrackerState.helpVisible || session != helpGeneration) return@openHelp
                    // Cancel before navigation; do not allow gaze to land in the old app in transit.
                    TrackerState.recoveryOpening = true
                    recoveryOpenedAt = SystemClock.uptimeMillis()
                    clearHelp()
                    try { startActivity(RecoveryActivity.externalIntent(this)) }
                    catch (e: RuntimeException) { TrackerState.recoveryOpening = false; updateNotification() }
                }, onPause = {
                    if (!closing && TrackerState.helpVisible && session == helpGeneration) pauseControls()
                })
            } catch (e: RuntimeException) {
                // A system window may disallow overlays. Home and the notification remain usable.
                clearHelp()
                updateNotification()
            }
        }
    }

    private fun pauseControls() {
        TrackerState.controls = false
        clearHelp(resetEpisode = true)
        resetTracking()
        TrackerState.actionStatus = "조작 일시정지 · 홈이나 알림에서 조작 재개를 선택해 주세요"
        TrackerState.notifyListeners()
        updateNotification()
    }

    private fun renderOverlay(now: Long) {
        if (closing) return
        val state = TrackerState.currentStatus(now)
        val enabled = TrackerState.externalActionsAllowed() && !TrackerState.speaking
        val showPoint = TrackerState.controls && !TrackerState.collecting && !TrackerState.helpVisible &&
            !TrackerState.recoveryOpening && state.phase == TrackingPhase.TRACKING
        overlay?.show(if (showPoint) TrackerState.sample?.point else null,
            if (enabled) TrackerState.sample?.dwellProgress ?: 0f else TrackerState.localDwellProgress,
            if (TrackerState.interactionAllowed) state.overlayMessage.ifBlank {
                if (TrackerState.speaking) TrackerState.actionStatus else ""
            } else "",
            if (enabled) TrackerState.sample?.scrollMode == true else TrackerState.localScrollMode)
    }

    private fun resetTracking() {
        trackingEpoch++
        pipeline.reset()
        TrackerState.clearCoordinates()
        if (::voice.isInitialized) voice.cancel()
        overlay?.show(null)
    }

    private fun fail(message: String, setting: RecoverySetting = RecoverySetting.NONE) {
        if (closing) return
        TrackerState.fault = TrackingFault(message, when (setting) {
            RecoverySetting.CAMERA -> "앱 설정에서 카메라 권한을 확인한 뒤 카메라를 다시 시작해 주세요."
            RecoverySetting.OVERLAY -> "‘다른 앱 위에 표시’를 허용한 뒤 카메라를 다시 시작해 주세요."
            else -> "NoTouch에서 카메라를 다시 시작해 주세요. 자동으로 보정을 시작하지 않아요."
        }, setting)
        clearHelp(resetEpisode = true)
        resetTracking()
        closing = true
        TrackerState.publish(null, message)
        // The foreground notification is removed on stop; keep a separate recovery entry.
        runCatching { getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID,
            Notification.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(message).setContentText("눌러서 해결 방법 보기")
                .setContentIntent(recoveryPendingIntent()).setAutoCancel(true).build()) }
        stopSelf()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (rotation() != initialRotation || sizeKey() != initialSize) fail("화면 변경 · 앱에서 다시 보정해 주세요")
    }
    override fun onDestroy() {
        closing = true
        TrackerState.contextChanged = null
        clearHelp(resetEpisode = true)
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
        const val ACTION_STOP = "dev.gazedot.STOP"
        const val ACTION_PAUSE = "dev.gazedot.PAUSE"
        const val ACTION_RESUME = "dev.gazedot.RESUME"
        private const val ERROR_NOTIFICATION_ID = 2
    }
}
