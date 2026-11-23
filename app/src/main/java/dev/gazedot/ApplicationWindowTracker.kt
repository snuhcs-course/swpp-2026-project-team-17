package dev.gazedot

internal data class ApplicationWindowKey(val packageName: String, val windowId: Int)

/** Only an actual application-window transition resets persistent interaction modes. */
internal class ApplicationWindowTracker {
    private var previous: ApplicationWindowKey? = null

    fun changed(current: ApplicationWindowKey?): Boolean {
        val changed = current == null || current != previous
        previous = current
        return changed
    }
}
