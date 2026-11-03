package dev.gazedot

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Functional local reader for evaluating the same panel used on other accessible app screens. */
class EasyControlDemoActivity : ComponentActivity() {
    private var article = 0
    private var saved = false
    private var fontSize = 18f
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var bookmark: Button
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        article = savedInstanceState?.getInt("article") ?: 0
        saved = savedInstanceState?.getBoolean("saved") ?: false
        fontSize = savedInstanceState?.getFloat("fontSize") ?: 18f
        val d = resources.displayMetrics.density
        fun dp(value: Int) = (value * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "NoTouch · 쉬운 조작 체험"
            textSize = 23f; setTextColor(Color.rgb(17, 18, 70))
        })
        root.addView(TextView(this).apply {
            text = "버튼 근처에서 시선이 흔들려 선택하기 어려우면 주변 후보를 크게 보여드려요. 안내 영역을 본 뒤 카드를 1초 응시하거나 터치해 선택하세요."
            textSize = 15f; setPadding(0, dp(10), 0, dp(10))
        })
        root.addView(Button(this).apply {
            text = "쉬운 조작 켜기"
            setOnClickListener {
                val service = ControlAccessibilityService.instance
                when {
                    service == null -> {
                        Toast.makeText(this@EasyControlDemoActivity, "접근성 설정에서 NoTouch를 켜 주세요", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    !TrackerState.running -> Toast.makeText(this@EasyControlDemoActivity,
                        "홈에서 카메라를 시작하고 보정한 뒤 다시 열어 주세요", Toast.LENGTH_LONG).show()
                    else -> service.setEasyPanelEnabled(true)
                }
            }
        })
        val toolbar = LinearLayout(this)
        fun action(label: String, callback: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 13f; isAllCaps = false
            setOnClickListener { callback() }
            toolbar.addView(this, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        bookmark = action("저장하기") { saved = !saved; refresh() }
        action("글자 크게") { fontSize = if (fontSize >= 26f) 18f else fontSize + 2f; refresh() }
        action("다음 글") { article = (article + 1) % 2; saved = false; refresh(); scroll.scrollTo(0, 0) }
        root.addView(toolbar)
        scroll = ScrollView(this)
        val articleContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(88)) }
        title = TextView(this).apply { textSize = 25f; setTextColor(Color.rgb(17, 18, 70)) }
        body = TextView(this).apply { setPadding(0, dp(16), 0, 0); setLineSpacing(dp(6).toFloat(), 1f) }
        articleContent.addView(title)
        articleContent.addView(body)
        scroll.addView(articleContent)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val heading = if (article == 0) "나에게 맞는 속도로 읽기" else "조금씩 쉬어 가는 하루"
        title.text = if (saved) "$heading · 저장됨" else heading
        bookmark.text = if (saved) "저장 취소" else "저장하기"
        body.textSize = fontSize
        val paragraphs = if (article == 0) listOf(
            "이 화면은 쉬운 조작을 체험하기 위한 예시 글입니다. 저장하기와 글자 크기, 다음 글 버튼이 실제로 동작합니다.",
            "쉬운 조작을 켜도 평소의 시선 클릭과 윙크 스크롤은 유지됩니다. 한 영역에서 시선이 반복해서 흔들려 클릭이 끝나지 않으면 주변 버튼 후보를 크게 보여드립니다.",
            "최근 시선 분포와 버튼 영역의 거리를 비교해 최대 세 후보를 보여줍니다. 자동 표시에는 주변 후보가 두 개 이상 필요합니다. 왼쪽 아래 쉬운 조작 버튼으로도 직전에 바라본 영역의 후보를 확인할 수 있습니다.",
            "두 카드 사이를 바라보면 두 후보가 따로 표시됩니다. 새로 나타난 카드를 선택하기 전에는 안내 영역으로 잠깐 시선을 옮겨 주세요.",
            "쉬운 조작을 접으면 원래 화면에서 결과를 확인할 수 있습니다. 일반 조작을 선택하면 기존 눈 조작으로 돌아갑니다.",
            "화면이 바뀌면 이전 항목으로 동작하지 않습니다. 새로고침을 선택해 현재 화면의 기능을 다시 불러올 수 있습니다."
        ) else listOf(
            "이 화면은 두 번째 예시 글입니다. 작은 버튼을 찾기 어려울 때 큰 카드에서 같은 기능을 선택해 보세요.",
            "잠깐 쉬고 싶을 때는 조작을 일시정지할 수 있습니다. 눈을 감거나 화면 밖을 바라보는 시간은 선택 시간에 더해지지 않습니다.",
            "안경과 조명, 기기의 위치에 따라 추적 상태가 달라질 수 있습니다. 잘 맞지 않는 경우에는 홈에서 다시 보정해 주세요.",
            "저장하기를 선택하면 제목에 저장 상태가 표시됩니다. 다시 선택하면 저장을 취소할 수 있습니다.",
            "글자 크게를 선택하면 본문의 크기가 바뀝니다. 가장 큰 단계에서 한 번 더 선택하면 처음 크기로 돌아갑니다.",
            "체험을 마쳤다면 접기를 선택해 글로 돌아가거나 일반 조작을 선택해 쉬운 조작을 끌 수 있습니다."
        )
        body.text = paragraphs.joinToString("\n\n")
    }

    override fun onResume() { super.onResume(); TrackerState.interactionAllowed = true }
    override fun onPause() {
        TrackerState.cancelActions?.invoke()
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("article", article)
        outState.putBoolean("saved", saved)
        outState.putFloat("fontSize", fontSize)
        super.onSaveInstanceState(outState)
    }
}
