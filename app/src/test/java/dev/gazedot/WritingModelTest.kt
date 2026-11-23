package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class WritingModelTest {
    private fun editor(text: String = "기존 내용", start: Int = 2, end: Int = 2) =
        EditorSnapshot("com.kakao.talk", 7, 101, text, start, end)

    @Test fun selectionIsPreservedAndAdditionGoesAfterItsEndInEitherDirection() {
        for ((start, end) in listOf(0 to 2, 2 to 0)) {
            val before = editor(start = start, end = end)
            val plan = before.plan("도와주세요.")!!
            assertEquals("기존도와주세요. 내용", plan.text)
            assertEquals(8, plan.caret)
            assertTrue(plan.confirmed(before.copy(text = plan.text, start = 8, end = 8)))
            assertFalse(plan.confirmed(before.copy(text = plan.text)))
        }
    }
    @Test fun inputIdentityIncludesAppWindowSelectedNodeContentsAndCaret() {
        val before = editor()
        listOf(before.copy(packageName = "another.app"), before.copy(windowId = 8),
            before.copy(nodeKey = 102),
            before.copy(text = "changed"), before.copy(start = 1), before.copy(end = 4))
            .forEach { assertFalse(before.unchanged(it)) }
        assertNull(editor(start = -1).plan("추가"))
        assertNull(editor(end = 50).plan("추가"))
    }
    @Test fun hangulCompoundVowelsFinalsAndDeletionComposeCorrectly() {
        val draft = WritingDraft()
        "ㄱㅗㅏㄴ".forEach(draft::jamo)
        assertEquals("관", draft.text)
        draft.backspace(); assertEquals("과", draft.text)
        draft.backspace(); assertEquals("고", draft.text)
        draft.backspace(); assertEquals("ㄱ", draft.text)
        draft.backspace(); assertEquals("", draft.text)
        "ㄷㅏㄹㄱ".forEach(draft::jamo)
        assertEquals("닭", draft.text)
        draft.jamo('ㅏ'); assertEquals("달가", draft.text)
        draft.undo(); assertEquals("닭", draft.text)
    }
    @Test fun recoveredHangulCanBeDeletedByJamoAndEmojiIsNotSplit() {
        val draft = WritingDraft("값")
        draft.backspace(); assertEquals("갑", draft.text)
        draft.backspace(); assertEquals("가", draft.text)
        draft.replace("좋아요😀"); draft.backspace(); assertEquals("좋아요", draft.text)
        draft.space(); draft.space(); assertEquals("좋아요  ", draft.text)
        draft.undo(); assertEquals("좋아요 ", draft.text)
    }
    @Test fun aWordCandidateOnlyChangesCurrentWordAndCanBeUndoneExactly() {
        val draft = WritingDraft("앞에서 확정한 내용 ㄱ")
        val candidate = PredictionInput(draft.version, draft.text).candidates(listOf("감사합니다", "가요", "아니요"), "").first()
        assertTrue(draft.apply(candidate))
        assertEquals("앞에서 확정한 내용 감사합니다 ", draft.text)
        draft.undo(); assertEquals("앞에서 확정한 내용 ㄱ", draft.text)
        assertFalse(draft.apply(candidate))
    }
    @Test fun continuationIsAppendOnlyAndCannotReplaceConfirmedPrefix() {
        val draft = WritingDraft("앞에서 확정한 내용 ")
        val candidate = PredictionInput(draft.version, draft.text).candidates(emptyList(), "감사합니다.").single()
        assertTrue(draft.apply(candidate))
        assertEquals("앞에서 확정한 내용 감사합니다.", draft.text)
        draft.undo(); assertEquals("앞에서 확정한 내용 ", draft.text)
        assertFalse(draft.apply(candidate.copy(version = draft.version, start = 0, end = draft.text.length, expected = draft.text)))
    }
    @Test fun incompleteJamoPredictionsAreFilteredAndOldResultsCannotBeSelected() {
        val draft = WritingDraft("ㄱ")
        val candidates = PredictionInput(0, draft.text).candidates(listOf("감사합니다", "가요", "아니요", "다른 말"), "안녕하세요")
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.kind == CandidateKind.WORD })
        draft.jamo('ㅏ'); assertEquals("가", draft.text)
        assertFalse(draft.apply(candidates.first()))
        assertEquals("과자 ", PredictionInput(0, "고").candidates(listOf("과자"), "").single().replacement)
    }
    @Test fun gazeHeldCandidatesDoNotChangeEvenWhenANewerVersionArrives() {
        val gate = PredictionGate()
        val a = PredictionInput(1, "ㄱ").candidates(listOf("감사합니다"), "")
        val b = PredictionInput(2, "가").candidates(listOf("가요"), "")
        gate.input(1); assertTrue(gate.offer(1, a)); gate.hold(true)
        gate.input(2); assertFalse(gate.offer(1, a)); assertTrue(gate.offer(2, b))
        assertEquals(a, gate.displayed)
        gate.hold(false); assertEquals(b, gate.displayed)
        gate.clear(); assertTrue(gate.displayed.isEmpty())
    }
    @Test fun screenTransitionRequiresValidGazeExitAndHoldingDoesNotRepeat() {
        val dwell = WritingDwell()
        for (time in 0L..2_000L step 100) assertFalse(dwell.update("button", true, time, time))
        dwell.update(null, false, 2_100, 2_100)
        assertFalse(dwell.update("button", true, 2_200, 2_200))
        dwell.update(null, true, 2_300, 2_300)
        for (time in 2_400L..3_300L step 100) assertFalse(dwell.update("button", true, time, time))
        assertTrue(dwell.update("button", true, 3_400, 3_400))
        for (time in 3_500L..5_000L step 100) assertFalse(dwell.update("button", true, time, time))
        dwell.update(null, false, 5_100, 5_100)
        assertFalse(dwell.update("button", true, 5_200, 5_200))
    }
    @Test fun movingToAnotherButtonAfterTypingNeedsNoExtraLookAtBackground() {
        val dwell = WritingDwell()
        dwell.update(null, true, 0, 0)
        for (time in 100L..1_000L step 100) assertFalse(dwell.update("a", true, time, time))
        assertTrue(dwell.update("a", true, 1_100, 1_100))
        dwell.selected("a")
        for (time in 1_200L..2_100L step 100) assertFalse(dwell.update("b", true, time, time))
        assertTrue(dwell.update("b", true, 2_200, 2_200))
    }
    @Test fun heartbeatDuplicatesDoNotRestartStableTextingButtonDwell() {
        val dwell = WritingDwell()
        dwell.update(null, true, 0, 0)
        for (time in 100L..1_000L step 100) {
            assertFalse(dwell.update("phrase", true, time, time))
            val progress = dwell.progress
            assertFalse(dwell.update("phrase", true, time, time + 25))
            assertEquals(progress, dwell.progress, 0f)
        }
        assertTrue(dwell.update("phrase", true, 1_100, 1_100))
        assertFalse(dwell.update("phrase", true, 1_100, 1_125))
    }

    @Test fun heartbeatCannotFinishSelectionFromFrozenCameraOrAfterLeaving() {
        val dwell = WritingDwell()
        dwell.update(null, true, 0, 0)
        for (time in 100L..800L step 100) dwell.update("phrase", true, time, time)
        for (now in 850L..1_400L step 50) assertFalse(dwell.update("phrase", true, 800, now))
        assertEquals(0f, dwell.progress, 0f)
        dwell.update("phrase", true, 1_500, 1_500)
        dwell.update(null, true, 1_500, 1_510)
        assertFalse(dwell.update("phrase", true, 1_600, 1_600))
        assertEquals(0f, dwell.progress, 0f)
    }
    @Test fun incompleteKoreanGetsCompleteAlternativesInsteadOfSuffixSteps() {
        val input=PredictionInput(1,"지금 도")
        val candidates=input.candidates(listOf("도와줘", "도와줄게"),"와")
        assertEquals(listOf("도와줘 ","도와줄게 "),candidates.map{it.replacement})
        assertTrue(candidates.all{it.start==3 && it.expected=="도" && it.kind==CandidateKind.WORD})
        val before=EditorSnapshot("app",1,1,"지금 도",4,4)
        assertEquals("지금 도와줘 ",before.candidatePlan(candidates[0])!!.text)
        assertTrue(input.candidates(listOf("와","줘"),"와").isEmpty())
    }
    @Test fun completeShortPhrasesPreserveEarlierDraft() {
        val input=PredictionInput(1,"지금 도")
        val c=input.candidates(listOf("도와줄 수 있어", "도와주면 좋겠어"),"")
        assertEquals(2,c.size)
        assertEquals("도",c[0].expected)
        assertTrue(PredictionInput(2,"도 ").candidates(emptyList(),"고마워.").single().kind==CandidateKind.CONTINUATION)
    }
    @Test fun cachedAlternativesFollowTypingButNeverAnotherContextOrOldVersion() {
        val cache=PredictionReuse();val input=PredictionInput(1,"지금 도")
        cache.remember(input,input.candidates(listOf("도와줘","도와줄게"),""))
        val reused=cache.reuse(PredictionInput(2,"지금 도와"))
        assertEquals(2,reused.size)
        assertTrue(reused.all{it.version==2L && it.expected=="도와"})
        assertTrue(cache.reuse(PredictionInput(3,"내일 도와")).isEmpty())
        assertTrue(cache.reuse(PredictionInput(4,"지금 돈")).isEmpty())
        cache.clear();assertTrue(cache.reuse(input).isEmpty())
    }
}
