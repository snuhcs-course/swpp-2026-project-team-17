package dev.gazedot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Local interaction and personal-model settings; no network inference. */
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
        button("도움 · 인식 문제 해결하기") { startActivity(Intent(this, RecoveryActivity::class.java)) }
        button("시선 모델 상태 · iTracker") { status.text=TrackerState.inferenceStatus+"\n사전학습 가중치 고정 · 5점은 화면 좌표 맞춤만 수행" }
        button("시선 원인 진단 · 6초 고정 응시") { startActivity(Intent(this,GazeDiagnosticActivity::class.java)) }
        button("최근 calibration 품질 결과") { status.text=getSharedPreferences("personal",MODE_PRIVATE).getString("calibration_audit","새 버전에서 보정한 기록이 없습니다") }
        button("최근 시선 진단 결과 공유") {
            val data=getSharedPreferences("personal",MODE_PRIVATE).getString("gaze_diagnostic",null)
            if(data==null) status.text="진단 기록 없음" else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,data)},"진단 결과 공유"))
        }
        button("최근 영상 처리 오류") {
            status.text=getSharedPreferences("personal",MODE_PRIVATE).getString("last_frame_error","기록된 오류 없음")
        }
        root.addView(TextView(this).apply {text="시선 모델: iTracker / Eye Tracking for Everyone (Krafka et al., CVPR 2016). 연구용 모델 · 폰 안에서 실행합니다."})
        button("홍채 기준 측정 결과") {
            val prefs=getSharedPreferences("personal",MODE_PRIVATE)
            status.text=prefs.getString("eye_profile_report","홍채 측정 전")
        }
        val personal=getSharedPreferences("personal",MODE_PRIVATE)
        root.addView(Switch(this).apply {
            text="개인화 버튼 추천·확인 학습"; isChecked=personal.getBoolean("enabled",true)
            setOnCheckedChangeListener { _,enabled ->
                personal.edit().putBoolean("enabled",enabled).apply()
                ControlAccessibilityService.instance?.closeSelection()
            }
        })
        root.addView(TextView(this).apply { text="기기 내 학습: 후보 중 직접 확인한 선택만 학습합니다. 추천창 밖을 본 뒤 항목을 1초 바라보거나 터치하세요. 화면·입력 내용은 서버로 보내지 않습니다." })
        button("정확도 추가 보정 · 5점 학습 + 4점 확인") { startActivity(Intent(this,AccuracyActivity::class.java)) }
        button("추가 정확도 보정만 해제") {
            TrackerState.model=TrackerState.model?.withCorrection(null)
            TrackerState.cancelActions?.invoke(); TrackerState.clearCoordinates()
            status.text="기본 5점 보정으로 돌아왔습니다"
        }
        button("최근 정확도 결과") { status.text=personal.getString("accuracy_report","측정 기록 없음") }
        button("버튼 학습 횟수 확인") {
            val model=PersonalTargetRanker().apply { restore(personal.getString("ranker","").orEmpty()) }
            status.text="명시적으로 확인한 선택 ${model.confirmations}회 · 폰 안에 저장됨"
        }
        button("버튼 선택 학습 초기화") {
            ControlAccessibilityService.instance?.closeSelection(); personal.edit().remove("ranker").apply()
            status.text="버튼 선택 학습을 초기화했습니다"
        }
        root.addView(TextView(this).apply {
            text = "5점 보정 후 사용하세요.\n1초 응시: 클릭 · 윙크: 스크롤 모드 · 양눈 두 번 깜빡임: 받아쓰기"
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
