package dev.gazedot

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.*
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
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
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closing = false
    private var landmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var overlay: DotOverlay? = null
    private val smoother = GazeSmoother()
    private var lastFrame = 0L
    private var lastResult = 0L
    private var initialRotation = Surface.ROTATION_0
    private var initialSize = ""
    private var receiverRegistered = false
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private fun rotation() = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
    private fun sizeKey(): String {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds.let { "${it.width()}x${it.height()}" }
        return resources.displayMetrics.let { "${it.widthPixels}x${it.heightPixels}" }
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
                resetSmoothing("watchdog_no_result"); overlay?.show(null)
                TrackerState.publish(null, "카메라 신호 대기 · 다른 카메라 앱을 닫아 주세요")
            }
            main.postDelayed(this, 400)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (TrackerState.running) return START_NOT_STICKY
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("tracking", "시선 추적", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "tracking")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("GazeDot 시선 추적 중")
                .setContentText("전면 카메라 사용 중 · 탭하여 보정 / 중지")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "중지", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(1, notification)
            overlay = DotOverlay(this)
            TrackerState.resetSmoothing = { reason -> resetSmoothing(reason) }
            TrackerState.overlayBounds = { overlay?.bounds() }
            initialRotation = rotation(); initialSize = sizeKey()
            displayManager.registerDisplayListener(displayListener, main)
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            TrackerState.running = true
            TrackerState.publish(null, "카메라·눈 검출 모델 준비 중…")
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
                        // Not a smoothing reset in this pipeline; logged so a run records that it happened.
                        (EvaluationLog.current ?: EvaluationLog.draining)?.event(SystemClock.uptimeMillis(), EvaluationSession.EVENT_CAMERA_ERROR, state.error?.type?.name ?: "")
                        overlay?.show(null)
                        TrackerState.publish(null, "카메라 사용 불가 · 다른 카메라 앱을 닫고 다시 시도해 주세요")
                    }
                }
            } catch (e: Exception) { fail("카메라 시작 실패: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(frame: ImageProxy) {
        var raw: Bitmap? = null; var upright: Bitmap? = null
        // Evaluation logging observes this path only; it stays null outside an evaluation run.
        var attempt: EvaluationAttempt? = null
        try {
            val now = SystemClock.uptimeMillis()
            val acceptedNanos = System.nanoTime()
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
            val extraction = result?.faceLandmarks()?.firstOrNull()?.let { LandmarkFeatures.extractDetailed(it, upright.width, upright.height) }
            val features = extraction?.features
            attempt?.observed(when {
                result == null -> EvaluationDetector.NOT_READY
                result.faceLandmarks().isEmpty() -> EvaluationDetector.NO_FACE
                else -> EvaluationDetector.FACE
            }, extraction?.rejection, features, if (result != null) detectNanos else null, System.nanoTime())
            val logged = attempt
            main.post {
                logged?.reachedMain(System.nanoTime())
                if (closing) { logged?.discarded(EvaluationOutcome.DISCARDED_CLOSING, SystemClock.uptimeMillis()); return@post }
                lastResult = SystemClock.uptimeMillis()
                // A run's smoothing context holds only its own logged results: an unlogged result accepted before
                // the run's planned end (in flight at run start) is dropped while that run is active, and counted.
                EvaluationLog.current?.let { s ->
                    if (logged == null && now < s.run.endMs) { s.unloggedResultDropped(now, SystemClock.uptimeMillis()); return@post }
                }
                val model = TrackerState.model
                val position = if (features != null) model?.predict(features) else null
                val message = when {
                    features == null -> "눈을 찾지 못했습니다 · 눈을 뜨고 카메라를 향해 주세요"
                    model == null -> "눈 검출됨 · 9점 보정을 시작해 주세요"
                    position == null -> "얼굴 위치가 달라졌습니다 · 원래 위치로 돌아오거나 재보정하세요"
                    else -> "추적 중 · 홈으로 나가도 빨간 점이 표시됩니다"
                }
                val smoothed = smoother.update(position)
                // Blind evaluation withholds the pointer from the participant; the coordinate itself is unchanged.
                val visible = if (EvaluationState.hidesPointer) null else smoothed
                overlay?.show(visible)
                TrackerState.publish(features, message,
                    smoothed?.let { ScreenGaze(it.first, it.second, now) })
                if (logged != null) {
                    val publishedNanos = System.nanoTime()
                    val at = SystemClock.uptimeMillis()
                    // A run measures exactly one model; the evaluation screen ends the run if it changes.
                    if (model == null || model !== logged.session.model) logged.discarded(EvaluationOutcome.DISCARDED_MODEL_CHANGED, at)
                    else logged.processed(at, features?.let { model.rejection(it) }, features?.let { model.linearOutput(it) },
                        position, smoothed, visible?.let { p -> overlay?.drawnAt(p) }, publishedNanos)
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

    private fun resetSmoothing(reason: String) {
        // Replay needs every smoothing reset of a run, in main-thread order.
        (EvaluationLog.current ?: EvaluationLog.draining)?.smoothingReset(SystemClock.uptimeMillis(), reason)
        smoother.reset()
    }

    private fun fail(message: String) {
        if (closing) return
        TrackerState.publish(null, message)
        stopSelf()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (rotation() != initialRotation || sizeKey() != initialSize) fail("화면 변경 · 앱에서 다시 보정해 주세요")
    }
    override fun onDestroy() {
        closing = true
        // A run cannot continue without its camera; it is finalized as incomplete.
        EvaluationLog.finish(EvaluationEndReason.SERVICE_STOPPED, SystemClock.uptimeMillis())
        TrackerState.resetSmoothing = null; TrackerState.overlayBounds = null
        TrackerState.clearCalibration()
        main.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(displayListener)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        analysis?.clearAnalyzer()
        analysis?.let { cameraProvider?.unbind(it) }
        overlay?.close()
        worker.execute { landmarker?.close(); landmarker = null }
        worker.shutdown()
        TrackerState.running = false; TrackerState.model = null
        TrackerState.publish(null, if (TrackerState.status.startsWith("추적 중")) "추적 중지됨" else TrackerState.status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "dev.gazedot.STOP"
        /** Minimum spacing between frames accepted for processing; later frames are skipped. */
        const val FRAME_INTERVAL_MS = 66L
        /** The watchdog clears the smoothing state when no result arrived for longer than this. */
        const val WATCHDOG_NO_RESULT_MS = 800L
    }
}
