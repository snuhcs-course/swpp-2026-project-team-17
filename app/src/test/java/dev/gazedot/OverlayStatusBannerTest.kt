package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class OverlayStatusBannerTest {
    private class Window {
        var text = ""
        var visible = false
        var registered = false
        var attached = false
        var removals = 0
        var adds = 0
        var failRemoval = false
        val banner = OverlayStatusBanner(
            setContent = { text, visible -> this.text = text; this.visible = visible },
            addWindow = { check(!registered); registered = true; adds++ },
            removeWindow = {
                removals++
                if (failRemoval) throw IllegalStateException("Window removal failed")
                registered = false; attached = false
            },
        )
        fun finishLayout() { attached = registered }
    }

    @Test fun normalTrackingBeforeFirstAttachmentCancelsThePendingWarning() {
        val window = Window()
        window.banner.show("시선 좌표를 사용할 수 없어요")
        assertTrue(window.registered)
        assertFalse(window.attached)
        window.banner.show("") // normal tracking arrives before Android's first layout
        window.finishLayout()
        assertFalse(window.attached)
        assertFalse(window.visible)
        assertEquals("", window.text)
        assertEquals(1, window.removals)
    }

    @Test fun failedRemovalHidesWarningImmediatelyAndRetriesOnFollowingNormalFrames() {
        val window = Window()
        window.banner.show("시선 좌표를 사용할 수 없어요")
        window.finishLayout()
        window.failRemoval = true
        window.banner.show("")
        assertTrue(window.registered)
        assertFalse(window.visible)
        assertEquals("", window.text)
        window.failRemoval = false
        window.banner.show("")
        assertFalse(window.registered)
        assertEquals(2, window.removals)
    }

    @Test fun aNewWarningAfterFailedRemovalReusesTheExistingWindow() {
        val window = Window()
        window.banner.show("이전 경고")
        window.failRemoval = true
        window.banner.clear()
        window.banner.show("새 경고")
        assertEquals(1, window.adds)
        assertTrue(window.visible)
        assertEquals("새 경고", window.text)
        window.failRemoval = false
        window.banner.clear()
        window.banner.show("다음 경고")
        assertEquals(2, window.adds)
    }

    @Test fun closingAlsoClearsContentWhenRemovalFails() {
        val window = Window()
        window.banner.show("시선 좌표를 사용할 수 없어요")
        window.failRemoval = true
        window.banner.clear()
        assertFalse(window.visible)
        assertEquals("", window.text)
    }
}
