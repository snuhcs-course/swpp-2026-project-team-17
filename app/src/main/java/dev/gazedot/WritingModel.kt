package dev.gazedot

internal object WritingPhrases {
    // Stable, row-major order; these are an AAC-inspired starting set, not a frequency ranking.
    val defaults = listOf("도와주세요.", "다음에 연락드릴게요.", "잠시만 기다려 주세요.", "네, 알겠습니다.", "아니요, 괜찮습니다.", "감사합니다.")
    val categories = linkedMapOf(
        "인사·연락" to listOf("안녕하세요.", "다음에 연락드릴게요.", "지금 통화하기 어렵습니다.", "잘 지내고 있어요."),
        "도움 요청" to listOf("도와주세요.", "잠시만 기다려 주세요.", "물 좀 주세요.", "이동을 도와주세요."),
        "일상 표현" to listOf("네, 알겠습니다.", "아니요, 괜찮습니다.", "감사합니다.", "잠시 쉬고 싶어요."),
    )
}

internal data class EditorSnapshot(
    val packageName: String, val windowId: Int, val nodeKey: Int,
    val text: String, val start: Int, val end: Int,
) {
    val validSelection get() = start in 0..text.length && end in 0..text.length
    // Empty text has exactly one insertion position, even when the editor omits selection metadata.
    fun withEmptySelectionFallback() = if (text.isEmpty()) copy(start = 0, end = 0) else this
    fun sameTarget(other: EditorSnapshot) = packageName == other.packageName && windowId == other.windowId &&
        nodeKey == other.nodeKey
    fun unchanged(other: EditorSnapshot) = sameTarget(other) && text == other.text && start == other.start && end == other.end
    fun plan(addition: String): InsertionPlan? {
        if (!validSelection || addition.isEmpty()) return null
        val at = maxOf(start, end)
        return InsertionPlan(this, text.take(at) + addition + text.drop(at), at + addition.length)
    }

    fun candidatePlan(candidate: WritingCandidate): InsertionPlan? {
        if (!validSelection || start != text.length || end != text.length ||
            candidate.start !in 0..text.length || candidate.end != text.length ||
            text.substring(candidate.start, candidate.end) != candidate.expected ||
            candidate.replacement.isEmpty()) return null
        val updated = text.take(candidate.start) + candidate.replacement
        return InsertionPlan(this, updated, updated.length)
    }
}

internal data class InsertionPlan(val before: EditorSnapshot, val text: String, val caret: Int) {
    fun textConfirmed(actual: EditorSnapshot) = before.sameTarget(actual) && actual.text == text
    fun confirmed(actual: EditorSnapshot) = textConfirmed(actual) && actual.start == caret && actual.end == caret
}

internal enum class CandidateKind { WORD, CONTINUATION }
internal data class WritingCandidate(
    val version: Long, val kind: CandidateKind, val start: Int, val end: Int,
    val expected: String, val replacement: String,
)

internal data class PredictionInput(val version: Long, val text: String) {
    val wordStart get() = text.indexOfLast { it.isWhitespace() || it in ".,!?。！？" } + 1
    fun candidates(words: List<String>, continuation: String): List<WritingCandidate> {
        val current = text.substring(wordStart)
        val result = words.asSequence().map { it.trim() }.filter {
            current.isNotEmpty() && it.isNotEmpty() && it.length <= 80 && it.none { c -> c.isISOControl() } &&
                it.split(Regex("\\s+")).size <= 6 &&
                it != current && hangulParts(it).startsWith(hangulParts(current))
        }.distinct().take(2).map {
            WritingCandidate(version, CandidateKind.WORD, wordStart, text.length, current, "$it ")
        }.toMutableList()
        // Continuations are append-only. The server never chooses an editable range.
        if (continuation.isNotBlank() && continuation.length <= 160 &&
            continuation.none { it.isISOControl() } &&
            text.lastOrNull()?.let { it.isWhitespace() || it in ".,!?。！？" } == true) {
            result += WritingCandidate(version, CandidateKind.CONTINUATION, text.length, text.length, "", continuation)
        }
        return result
    }
}

