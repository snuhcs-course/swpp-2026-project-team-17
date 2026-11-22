package dev.gazedot

/** Window registration is independent of whether Android has attached/drawn the view yet. */
internal class OverlayStatusBanner(
    private val setContent: (String, Boolean) -> Unit,
    private val addWindow: () -> Unit,
    private val removeWindow: () -> Unit,
) {
    private var registered = false

    fun show(message: String): Boolean {
        if (message.isBlank()) {
            clear()
            return false
        }
        setContent(message, true)
        if (!registered) {
            try {
                addWindow()
                registered = true
            } catch (_: RuntimeException) {
                setContent("", false)
                return false
            }
        }
        return true
    }

    fun clear() {
        // Hide and erase first: even a failed window removal must not leave an old warning.
        setContent("", false)
        if (!registered) return
        try {
            removeWindow()
            registered = false
        } catch (_: RuntimeException) {
            // Keep ownership so the next normal frame retries removal.
        }
    }
}
