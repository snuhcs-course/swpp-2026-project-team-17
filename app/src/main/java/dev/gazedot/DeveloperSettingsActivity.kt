package dev.gazedot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Local interaction settings. No learned models or network gaze correction. */
class DeveloperSettingsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status.text = if (granted) "마이크 권한 허용됨 · 추적 중지 후 홈에서 다시 시작하세요" else "마이크 권한이 없어 받아쓰기를 사용할 수 없습니다"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24*d).toInt(), (48*d).toInt(), (24*d).toInt(), (32*d).toInt())
        }
        status = TextView(this).apply { textSize = 18f; text = TrackerState.status }
        root.addView(status)
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        root.addView(TextView(this).apply {
            text = "9점 보정 후 사용하세요.\n1초 응시: 클릭 · 윙크: 스크롤 모드 · 양눈 두 번 깜빡임: 받아쓰기"
        })
        button("접근성 서비스 설정") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("받아쓰기 마이크 권한 허용") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                status.text = "마이크 권한이 이미 허용되어 있습니다"
            else microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
        root.addView(TextView(this).apply {
            text = "받아쓰기는 기기 내 인식을 우선합니다. 기기가 지원하지 않으면 시스템 음성 인식을 사용하며 제공자가 음성을 서버로 전송할 수 있습니다."
        })
        button("클릭·스크롤·받아쓰기 연습") { startActivity(Intent(this, PracticeActivity::class.java)) }
        button("일시정지한 조작 재개") {
            if (TrackerState.running) startForegroundService(Intent(this, TrackingService::class.java))
            else status.text = "홈에서 카메라를 먼저 시작하세요"
        }
        button("조작 일시정지") {
            if (TrackerState.running) startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_PAUSE))
        }
        button("추적 중지") { stopService(Intent(this, TrackingService::class.java)); status.text = "추적 중지됨" }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onStart() { super.onStart(); TrackerState.settingsStarted() }
    override fun onStop() { TrackerState.settingsStopped(); super.onStop() }
}
