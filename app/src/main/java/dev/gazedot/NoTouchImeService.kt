package dev.gazedot

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** System-wide, Korean-first keyboard with large selectable jamo targets. */
class NoTouchImeService : InputMethodService() {
    private val composer = HangulComposer()
    private var korean = true
    private var shifted = false
    private var numeric = false
    private var passwordField = false
    private var emailField = false
    private var uriField = false
    private var multiLine = false
    private var largeLayout = true
    private var keyboardRoot: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        largeLayout = getSharedPreferences("options", MODE_PRIVATE).getBoolean("ime_large_layout", true)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            composer.clear()
            shifted = false
        }
        val type = attribute?.inputType ?: EditorInfo.TYPE_CLASS_TEXT
        val klass = type and android.text.InputType.TYPE_MASK_CLASS
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        numeric = klass == android.text.InputType.TYPE_CLASS_NUMBER || klass == android.text.InputType.TYPE_CLASS_PHONE
        passwordField = (klass == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (klass == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        emailField = klass == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )
        uriField = klass == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_URI
        )
        multiLine = type and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        renderKeyboard()
    }

    override fun onCreateInputView(): View = buildKeyboard().also { keyboardRoot = it }

    override fun onFinishInput() {
        composer.clear()
        keyboardRoot = null
        super.onFinishInput()
    }

    private fun buildKeyboard(): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setBackgroundColor(Color.rgb(10, 18, 39))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        keyboardRoot = root
        renderKeyboard(root)
        return root
    }

    private fun renderKeyboard(existing: LinearLayout? = keyboardRoot) {
        val root = existing ?: return
        root.removeAllViews()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()

        val headingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = TextView(this).apply {
            text = if (numeric) "숫자 입력" else if (korean) "한글 · 두벌식 자모" else "English · QWERTY"
            setTextColor(Color.rgb(175, 195, 224)); textSize = 12f; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(5), 0)
        }
        headingRow.addView(heading, LinearLayout.LayoutParams(0, dp(20), 1f))
        headingRow.addView(keyView(if (largeLayout) "두벌식 배열" else "큰 키 배열", 11f) {
            largeLayout = !largeLayout
            getSharedPreferences("options", MODE_PRIVATE).edit().putBoolean("ime_large_layout", largeLayout).apply()
            renderKeyboard()
        }, LinearLayout.LayoutParams(dp(92), dp(24)))
        root.addView(headingRow, LinearLayout.LayoutParams(-1, dp(24)))

        val candidates = if (!passwordField && !numeric) candidateWords() else emptyList()
        val candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val shown = candidates.ifEmpty { listOf("안녕하세요", "네", "도와주세요", "감사합니다") }
        shown.take(4).forEach { phrase ->
            candidateRow.addView(keyView(phrase, 13f, true) { commitCandidate(phrase) },
                LinearLayout.LayoutParams(0, dp(30), 1f).apply { setMargins(dp(2), dp(1), dp(2), dp(2)) })
        }
        root.addView(candidateRow, LinearLayout.LayoutParams(-1, dp(34)))

        val keyRows: List<List<String>> = when {
            numeric -> listOf(listOf("1", "2", "3", "4", "5"), listOf("6", "7", "8", "9", "0"), listOf("-", ".", ",", "@", "/"))
            korean -> {
                val rows = if (largeLayout) listOf("ㅂㅈㄷㄱㅅ", "ㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹ", "ㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍ", "ㅠㅜㅡ")
                    else listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ")
                rows.map { chars -> chars.map { it.toString() }.map { if (shifted) shiftedHangul[it] ?: it else it } }
            }
            else -> (if (largeLayout) listOf("qwert", "yuiop", "asdf", "ghjkl", "zxcv", "bnm")
                else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))
                .map { chars -> chars.map { if (shifted) it.uppercase() else it.toString() } }
        }
        val keyHeight = if (largeLayout) 36 else 54
        keyRows.forEach { row -> addKeyRow(root, row, keyHeight, 19f) { key -> typeKey(key) } }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (!numeric) controls.addView(keyView(if (shifted) "⇧ ✓" else "⇧", 16f) {
            shifted = !shifted; renderKeyboard()
        }, LinearLayout.LayoutParams(0, dp(43), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(2)) })
        controls.addView(keyView("⌫", 20f) { delete() }, LinearLayout.LayoutParams(0, dp(43), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(2)) })
        controls.addView(keyView(if (numeric) "ABC" else "123", 15f) {
            if (!numeric) finishComposition()
            numeric = !numeric; renderKeyboard()
        }, LinearLayout.LayoutParams(0, dp(43), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(2)) })
        if (!numeric) controls.addView(keyView(if (korean) "한/영" else "한/영", 14f) {
            finishComposition(); korean = !korean; shifted = false; renderKeyboard()
        }, LinearLayout.LayoutParams(0, dp(43), 1.3f).apply { setMargins(dp(2), dp(3), dp(2), dp(2)) })
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(42)))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val shortcut = when { emailField -> "@"; uriField -> "/"; else -> null }
        val dotShortcut = if (emailField || uriField) "." else null
        bottom.addView(keyView("space", 16f) { commitText(" ") },
            LinearLayout.LayoutParams(0, dp(40), if (shortcut == null) 3.2f else 2.4f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
        if (shortcut != null) bottom.addView(keyView(shortcut, 15f) { commitText(shortcut) },
            LinearLayout.LayoutParams(0, dp(40), .8f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
        if (dotShortcut != null) bottom.addView(keyView(dotShortcut, 15f) { commitText(dotShortcut) },
            LinearLayout.LayoutParams(0, dp(40), .8f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
        bottom.addView(keyView(actionLabel(), 15f, true) { editorAction() },
            LinearLayout.LayoutParams(0, dp(40), 1.4f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(42)))
    }

    private fun addKeyRow(root: LinearLayout, labels: List<String>, height: Int, size: Float, action: (String) -> Unit) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        labels.forEach { label ->
            row.addView(keyView(label, size) { action(label) },
                LinearLayout.LayoutParams(0, dp(height), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(height + 4)))
    }

    private fun keyView(label: String, size: Float, highlighted: Boolean = false, action: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).roundToInt()
        return TextView(this).apply {
            text = label; textSize = size; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); isClickable = true; isFocusable = true
            contentDescription = label
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (highlighted) Color.rgb(5, 125, 229) else Color.rgb(27, 45, 75))
                setStroke(dp(1), Color.rgb(56, 83, 122))
            }
            setOnClickListener { action() }
        }
    }

    private fun candidateWords(): List<String> {
        val current = composer.text
        val common = listOf("안녕하세요", "감사합니다", "도와주세요", "괜찮아요", "네", "아니요", "지금", "잠시만요")
        return if (current.isBlank()) common.take(4) else common.filter { it.startsWith(current) && it != current }.take(4)
    }

    private fun typeKey(key: String) {
        if (numeric || !korean) {
            val output = if (!numeric && shifted) key.uppercase() else key
            currentInputConnection?.commitText(output, 1)
        } else {
            composer.append(key.first())
            currentInputConnection?.setComposingText(composer.text, 1)
        }
        if (shifted) { shifted = false; renderKeyboard() } else renderKeyboard()
    }

    private fun commitCandidate(value: String) {
        currentInputConnection?.commitText(value, 1)
        composer.clear(); shifted = false; renderKeyboard()
    }

    private fun finishComposition() {
        val value = composer.text
        if (value.isNotEmpty()) currentInputConnection?.commitText(value, 1)
        composer.clear()
    }

    private fun commitText(value: String) {
        val ic = currentInputConnection ?: return
        val composing = composer.text
        ic.commitText(composing + value, 1)
        composer.clear(); renderKeyboard()
    }

    private fun delete() {
        val ic = currentInputConnection ?: return
        if (!composer.isEmpty) {
            composer.backspace()
            ic.setComposingText(composer.text, 1)
        } else ic.deleteSurroundingText(1, 0)
        renderKeyboard()
    }

    private fun actionLabel(): String = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
        EditorInfo.IME_ACTION_GO -> "이동"
        EditorInfo.IME_ACTION_SEARCH -> "검색"
        EditorInfo.IME_ACTION_SEND -> "전송"
        EditorInfo.IME_ACTION_NEXT -> "다음"
        else -> if (multiLine) "줄 바꿈" else "완료"
    }

    private fun editorAction() {
        val info = currentInputEditorInfo ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val ic = currentInputConnection ?: return
        if (multiLine && (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED)) commitText("\n")
        else {
            finishComposition()
            val sent = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && ic.performEditorAction(action)
            if (!sent) {
                if (multiLine) ic.commitText("\n", 1) else requestHideSelf(0)
            }
        }
    }

    private companion object {
        val shiftedHangul = mapOf("ㄱ" to "ㄲ", "ㄷ" to "ㄸ", "ㅂ" to "ㅃ", "ㅅ" to "ㅆ", "ㅈ" to "ㅉ", "ㅐ" to "ㅒ", "ㅔ" to "ㅖ")
    }
}