internal fun hangulParts(text: String): String = buildString {
    val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    val vowels = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    val finals = listOf("", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
    val compounds = mapOf('ㅐ' to "ㅏㅣ", 'ㅒ' to "ㅑㅣ", 'ㅔ' to "ㅓㅣ", 'ㅖ' to "ㅕㅣ",
        'ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅏㅣ", 'ㅚ' to "ㅗㅣ", 'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅓㅣ", 'ㅟ' to "ㅜㅣ", 'ㅢ' to "ㅡㅣ",
        'ㄳ' to "ㄱㅅ", 'ㄵ' to "ㄴㅈ", 'ㄶ' to "ㄴㅎ", 'ㄺ' to "ㄹㄱ", 'ㄻ' to "ㄹㅁ", 'ㄼ' to "ㄹㅂ", 'ㄽ' to "ㄹㅅ", 'ㄾ' to "ㄹㅌ", 'ㄿ' to "ㄹㅍ", 'ㅀ' to "ㄹㅎ", 'ㅄ' to "ㅂㅅ")
    fun part(c: Char) { append(compounds[c] ?: c.toString()) }
    text.forEach { c ->
        if (c in '\uAC00'..'\uD7A3') {
            val code = c.code - 0xAC00
            part(initials[code / 588]); part(vowels[code % 588 / 28]); finals[code % 28].forEach(::part)
        } else part(c)
    }
}

internal class WritingDraft(initial: String = "") {
    private val composer = KoreanComposer().apply { setText(initial) }
    private val history = ArrayDeque<KoreanComposer.State>()
    var version = 0L; private set
    val text get() = composer.text
    val canUndo get() = history.isNotEmpty()
    private fun change(action: () -> Unit) {
        val before = composer.snapshot()
        action()
        if (composer.snapshot() == before) return
        history.addLast(before)
        while (history.size > 100) history.removeFirst()
        version++
    }
    fun jamo(c: Char) = change { composer.add(c) }
    fun literal(s: String) = change { composer.literal(s) }
    fun backspace() = change { composer.backspace() }
    fun space() = change { composer.literal(" ") }
    fun undo() {
        if (history.isNotEmpty()) { composer.restore(history.removeLast()); version++ }
    }
    fun replace(s: String) = change { composer.setText(s) }
    fun accepted() { composer.setText(""); history.clear(); version++ }
    fun accepts(c: WritingCandidate) = c.version == version && c.start in 0..text.length &&
        c.end in c.start..text.length && text.substring(c.start, c.end) == c.expected &&
        (if (c.kind == CandidateKind.WORD) c.start == PredictionInput(version, text).wordStart && c.end == text.length
        else c.start == text.length && c.end == text.length)
    fun apply(c: WritingCandidate): Boolean {
        if (!accepts(c)) return false
        change { composer.setText(text.take(c.start) + c.replacement + text.drop(c.end)) }
        return true
    }
}

/** UI control identities survive text refreshes. A new screen must first be left by valid gaze. */
internal class WritingDwell {
    private val dwell = TutorialDwell()
    private var target: String? = null
    private var selected: String? = null
    private var waitingForExit = true
    val progress get() = dwell.progress
    fun screenChanged() { reset(); selected = null; waitingForExit = true }
    fun selected(key: String) { selected = key; reset() }
    fun reset() { dwell.reset(); target = null }
    fun expire(now: Long) = dwell.expire(now)
    fun update(key: String?, valid: Boolean, sampleAt: Long, now: Long): Boolean {
        if (!valid) { reset(); return false }
        if (waitingForExit) {
            if (key == null) waitingForExit = false
            reset(); return false
        }
        if (key != selected) selected = null
        if (key != target) { dwell.reset(); target = key }
        if (dwell.update(key != null && key != selected, sampleAt, now)) {
            selected = key; reset(); return true
        }
        return false
    }
}

internal class PredictionGate {
    var version = -1L; private set
    var held = false; private set
    var displayed = emptyList<WritingCandidate>(); private set
    private var pending: List<WritingCandidate>? = null
    fun input(version: Long) {
        this.version = version; pending = null
        if (!held) displayed = emptyList()
    }
    fun offer(version: Long, candidates: List<WritingCandidate>): Boolean {
        if (version != this.version || candidates.any { it.version != version }) return false
        if (held) pending = candidates else displayed = candidates
        return true
    }
    fun hold(value: Boolean) {
        held = value
        if (!value) {
            displayed = pending ?: displayed.filter { it.version == version }
            pending = null
        }
    }
    fun clear() { held = false; displayed = emptyList(); pending = null; version = -1 }
}

/** Only the last response, in memory. Never reuse against a different draft prefix. */
internal class PredictionReuse {
    private var previous: PredictionInput? = null
    private var words = emptyList<String>()
    fun remember(input: PredictionInput, candidates: List<WritingCandidate>) {
        previous=input
        words=candidates.filter { it.kind==CandidateKind.WORD }.map { it.replacement.trim() }.take(2)
    }
    fun reuse(input: PredictionInput): List<WritingCandidate> {
        val before=previous ?: return emptyList()
        if(input.text.take(input.wordStart)!=before.text.take(before.wordStart)) return emptyList()
        if(!hangulParts(input.text.substring(input.wordStart)).startsWith(hangulParts(before.text.substring(before.wordStart)))) return emptyList()
        return input.candidates(words, "")
    }
    fun clear() { previous=null; words=emptyList() }
}
