package dev.gazedot

/** Local frequency ranking of accepted words, never the draft or surrounding conversation. */
internal class CompletionPreferences(initial: Map<String,Int> = emptyMap()) {
    private val counts=linkedMapOf<String,Int>().apply {
        initial.entries.filter { it.key.isNotBlank() && it.key.length<=80 && it.value>0 }
            .sortedByDescending { it.value }.take(100).forEach { put(it.key,it.value.coerceAtMost(10_000)) }
    }
    fun record(candidate: WritingCandidate) {
        if(candidate.kind!=CandidateKind.WORD) return
        val word=candidate.replacement.trim()
        if(word.isBlank() || word.length>80 || word.any{it.isISOControl()}) return
        counts[word]=((counts[word]?:0)+1).coerceAtMost(10_000)
        if(counts.size>100) counts.remove(counts.entries.minBy { it.value }.key)
    }
    fun snapshot(): Map<String,Int> = counts.toMap()
    fun clear() = counts.clear()
    fun rank(input: PredictionInput, candidates: List<WritingCandidate>): List<WritingCandidate> {
        val learned=input.candidates(counts.entries.sortedByDescending{it.value}.map{it.key}, "").take(2)
        val ai=candidates.filter{it.version==input.version && it.kind==CandidateKind.WORD}
            .sortedByDescending{counts[it.replacement.trim()]?:0}
        val words=(learned+ai).distinctBy{it.replacement}.take(6)
        return words+candidates.filter{it.version==input.version && it.kind==CandidateKind.CONTINUATION}.take(6-words.size)
    }
}
