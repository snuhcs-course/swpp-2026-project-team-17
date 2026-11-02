package dev.gazedot

/** Small 2-set Hangul composer for the NoTouch IME. Input is compatibility jamo. */
internal class HangulComposer {
    private val input = mutableListOf<Char>()
    val text: String get() = compose(input)
    val isEmpty: Boolean get() = input.isEmpty()

    fun append(value: Char) { input += value }
    fun backspace() { if (input.isNotEmpty()) input.removeAt(input.lastIndex) }
    fun clear() { input.clear() }

    private fun compose(chars: List<Char>): String {
        val out = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val initial = leading[chars[i]]
            if (initial == null) { out.append(chars[i++]); continue }
            if (i + 1 >= chars.size || vowel[chars[i + 1]] == null) {
                out.append(chars[i++]); continue
            }

            val l = initial
            var v = vowel.getValue(chars[i + 1])
            i += 2
            val compoundVowel = if (i < chars.size) vowelCombinations[chars[i - 1] to chars[i]] else null
            if (compoundVowel != null) { v = compoundVowel; i++ }

            var t = 0
            if (i < chars.size) {
                val firstFinal = finalIndex[chars[i]]
                val nextIsVowel = i + 1 < chars.size && vowel.containsKey(chars[i + 1])
                if (firstFinal != null && !nextIsVowel) {
                    val pair = if (i + 1 < chars.size) finalCombinations[chars[i] to chars[i + 1]] else null
                    val vowelAfterPair = i + 2 < chars.size && vowel.containsKey(chars[i + 2])
                    if (pair != null && !vowelAfterPair) {
                        t = pair
                        i += 2
                    } else {
                        t = firstFinal
                        i++
                    }
                }
            }
            out.append((0xAC00 + (l * 21 + v) * 28 + t).toChar())
        }
        return out.toString()
    }

    private companion object {
        val leading = mapOf(
            'ㄱ' to 0, 'ㄲ' to 1, 'ㄴ' to 2, 'ㄷ' to 3, 'ㄸ' to 4, 'ㄹ' to 5,
            'ㅁ' to 6, 'ㅂ' to 7, 'ㅃ' to 8, 'ㅅ' to 9, 'ㅆ' to 10, 'ㅇ' to 11,
            'ㅈ' to 12, 'ㅉ' to 13, 'ㅊ' to 14, 'ㅋ' to 15, 'ㅌ' to 16, 'ㅍ' to 17, 'ㅎ' to 18
        )
        val vowel = mapOf(
            'ㅏ' to 0, 'ㅐ' to 1, 'ㅑ' to 2, 'ㅒ' to 3, 'ㅓ' to 4, 'ㅔ' to 5, 'ㅕ' to 6,
            'ㅖ' to 7, 'ㅗ' to 8, 'ㅘ' to 9, 'ㅙ' to 10, 'ㅚ' to 11, 'ㅛ' to 12, 'ㅜ' to 13,
            'ㅝ' to 14, 'ㅞ' to 15, 'ㅟ' to 16, 'ㅠ' to 17, 'ㅡ' to 18, 'ㅢ' to 19, 'ㅣ' to 20
        )
        val finalIndex = mapOf(
            'ㄱ' to 1, 'ㄲ' to 2, 'ㄴ' to 4, 'ㄷ' to 7, 'ㄹ' to 8, 'ㅁ' to 16, 'ㅂ' to 17,
            'ㅅ' to 19, 'ㅆ' to 20, 'ㅇ' to 21, 'ㅈ' to 22, 'ㅊ' to 23, 'ㅋ' to 24,
            'ㅌ' to 25, 'ㅍ' to 26, 'ㅎ' to 27
        )
        val finalCombinations = mapOf(
            ('ㄱ' to 'ㅅ') to 3, ('ㄴ' to 'ㅈ') to 5, ('ㄴ' to 'ㅎ') to 6,
            ('ㄹ' to 'ㄱ') to 9, ('ㄹ' to 'ㅁ') to 10, ('ㄹ' to 'ㅂ') to 11,
            ('ㄹ' to 'ㅅ') to 12, ('ㄹ' to 'ㅌ') to 13, ('ㄹ' to 'ㅍ') to 14,
            ('ㄹ' to 'ㅎ') to 15, ('ㅂ' to 'ㅅ') to 18
        )
        val vowelCombinations = mapOf(
            ('ㅗ' to 'ㅏ') to 9, ('ㅗ' to 'ㅐ') to 10, ('ㅗ' to 'ㅣ') to 11,
            ('ㅜ' to 'ㅓ') to 14, ('ㅜ' to 'ㅔ') to 15, ('ㅜ' to 'ㅣ') to 16,
            ('ㅡ' to 'ㅣ') to 19
        )
    }
}
