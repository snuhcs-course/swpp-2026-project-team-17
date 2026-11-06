package dev.gazedot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity

/** Uses actual accessibility actions/gestures, not the tutorial's scroll animation. */
class PracticeActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val stateListener: () -> Unit = { updateStatus() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(n: Int) = (n * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(60), dp(24), dp(48))
        }
        root.addView(TextView(this).apply {
            text = "실제 눈 조작 연습\n1초 응시: 클릭 · 윙크: 스크롤 모드"; textSize = 23f
        })
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "접근성 서비스 설정"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        var count = 0
        root.addView(Button(this).apply {
            text = "클릭 횟수: 0"; setOnClickListener { text = "클릭 횟수: ${++count}" }
        })
        root.addView(EditText(this).apply {
            hint = "이 입력칸을 선택하고 빠르게 두 번 깜빡인 뒤 말하세요"
            minLines = 3; setSingleLine(false)
        })
        root.addView(TextView(this).apply {
            text = "좌우 스크롤 연습\n윙크로 파란 커서를 켠 뒤 화면 좌/우 1/3을 바라보세요."; textSize = 18f
        })
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(8) { index ->
            cards.addView(TextView(this).apply {
                text = "가로 카드 ${index + 1}\n← 왼쪽 · 오른쪽 →"; textSize = 20f
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(if (index % 2 == 0) 0xFFE6E6FD.toInt() else 0xFFDCEEFF.toInt())
            }, LinearLayout.LayoutParams(dp(220), -1))
        }
        val horizontal = HorizontalScrollView(this).apply {
            isFillViewport = true
            contentDescription = "좌우 스크롤 연습 카드"
            addView(cards, FrameLayout.LayoutParams(-2, -1))
        }
        root.addView(horizontal, LinearLayout.LayoutParams(-1, dp(160)))
        val horizontalStatus = TextView(this).apply { text = "가로 이동: 준비 중"; textSize = 16f }
        root.addView(horizontalStatus)
        horizontal.setOnScrollChangeListener { _, x, _, _, _ ->
            val maximum = (cards.width - horizontal.width).coerceAtLeast(0)
            horizontalStatus.text = "가로 이동: $x / $maximum · 카드 번호가 바뀌는지 확인하세요."
        }
        // Begin in the middle so both left and right can move immediately.
        horizontal.post { horizontal.scrollTo((cards.width - horizontal.width).coerceAtLeast(0) / 2, 0) }
        repeat(24) { n -> root.addView(TextView(this).apply {
            text = "세로 스크롤 연습 ${n + 1}\n위/아래 1/3을 바라보세요. 다시 윙크하면 스크롤 모드가 꺼집니다."
            textSize = 18f; setPadding(0, dp(20), 0, dp(20))
        }) }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() {
        super.onResume()
        TrackerState.interactionAllowed = true
        TrackerState.listener = stateListener
        updateStatus()
    }
    override fun onPause() {
        if (TrackerState.listener === stateListener) TrackerState.listener = null
        super.onPause()
    }
    private fun updateStatus() {
        val message = when {
            ControlAccessibilityService.instance == null -> "아래 설정에서 NoTouch 눈 조작 접근성 서비스를 켜 주세요."
            !TrackerState.running || TrackerState.model == null -> "홈에서 카메라를 시작하고 9점 보정을 완료해 주세요."
            !TrackerState.controls -> "홈 설정에서 일시정지한 조작을 재개해 주세요."
            TrackerState.sample?.scrollMode == true -> "스크롤 모드 켜짐 · 좌/우 1/3을 바라보세요.\n${TrackerState.actionStatus}"
            else -> "조작 준비 완료 · 윙크하면 파란 커서로 바뀝니다. 가로 스크롤은 가로 콘텐츠가 있는 영역에서 동작합니다."
        }
        // Unchanged text must not emit content-change events that cancel an in-flight dwell.
        if (status.text.toString() != message) status.text = message
    }
}
