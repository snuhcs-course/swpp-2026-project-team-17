package dev.gazedot
import org.junit.Assert.*
import org.junit.Test

class CompletionPreferencesTest {
    private val alternatives=listOf("도와줘","도와줄게","도와주세요","도착했어","도착하면","도전해봐")
    @Test fun sixCandidatesAreKeptAndCacheCanReuseAllSix() {
        val input=PredictionInput(1,"도")
        val candidates=input.candidates(alternatives+"도와줘","")
        assertEquals(6,candidates.size)
        val cache=PredictionReuse();cache.remember(input,candidates)
        assertEquals(6,cache.reuse(input.copy(version=2)).size)
    }
    @Test fun frequentChoiceMovesFirstAndSurvivesReloadWithoutDraft() {
        val input=PredictionInput(1,"지금 도")
        val candidates=input.candidates(alternatives,"")
        val preferences=CompletionPreferences()
        repeat(3){preferences.record(candidates[3])}
        preferences.record(candidates[1])
        val restored=CompletionPreferences(preferences.snapshot())
        val ranked=restored.rank(input,candidates)
        assertEquals("도착했어 ",ranked.first().replacement)
        assertEquals(6,ranked.size)
        assertFalse(preferences.snapshot().keys.any{it.contains("지금")})
    }
    @Test fun matchingLearnedChoiceCanAppearBeforeNetworkAndNeverForAnotherPrefix() {
        val input=PredictionInput(1,"도")
        val preferences=CompletionPreferences()
        preferences.record(input.candidates(alternatives,"")[2])
        assertEquals("도와주세요 ",preferences.rank(input.copy(version=2),emptyList()).single().replacement)
        assertTrue(preferences.rank(PredictionInput(3,"가"),emptyList()).isEmpty())
        preferences.clear()
        assertTrue(preferences.rank(input,emptyList()).isEmpty())
    }
    @Test fun memoryIsBoundedAndDoesNotLearnAppendOnlySentenceContent() {
        val preferences=CompletionPreferences()
        for(i in 1..150) preferences.record(WritingCandidate(1,CandidateKind.WORD,0,1,"가","가$i "))
        assertEquals(100,preferences.snapshot().size)
        val before=preferences.snapshot()
        preferences.record(WritingCandidate(1,CandidateKind.CONTINUATION,1,1,"","private sentence"))
        assertEquals(before,preferences.snapshot())
    }
}
