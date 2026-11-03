package dev.gazedot

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal enum class EasyActionKind { CLICK, FOCUS, FORWARD, BACKWARD, BACK }

internal data class EasyScreenAction(
    val id: String,
    val label: String,
    val kind: EasyActionKind,
    val bounds: Rect,
    val viewId: String?,
    val className: String,
)

internal data class EasyScreenSnapshot(
    val packageName: String,
    val windowId: Int,
    val actions: List<EasyScreenAction>,
)

/** Read visible action labels locally. No images, labels or coordinates are stored or uploaded. */
@Suppress("DEPRECATION")
internal class EasyScreenActions(private val service: AccessibilityService) {
    private fun root(): AccessibilityNodeInfo? = service.windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
    }?.root ?: service.rootInActiveWindow

    private fun label(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (node.isPassword || !node.isVisibleToUser) return ""
        if (node.isEditable) return "텍스트 입력칸 선택"
        val own = (node.contentDescription ?: node.text)?.toString()?.trim().orEmpty()
        if (own.isNotBlank()) return own.take(100)
        if (depth >= 3) return ""
        val words = mutableListOf<String>()
        for (i in 0 until minOf(node.childCount, 12)) {
            val child = node.getChild(i) ?: continue
            try { label(child, depth + 1).takeIf { it.isNotBlank() }?.let(words::add) }
            finally { child.recycle() }
            if (words.size >= 3) break
        }
        return words.distinct().joinToString(" · ").take(100)
    }

    fun capture(): EasyScreenSnapshot? {
        val root = root() ?: return null
        try {
            val packageName = root.packageName?.toString() ?: return null
            val windowId = root.windowId
            val screen = DisplaySpace.bounds(service)
            val actions = mutableListOf<EasyScreenAction>()
            val seen = mutableSetOf<String>()
            var visits = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++visits > 500 || depth > 24 || !node.isVisibleToUser || node.isPassword) return
                val rect = Rect().also(node::getBoundsInScreen)
                if (node.isEnabled && !rect.isEmpty && Rect.intersects(screen, rect)) {
                    fun add(kind: EasyActionKind, text: String) {
                        val key = "${node.viewIdResourceName}|${node.className}|$rect|$kind"
                        if (seen.add(key)) actions += EasyScreenAction(
                            key, text, kind, Rect(rect), node.viewIdResourceName, node.className?.toString().orEmpty())
                    }
                    if (node.isClickable || node.isEditable) {
                        val text = label(node)
                        if (text.isNotBlank()) add(if (node.isEditable) EasyActionKind.FOCUS else EasyActionKind.CLICK, text)
                    }
                    val supported = node.actionList.map { it.id }
                    if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in supported) add(EasyActionKind.FORWARD, "다음 내용 보기")
                    if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in supported) add(EasyActionKind.BACKWARD, "이전 내용 보기")
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try { visit(child, depth + 1) } finally { child.recycle() }
                }
            }
            visit(root, 0)
            // Keep all discovered actions within the traversal budget; proximity ranking happens later.
            actions += EasyScreenAction("system:back", "뒤로 가기", EasyActionKind.BACK, Rect(), null, "")
            return EasyScreenSnapshot(packageName, windowId, actions)
        } finally { root.recycle() }
    }

    fun isCurrent(snapshot: EasyScreenSnapshot): Boolean {
        val root = root() ?: return false
        return try { root.windowId == snapshot.windowId && root.packageName?.toString() == snapshot.packageName }
        finally { root.recycle() }
    }

    fun execute(snapshot: EasyScreenSnapshot, action: EasyScreenAction): Boolean {
        if (!TrackerState.running || !TrackerState.controls || !TrackerState.interactionAllowed ||
            TrackerState.collecting || TrackerState.speaking || !TrackerState.easyPanelEnabled) return false
        val root = root() ?: return false
        try {
            if (root.windowId != snapshot.windowId || root.packageName?.toString() != snapshot.packageName) return false
            if (action.kind == EasyActionKind.BACK) return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            var visits = 0
            fun find(node: AccessibilityNodeInfo, depth: Int): Boolean {
                if (++visits > 500 || depth > 24 || !node.isVisibleToUser || node.isPassword) return false
                val rect = Rect().also(node::getBoundsInScreen)
                if (node.isEnabled && rect == action.bounds && node.viewIdResourceName == action.viewId &&
                    node.className?.toString().orEmpty() == action.className) {
                    val command = when (action.kind) {
                        EasyActionKind.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
                        EasyActionKind.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS
                        EasyActionKind.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        EasyActionKind.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        EasyActionKind.BACK -> return false
                    }
                    // A recycled row can retain its view ID and bounds but represent different content.
                    if (action.kind in listOf(EasyActionKind.CLICK, EasyActionKind.FOCUS) && label(node) != action.label) return false
                    return node.actionList.any { it.id == command } && node.performAction(command)
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try { if (find(child, depth + 1)) return true } finally { child.recycle() }
                }
                return false
            }
            return find(root, 0)
        } finally { root.recycle() }
    }
}
