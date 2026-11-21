package dev.gazedot

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class MessagingEditorsTest {
    @Test fun emptyEditorWithoutSelectionCanConnectAndInsertAtZero() {
        for ((start, end) in listOf(-1 to -1, 0 to -1, 5 to 5, 0 to 0)) {
            val empty = EditorSnapshot("com.samsung.android.messaging", 5, 123, "", start, end)
                .withEmptySelectionFallback()
            assertTrue(empty.validSelection)
            assertEquals(0, empty.start)
            assertEquals(0, empty.end)
            assertEquals("안녕", empty.plan("안녕")?.text)
        }
    }

    @Test fun nonemptyEditorNeverGuessesMissingSelectionOrDuplicatesConfirmedText() {
        val before = EditorSnapshot("com.samsung.android.messaging", 5, 123, "", 0, 0)
        val plan = before.plan("안녕")!!
        val actual = before.copy(text = "안녕", start = -1, end = -1).withEmptySelectionFallback()
        assertFalse(actual.validSelection)
        assertNull(actual.plan("추가"))
        assertTrue(plan.textConfirmed(actual))
        assertFalse(plan.confirmed(actual))
        val selected = actual.copy(start = 0, end = 1)
        assertEquals(selected, selected.withEmptySelectionFallback())
    }

    @Test fun messageEditorsNeedNeitherResourceIdNorConversationTitle() {
        for (pkg in listOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.kakao.talk")) {
            assertTrue(MessagingEditors.supports(pkg, false,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
            assertTrue(MessagingEditors.supports(pkg, false, InputType.TYPE_NULL))
        }
        val emptyEditor = EditorSnapshot("com.samsung.android.messaging", 5, 123, "", 0, 0)
        assertEquals("안녕하세요", emptyEditor.plan("안녕하세요")?.text)
    }

    @Test fun passwordFieldsAndOtherAppsRemainExcluded() {
        val pkg = "com.samsung.android.messaging"
        assertFalse(MessagingEditors.supports("another.app", false, InputType.TYPE_CLASS_TEXT))
        assertFalse(MessagingEditors.supports(pkg, true, InputType.TYPE_CLASS_TEXT))
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
            assertFalse(MessagingEditors.supports(pkg, false, InputType.TYPE_CLASS_TEXT or variation))
        }
        assertFalse(MessagingEditors.supports(pkg, false,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test fun insertionConfirmationRejectsAnotherSelectedNode() {
        val before = EditorSnapshot("com.samsung.android.messaging", 5, 123, "", 0, 0)
        val plan = before.plan("안녕")!!
        val inserted = before.copy(text = "안녕", start = 2, end = 2)
        assertTrue(plan.confirmed(inserted))
        assertFalse(plan.confirmed(inserted.copy(nodeKey = 124)))
        assertFalse(plan.confirmed(inserted.copy(windowId = 6)))
    }
}
