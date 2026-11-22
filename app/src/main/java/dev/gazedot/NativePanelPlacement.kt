package dev.gazedot

internal data class NativePanelPlacement(val top: Int, val height: Int) {
    companion object {
        fun aboveEditor(keyboardTop: Int?, editorTop: Int, topInset: Int,
            desiredHeight: Int, gap: Int, minimumHeight: Int): NativePanelPlacement? {
            if (keyboardTop == null || editorTop <= topInset || desiredHeight <= 0) return null
            val bottom = minOf(keyboardTop, editorTop) - gap
            val available = bottom - topInset - gap
            if (available < minimumHeight) return null
            val height = desiredHeight.coerceAtMost(available).coerceAtLeast(0)
            return NativePanelPlacement(bottom - height, height)
        }
    }
}
