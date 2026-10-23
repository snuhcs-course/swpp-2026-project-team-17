package dev.gazedot

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class DeveloperSettingsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startTracking()
        else status.text = "다른 앱 위에 표시 권한을 허용한 뒤 다시 시작하세요."
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(4096)
                while (true) {
                    val n = reader.read(buffer); if (n < 0) break
                    result.append(buffer, 0, n)
                    require(result.length <= 200_000) { "모델 파일이 너무 큽니다" }
                }
                result.toString()
            }
            val model = ModelStore.import(this, text)
            TrackerState.cancelActions?.invoke()
            TrackerState.model = model
            TrackerState.status = "학습 모델 불러오기 완료 · 조작 연습에서 확인하세요"
        }.onFailure { TrackerState.status = "모델 불러오기 실패: ${it.message}" }
        showHome()
    }
    private val exportReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) runCatching { contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(File(filesDir,"depth-evaluation.json").readText()) } }
            .onFailure { TrackerState.status="평가 내보내기 실패: ${it.message}" }
        showHome()
    }
    private var training=false
    private val exportData = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)!!.bufferedWriter().use { writer ->
                var first = true
                DepthTraining.folder(this).listFiles()?.filter { it.extension == "csv" }?.sortedBy { it.name }?.forEach { file ->
                    file.bufferedReader().useLines { lines -> lines.forEachIndexed { index, line ->
                        if (first || index > 0) { writer.write(line); writer.newLine() }
                    } }
                    first = false
                }
            }
            TrackerState.status = "학습 CSV 내보내기 완료"
        }.onFailure { TrackerState.status = "내보내기 실패: ${it.message}" }
        showHome()
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
    }
    override fun onResume() {
        super.onResume()
        TrackerState.interactionAllowed = false
        TrackerState.cancelActions?.invoke()
        TrackerState.listener = {
            if (::status.isInitialized) status.text = "${TrackerState.status}\n${TrackerState.actionStatus}"
        }
        showHome()
    }
    override fun onPause() { TrackerState.listener = null; super.onPause() }
    override fun onStart() { super.onStart(); TrackerState.settingsStarted() }
    override fun onStop() { TrackerState.settingsStopped(); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun showHome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(52), dp(26), dp(36))
            setBackgroundColor(Color.rgb(245, 247, 251))
        }
        fun text(value: String, size: Float, color: Int = Color.rgb(28, 37, 53)) = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(color)
            setPadding(0, dp(10), 0, dp(10)); root.addView(this)
        }
        text("NoTouch", 14f, Color.rgb(213, 41, 59))
        text("눈으로 움직이는\n빨간 점", 32f)
        text("설정 · 개발용 학습 · GPT 실험", 15f)
        status = text(TrackerState.status, 17f)
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                this.text = label; isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
        }
        button("시작 / 일시정지한 조작 재개") { startTracking() }
        button("GPT-5.4 눈/머리 · 화면 보정 실험 설정") { startActivity(Intent(this, CloudSettingsActivity::class.java)) }
        text("모델: ${TrackerState.modelLabel}", 14f)
        button("학습된 모델 JSON 불러오기") { importModel.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        button("접근성 서비스 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        text("카메라를 시작하면 눈 조작이 자동으로 준비됩니다. 접근성 권한을 허용한 뒤 연습 화면이나 다른 앱에서 사용하세요.\n같은 대상을 1초 응시: 클릭 · 한쪽 눈 윙크: 스크롤 모드 켜기/끄기 · 빠르게 양눈 두 번 깜빡임: 받아쓰기", 15f)
        val options = getSharedPreferences("options", MODE_PRIVATE)
        text("빠른 두 번 깜빡임으로 받아쓰기를 시작합니다. 기기 내 인식을 우선 사용하며, 미지원 기기는 시스템 음성 인식을 사용합니다. 시스템 제공자가 음성을 서버로 전송할 수 있습니다.", 13f)
        button("클릭·스크롤·받아쓰기 연습 화면") { startActivity(Intent(this, PracticeActivity::class.java)) }
        button("홈 화면에서 빨간 점 확인") {
            if (!TrackerState.running) status.text = "카메라를 먼저 시작하세요."
            else startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
        button("추적 중지 · 카메라 끄기") {
            TrackerState.status = "추적 중지됨"
            val serviceIntent = Intent(this, TrackingService::class.java)
            stopService(serviceIntent)
            status.text = "추적 중지됨"
        }
        text("개발 단계 데이터 수집", 20f)
        val participant = EditText(this).apply { hint = "참가자 코드 (예: P01)"; setText(options.getString("participant", "P01")); setSingleLine(true) }
        root.addView(participant)
        button("목표 60개 보고 터치하여 데이터 수집") {
            if (!TrackerState.running || TrackerState.features == null) status.text = "카메라를 시작하고 눈이 검출될 때 실행하세요"
            else {
                val code = participant.text.toString().trim().ifEmpty { "P01" }
                options.edit().putString("participant", code).apply()
                startActivity(Intent(this, CaptureActivity::class.java).putExtra("participant", code))
            }
        }
        text("거리·머리 보정 학습: 30cm·45cm 등 서로 다른 거리에서 60점 수집을 각각 완료하세요. 목표 사이에는 머리 자세를 조금 바꾸되, 목표를 보는 동안에는 유지하세요. 마지막 세션은 학습에 쓰지 않는 평가용입니다. 최소 3세션이 필요합니다. 거리 표시는 추정치이므로 자로 확인하세요.", 14f)
        button("수집 데이터로 학습 · 거리 포함/제외 평가") {
            if(training) status.text="학습 중입니다"
            else {
                training=true; status.text="세션을 분리해 학습·평가 중…"
                Thread {
                    val result=runCatching { DepthTraining.train(this) }
                    runOnUiThread {
                        training=false
                        result.onSuccess {
                            TrackerState.cancelActions?.invoke()
                            TrackerState.model=DepthTraining.load(this)
                            TrackerState.modelLabel="거리·머리 보정 학습 모델"
                            TrackerState.status=it
                            if(!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("거리 보정 평가").setMessage(it).setPositiveButton("확인",null).show()
                        }.onFailure { TrackerState.status="학습 실패: ${it.message}" }
                        if(!isDestroyed) showHome()
                    }
                }.start()
            }
        }
        button("거리 비교 평가 JSON 내보내기") {
            if(File(filesDir,"depth-evaluation.json").exists()) exportReport.launch("notouch-distance-evaluation.json")
            else status.text="먼저 학습·평가를 실행하세요"
        }
        val datasetCount = DepthTraining.folder(this).listFiles()?.count { it.extension == "csv" } ?: 0
        button("수집 CSV 내보내기 ($datasetCount 세션)") {
            if (datasetCount == 0) status.text = "수집한 데이터가 없습니다"
            else exportData.launch("notouch-distance-training-v3.csv")
        }
        text("동작 방법", 20f)
        text("같은 버튼이나 위치를 1초 동안 바라보면 한 번 클릭합니다. 진행률은 포인터 둘레에 표시됩니다. 다시 선택하려면 시선을 벗겼다가 돌아오세요.", 15f)
        text("왼쪽 또는 오른쪽 눈을 한 번 윙크하면 스크롤 모드가 켜집니다. 모드가 켜지면 커서 중앙이 파란색으로 바뀝니다. 위·아래·왼쪽·오른쪽 1/3을 보면 해당 방향으로 천천히 스크롤하고 중앙에서는 멈춥니다. 다시 윙크하면 꺼집니다. 스크롤 모드에서는 클릭하지 않습니다. 모드가 꺼진 상태에서 양눈을 빠르게 두 번 깜빡이면 선택된 입력칸에 한 문장 음성 입력을 시작합니다.", 15f)
        text("모델 파일 없이 기본 홍채 포인터가 작동합니다. 기본 모드는 대략적인 방향 추정입니다. 선택 보정이나 학습 모델로 정확도를 개선할 수 있습니다.", 15f)
        text("기본 모드는 영상 전송 없이 동작합니다. GPT 실험을 켜면 얼굴·눈 사진과 수치 특징, 화면 후보 정보가 선택한 기능에 따라 OpenAI로 전송됩니다. 홍채 무늬 등록·정밀 3D 각도 측정·외장 IR 입력·시선 자판은 아직 미구현입니다.", 14f)
        button("홈으로 돌아가기") { finish() }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startTracking() {
        if (TrackerState.running) {
            startService(Intent(this, TrackingService::class.java))
            status.text = "눈 조작 재개 · 연습 화면 또는 다른 앱에서 사용하세요"
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val permissionPrefs = getSharedPreferences("options", MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            (!permissionPrefs.getBoolean("asked_mic", false) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            permissionPrefs.edit().putBoolean("asked_mic", true).apply()
            val requested = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
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

}
