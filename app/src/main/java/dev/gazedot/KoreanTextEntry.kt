package dev.gazedot

/** Two-set-style Hangul syllable composer for gaze-selected compatibility jamo. */
internal class KoreanComposer {
    data class State(val committed: String, val initial: Char?, val vowel: Char?, val ending: String?)
    private val initialOrder = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val vowelOrder = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val finalOrder = listOf("", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
    private val compoundVowels = mapOf("ㅗㅏ" to "ㅘ", "ㅗㅐ" to "ㅙ", "ㅗㅣ" to "ㅚ", "ㅜㅓ" to "ㅝ", "ㅜㅔ" to "ㅞ", "ㅜㅣ" to "ㅟ", "ㅡㅣ" to "ㅢ", "ㅏㅣ" to "ㅐ", "ㅑㅣ" to "ㅒ", "ㅓㅣ" to "ㅔ", "ㅕㅣ" to "ㅖ")
    private val compoundFinals = mapOf("ㄱㅅ" to "ㄳ", "ㄴㅈ" to "ㄵ", "ㄴㅎ" to "ㄶ", "ㄹㄱ" to "ㄺ", "ㄹㅁ" to "ㄻ", "ㄹㅂ" to "ㄼ", "ㄹㅅ" to "ㄽ", "ㄹㅌ" to "ㄾ", "ㄹㅍ" to "ㄿ", "ㄹㅎ" to "ㅀ", "ㅂㅅ" to "ㅄ")
    private val finalSplit = mapOf("ㄳ" to ("ㄱ" to "ㅅ"), "ㄵ" to ("ㄴ" to "ㅈ"), "ㄶ" to ("ㄴ" to "ㅎ"), "ㄺ" to ("ㄹ" to "ㄱ"), "ㄻ" to ("ㄹ" to "ㅁ"), "ㄼ" to ("ㄹ" to "ㅂ"), "ㄽ" to ("ㄹ" to "ㅅ"), "ㄾ" to ("ㄹ" to "ㅌ"), "ㄿ" to ("ㄹ" to "ㅍ"), "ㅀ" to ("ㄹ" to "ㅎ"), "ㅄ" to ("ㅂ" to "ㅅ"))
    private var committed = ""
    private var initial: Char? = null
    private var vowel: Char? = null
    private var ending: String? = null

    fun snapshot() = State(committed, initial, vowel, ending)
    fun restore(state: State) { committed = state.committed; initial = state.initial; vowel = state.vowel; ending = state.ending }
    val composing get() = renderSyllable()
    val text get() = committed + composing
    val currentWord get() = text.substringAfterLast(' ').trim()
    val wordIsComplete get() = composing.isEmpty() && committed.isNotEmpty() && !committed.endsWith(' ')

    fun add(jamo: Char) {
        if (jamo in vowelOrder) addVowel(jamo) else if (jamo in initialOrder) addConsonant(jamo)
    }
    private fun addConsonant(c: Char) {
        if (initial == null) { initial = c; return }
        if (vowel == null) { committed += initial; initial = c; return }
        val currentFinal = ending
        if (currentFinal == null) {
            if (c in "ㄱㄲㄴㄷㄹㅁㅂㅅㅆㅇㅈㅊㅋㅌㅍㅎ") ending = c.toString()
            else { committed += renderSyllable(); initial = c; vowel = null; ending = null }
            return
        }
        val compound = compoundFinals[currentFinal + c]
        if (compound != null) ending = compound
        else { committed += renderSyllable(); initial = c; vowel = null; ending = null }
    }
    private fun addVowel(v: Char) {
        if (initial == null) initial = 'ㅇ'
        if (vowel == null) { vowel = v; return }
        val currentFinal = ending
        if (currentFinal != null) {
            val split = finalSplit[currentFinal]
            val carry = split?.first ?: ""
            val nextInitial = split?.second?.firstOrNull() ?: currentFinal.first()
            ending = carry.ifEmpty { null }
            committed += renderSyllable()
            initial = nextInitial; vowel = v; ending = null
            return
        }
        val combined = compoundVowels["$vowel$v"]
        if (combined != null) vowel = combined.first()
        else { committed += renderSyllable(); initial = 'ㅇ'; vowel = v; ending = null }
    }
    fun backspace() {
        when {
            ending != null -> {
                val split = finalSplit[ending!!]
                ending = if (split != null) split.first else null
            }
            vowel != null -> {
                val compound = compoundVowels.entries.firstOrNull { it.value.first() == vowel }
                if (compound != null) vowel = compound.key.first() else vowel = null
            }
            initial != null -> initial = null
            committed.isNotEmpty() -> committed = committed.dropLast(1)
        }
    }
    fun acceptWord(word: String, replaceCurrent: Boolean) {
        if (replaceCurrent) {
            committed = committed.dropLast((currentWord.length - composing.length).coerceAtLeast(0))
        } else {
            committed = text
            if (committed.isNotEmpty() && !committed.endsWith(' ')) committed += " "
        }
        initial = null; vowel = null; ending = null
        committed += word.trim() + " "
    }
    fun space() { if (text.isNotEmpty() && !text.endsWith(' ')) { committed = text + " "; initial = null; vowel = null; ending = null } }
    private fun renderSyllable(): String {
        val i = initial ?: return ""
        val v = vowel ?: return i.toString()
        val li = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".indexOf(i)
        val vi = vowelOrder.indexOf(v)
        val ti = finalOrder.indexOf(ending ?: "")
        if (li < 0 || vi < 0 || ti < 0) return "$i$v${ending.orEmpty()}"
        return (0xAC00 + (li * 21 + vi) * 28 + ti).toChar().toString()
    }
}

/** Small explicit, replaceable on-device phrase list. It is not a general language model. */
internal object LocalKoreanSuggestions {
    private val words = listOf("안녕하세요", "안녕", "감사합니다", "고마워요", "네", "아니요", "좋아요", "괜찮아요", "도와주세요", "물 주세요", "화장실", "병원", "집", "가고 싶어요", "먹고 싶어요", "쉬고 싶어요", "춥습니다", "덥습니다", "아파요", "잠깐만요", "이것", "저것", "오늘", "내일", "지금", "엄마", "아빠", "친구", "사랑해요")
    private val next = mapOf("안녕" to listOf("하세요", "잘"), "감사합니다" to listOf("정말", "도움이 됐어요"), "네" to listOf("고마워요", "좋아요"), "오늘" to listOf("괜찮아요", "집에 가고 싶어요"), "도와주세요" to listOf("병원에 가고 싶어요"))
    fun forText(text: String, limit: Int = 3): List<String> {
        if (text.endsWith(' ')) {
            val previous = text.trimEnd().substringAfterLast(' ')
            if (previous.isEmpty()) return listOf("안녕하세요", "네", "도와주세요").take(limit)
            return (next[previous] ?: emptyList()).take(limit)
        }
        val current = text.substringAfterLast(' ').trim()
        if (current.isEmpty()) return listOf("안녕하세요", "네", "도와주세요").take(limit)
        val matching = words.filter { it.startsWith(current) && it != current }
        if (matching.isNotEmpty()) return matching.take(limit)
        if (text.trimEnd().substringAfterLast(' ') == current) return (next[current] ?: emptyList()).take(limit)
        return emptyList()
    }
}
