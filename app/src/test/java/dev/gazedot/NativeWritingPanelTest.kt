package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class NativeWritingPanelTest {
    @Test fun panelNeverCoversEditorKeyboardOrSystemTopInset() {
        for (desired in listOf(180, 400, 900)) {
            val panel = NativePanelPlacement.aboveEditor(520, 460, 28, desired, 8, 96)!!
            assertTrue(panel.top >= 36)
            assertEquals(452, panel.top + panel.height)
            assertTrue(panel.height <= desired)
        }
    }
    @Test fun hiddenKeyboardOrInsufficientSpaceDoesNotProduceOverlappingPanel() {
        assertNull(NativePanelPlacement.aboveEditor(null, 460, 28, 240, 8, 96))
        assertNull(NativePanelPlacement.aboveEditor(140, 100, 28, 240, 8, 96))
    }
    @Test fun floatingKeyboardStillKeepsPanelAboveKeyboardButUnknownEditorBoundsHideIt() {
        val panel = NativePanelPlacement.aboveEditor(350, 500, 28, 200, 8, 96)!!
        assertEquals(342, panel.top + panel.height)
        assertNull(NativePanelPlacement.aboveEditor(350, 0, 28, 200, 8, 96))
    }
    @Test fun growingComposerMovesPanelUpWithoutCoveringItsFirstLine() {
        val singleLine = NativePanelPlacement.aboveEditor(900, 820, 28, 300, 12, 120)!!
        val multiline = NativePanelPlacement.aboveEditor(900, 600, 28, 300, 12, 120)!!
        assertEquals(220, singleLine.top - multiline.top)
        assertEquals(588, multiline.top + multiline.height)
        assertNull(NativePanelPlacement.aboveEditor(900, 140, 28, 300, 12, 120))
    }
    @Test fun wordCandidateReplacesOnlyTheUnfinishedLastWord() {
        val editor = EditorSnapshot("com.samsung.android.messaging", 1, 99, "오늘 안ㄴ", 5, 5)
        val candidate = PredictionInput(8, editor.text).candidates(listOf("안녕"), "").single()
        val plan = editor.candidatePlan(candidate)!!
        assertEquals("오늘 안녕 ", plan.text)
        assertEquals(plan.text.length, plan.caret)
        assertNull(editor.copy(text = "오늘 다른", start = 5, end = 5).candidatePlan(candidate))
        assertNull(editor.copy(start = 1, end = 1).candidatePlan(candidate))
    }
    @Test fun continuationAppendsWithoutReplacingPreviousText() {
        val editor = EditorSnapshot("com.samsung.android.messaging", 1, 99, "안녕. ", 4, 4)
        val candidate = PredictionInput(9, editor.text).candidates(emptyList(), "나중에 연락할게.").single()
        assertEquals("안녕. 나중에 연락할게.", editor.candidatePlan(candidate)!!.text)
        assertTrue(PredictionInput(10,"안녕").candidates(emptyList(),"하세요").isEmpty())
        assertNull(editor.copy(start = 0, end = 2).candidatePlan(candidate))
    }
}
