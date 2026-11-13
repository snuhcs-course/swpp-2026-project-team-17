package dev.gazedot

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity

/** Prediction-first local Korean typing screen; all controls are ordinary accessible Android buttons. */
class TextEntryActivity : ComponentActivity() {
    private val composer = KoreanComposer()
    private val undo = ArrayDeque<KoreanComposer.State>()
    private lateinit var message: TextView
    private lateinit var messageScroll: ScrollView
    private lateinit var composing: TextView
    private lateinit var suggestionRow: LinearLayout
    private lateinit var groupRow1: LinearLayout
    private lateinit var groupRow2: LinearLayout
    private lateinit var jamoScroll: ScrollView
    private lateinit var jamoGrid: GridLayout
    private lateinit var spellButton: Button
    private val focusables = mutableListOf<View>()
    private val groups = listOf(
        "ㄱ ㄲ ㅋ" to "ㄱㄲㅋ", "ㄴ ㄷ ㄸ ㄹ ㅌ" to "ㄴㄷㄸㄹㅌ", "ㅁ ㅂ ㅃ ㅍ" to "ㅁㅂㅃㅍ", "ㅅ ㅆ ㅈ ㅉ ㅊ" to "ㅅㅆㅈㅉㅊ", "ㅇ ㅎ" to "ㅇㅎ",
        "ㅏ ㅑ ㅓ ㅕ ㅣ" to "ㅏㅑㅓㅕㅣ", "ㅗ ㅛ ㅜ ㅠ ㅡ" to "ㅗㅛㅜㅠㅡ",
        "ㅐ ㅒ ㅔ ㅖ ㅚ ㅟ" to "ㅐㅒㅔㅖㅚㅟ", "ㅘ ㅙ ㅝ ㅞ ㅢ" to "ㅘㅙㅝㅞㅢ",
        "받침" to "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    )
    private var selectedGroup = 0
    private var spellMode = false
    private var focused: View? = null
    private val ink = Color.rgb(17, 18, 70)
    private val blue = Color.rgb(6, 108, 236)
    private val pale = Color.rgb(230, 230, 253)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(n: Int) = (n * d).toInt()
        val font = resources.getFont(R.font.geologica)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(28), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        fun label(text: String, size: Float, color: Int = ink) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); typeface = font
            fontVariationSettings = "'wght' 600"; gravity = Gravity.CENTER_VERTICAL
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = makeButton("‹ 홈", d, secondary = true).apply {
            contentDescription = "홈으로 돌아가기"
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(90), dp(48)))
        header.addView(label("한국어 입력", 22f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        message = label("메시지가 여기에 표시됩니다", 22f).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10)); gravity = Gravity.TOP or Gravity.START
            background = shape(Color.rgb(248, 249, 255), ink, dp(16)); minLines = 2
            contentDescription = "입력한 메시지"
        }
        messageScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(message, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(messageScroll, LinearLayout.LayoutParams(-1, dp(82)).apply { topMargin = dp(4) })
        composing = label("", 14f, blue).apply { gravity = Gravity.CENTER_VERTICAL; contentDescription = "현재 입력 중인 한글 음절" }
        root.addView(composing, LinearLayout.LayoutParams(-1, dp(28)))

        root.addView(label("단어 제안 · 선택하면 입력됩니다", 13f), LinearLayout.LayoutParams(-1, dp(26)))
        suggestionRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(suggestionRow, LinearLayout.LayoutParams(-1, dp(150)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        spellButton = makeButton("Spell · 글자 입력", d)
        spellButton.contentDescription = "글자 단위 입력 모드 켜기 또는 끄기"
        spellButton.setOnClickListener { spellMode = !spellMode; renderKeyboard(); updateText() }
        actions.addView(spellButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        val backspace = makeButton("⌫ 지우기", d, secondary = true).apply {
            contentDescription = "현재 음절 또는 글자 하나 지우기"
            setOnClickListener { recordUndo(); composer.backspace(); updateText() }
        }
        actions.addView(backspace, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(4) })
        root.addView(makeButton("띄어쓰기", d, secondary = true).apply {
            contentDescription = "단어 사이 띄어쓰기 입력"
            setOnClickListener { recordUndo(); composer.space(); updateText() }
        }, LinearLayout.LayoutParams(-1, dp(44)))

        val undoButton = makeButton("↶ 실행 취소", d, secondary = true).apply {
            contentDescription = "직전 입력 또는 단어 선택 실행 취소"
            setOnClickListener { if (undo.isNotEmpty()) composer.restore(undo.removeLast()); updateText() }
        }
        root.addView(undoButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })

        groupRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        groupRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(groupRow1, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        root.addView(groupRow2, LinearLayout.LayoutParams(-1, dp(48)))
        jamoGrid = GridLayout(this).apply { columnCount = 5; rowCount = 2; useDefaultMargins = true }
        jamoScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            contentDescription = "자모 버튼 목록. 받침 그룹은 아래로 스크롤할 수 있습니다."
            addView(jamoGrid, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(jamoScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2) })
        setContentView(root)
        buildGroupSelectors(d)
        renderKeyboard()
        updateText()
        focusables.add(back)
        focusables.addAll(listOf(spellButton, backspace, undoButton))
    }

    private fun shape(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); setStroke((resources.displayMetrics.density * 1.5f).toInt(), stroke); cornerRadius = radius.toFloat()
    }
    private fun makeButton(text: String, d: Float, secondary: Boolean = false) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = if (text.length > 8) 14f else 17f
        setTextColor(if (secondary) ink else Color.WHITE)
        typeface = resources.getFont(R.font.geologica); fontVariationSettings = "'wght' 650"
        background = GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(if (secondary) pale else blue)
            setStroke((1.5f * d).toInt(), if (secondary) Color.rgb(192, 194, 225) else blue)
        }
        stateListAnimator = null; minHeight = 0; minimumHeight = 0; minWidth = 0
        setPadding((8*d).toInt(), 0, (8*d).toInt(), 0)
        isFocusable = true; isClickable = true
    }

    private fun buildGroupSelectors(d: Float) {
        groupRow1.removeAllViews(); groupRow2.removeAllViews()
        groups.forEachIndexed { index, (name, _) ->
            val button = makeButton(name, d, secondary = index != selectedGroup).apply {
                textSize = if (index == 9) 14f else 12f
                contentDescription = "${name.replace(" ", "")} 자모 그룹 선택"
                setOnClickListener { selectedGroup = index; buildGroupSelectors(d); renderKeyboard(); updateText() }
            }
            focusables.add(button)
            val target = if (index < 5) groupRow1 else groupRow2
            target.addView(button, LinearLayout.LayoutParams(0, (44*d).toInt(), if (index < 5) 1f else 1f).apply {
                marginStart = (2*d).toInt(); marginEnd = (2*d).toInt()
            })
        }
    }

    private fun renderKeyboard() {
        groupRow1.visibility = if (spellMode) View.VISIBLE else View.INVISIBLE
        groupRow2.visibility = if (spellMode) View.VISIBLE else View.INVISIBLE
        jamoGrid.visibility = if (spellMode) View.VISIBLE else View.INVISIBLE
        spellButton.text = if (spellMode) "Spell · 입력 중" else "Spell · 글자 입력"
        jamoGrid.removeAllViews()
        val letters = groups[selectedGroup].second
        jamoGrid.rowCount = maxOf(2, (letters.length + 4) / 5)
        jamoScroll.scrollTo(0, 0)
        letters.forEachIndexed { index, jamo ->
            val button = makeButton(jamo.toString(), resources.displayMetrics.density).apply {
                textSize = if (jamoGrid.width > 0 && jamoGrid.width / 5 < 76 * resources.displayMetrics.density) 24f else 30f
                contentDescription = "자모 $jamo 입력"
                setOnClickListener { recordUndo(); composer.add(jamo); updateText() }
            }
            focusables.add(button)
            jamoGrid.addView(button, GridLayout.LayoutParams().apply {
                width = 0; height = (56 * resources.displayMetrics.density).toInt()
                columnSpec = GridLayout.spec(index % 5, 1f); rowSpec = GridLayout.spec(index / 5)
                setMargins((3*resources.displayMetrics.density).toInt(), (2*resources.displayMetrics.density).toInt(), (3*resources.displayMetrics.density).toInt(), (2*resources.displayMetrics.density).toInt())
            })
        }
    }

    private fun recordUndo() {
        undo.addLast(composer.snapshot())
        while (undo.size > 40) undo.removeFirst()
    }
    private fun selectSuggestion(word: String) {
        recordUndo()
        val replace = composer.currentWord.isNotEmpty() && !composer.text.endsWith(' ') && word.startsWith(composer.currentWord)
        composer.acceptWord(word, replace)
        updateText()
    }
    private fun updateText() {
        val current = composer.text
        message.text = current.ifEmpty { "메시지가 여기에 표시됩니다" }
        messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
        composing.text = if (composer.composing.isNotEmpty()) "입력 중: ${composer.composing}" else " "
        renderSuggestions()
    }
    private fun renderSuggestions() {
        suggestionRow.removeAllViews()
        val candidates = LocalKoreanSuggestions.forText(composer.text)
        repeat(3) { index ->
            val word = candidates.getOrNull(index)
            val button = makeButton(word ?: "", resources.displayMetrics.density, secondary = word == null).apply {
                isEnabled = word != null
                alpha = if (word != null) 1f else 0f
                contentDescription = word?.let { "단어 제안 $it 입력" } ?: "비어 있는 단어 제안"
                if (word != null) setOnClickListener { selectSuggestion(word) }
            }
            if (word != null) focusables.add(button)
            suggestionRow.addView(button, LinearLayout.LayoutParams(-1, (46 * resources.displayMetrics.density).toInt()).apply {
                topMargin = (2 * resources.displayMetrics.density).toInt(); bottomMargin = (2 * resources.displayMetrics.density).toInt()
            })
        }
        focusables.clear()
        fun collect(view: View) {
            if (view.isClickable && view.isEnabled && view.visibility == View.VISIBLE) focusables.add(view)
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(findViewById(android.R.id.content))
    }

    private fun updateGazeFocus() {
        val gaze = TrackerState.gaze?.takeIf { TrackerState.running && it.fresh(android.os.SystemClock.uptimeMillis()) }
        val bounds = DisplaySpace.bounds(this)
        val target = if (gaze == null) null else
            focusables.firstOrNull { view ->
                val rect = Rect(); view.isShown && view.isEnabled && view.getGlobalVisibleRect(rect) &&
                    rect.contains((gaze.x * bounds.width()).toInt(), (gaze.y * bounds.height()).toInt())
            }
        if (target === focused) return
        focused?.let { it.scaleX = 1f; it.scaleY = 1f; it.elevation = 0f }
        focused = target
        target?.let { it.scaleX = 1.04f; it.scaleY = 1.04f; it.elevation = 8 * resources.displayMetrics.density }
    }

    override fun onResume() { super.onResume(); TrackerState.interactionAllowed = true; updateText(); TrackerState.listener = { updateGazeFocus() } }
    override fun onPause() { TrackerState.listener = null; focused = null; focusables.clear(); super.onPause() }
}
