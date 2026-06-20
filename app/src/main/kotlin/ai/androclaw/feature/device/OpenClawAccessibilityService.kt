package ai.androclaw.feature.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * Accessibility Service — gives the agent eyes and hands on the device.
 *
 * Capabilities:
 *   - Read on-screen text from any app
 *   - Find and click UI elements by text or resource ID
 *   - Perform swipe/scroll gestures
 *   - Take global actions (HOME, BACK, RECENTS, NOTIFICATIONS)
 *   - Monitor app usage (which app is in foreground)
 *
 * Must be enabled by the user in:
 *   Settings → Accessibility → OpenClaw → Enable
 *
 * The service runs in the main process. Expensive operations (OCR, NLP)
 * should be dispatched to background threads via AccessibilityTools.
 */
class OpenClawAccessibilityService : AccessibilityService() {

    // Singleton access — set when service is connected
    companion object {
        @Volatile private var instance: OpenClawAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null

        fun getInstance(): OpenClawAccessibilityService? = instance

        private val _screenEvents = MutableSharedFlow<ScreenEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
        val screenEvents: SharedFlow<ScreenEvent> = _screenEvents.asSharedFlow()
    }

    private var currentPackage: String = ""
    private var currentActivity: String = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        Timber.d("AccessibilityService: connected")
    }

    override fun onInterrupt() {
        Timber.w("AccessibilityService: interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Timber.d("AccessibilityService: destroyed")
    }

    // ── Event stream ──────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg      = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != currentPackage || className != currentActivity) {
                    currentPackage  = pkg
                    currentActivity = className
                    _screenEvents.tryEmit(ScreenEvent.AppForeground(pkg, className))
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text.joinToString(" ")
                if (text.isNotBlank()) {
                    _screenEvents.tryEmit(ScreenEvent.Notification(pkg, text))
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Intentionally not streaming keystrokes — privacy boundary
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Streamed on demand via getScreenText(), not auto-pushed
            }
        }
    }

    // ── Screen reading ────────────────────────────────────────────────────────

    /**
     * Get all visible text on the current screen, flattened into a single string.
     * Useful for the agent to understand what's on screen without taking a screenshot.
     */
    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        return buildString { collectText(root, this) }.trim()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrBlank()) sb.append(text).append(' ')
        if (!desc.isNullOrBlank() && desc != text) sb.append(desc).append(' ')
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
    }

    /**
     * Find a node by its visible text (partial match, case-insensitive).
     * Returns the first match or null.
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByText(text).firstOrNull()
    }

    /**
     * Find a node by its resource ID (e.g. "com.example:id/button_submit").
     */
    fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByViewId(resourceId).firstOrNull()
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Click a node found by visible text. Returns true if found and clicked. */
    fun clickByText(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Click a node found by resource ID. Returns true if found and clicked. */
    fun clickById(resourceId: String): Boolean {
        val node = findNodeById(resourceId) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Type text into the focused input field. */
    fun typeText(text: String): Boolean {
        val node = findFocusedInput() ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Scroll the first scrollable view in the given direction. */
    fun scroll(direction: ScrollDirection): Boolean {
        val root   = rootInActiveWindow ?: return false
        val action = when (direction) {
            ScrollDirection.DOWN  -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.UP    -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return findFirstScrollable(root)?.performAction(action) ?: false
    }

    /**
     * Perform a tap gesture at screen coordinates.
     * Useful when no node is available (e.g. canvas-based apps, games).
     */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Swipe from (x1, y1) to (x2, y2) over [durationMs] ms.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ── Global actions ────────────────────────────────────────────────────────

    fun pressHome()         = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressBack()         = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressRecents()      = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findFocusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val result = findFirstScrollable(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    fun getBoundsOf(text: String): Rect? {
        val node = findNodeByText(text) ?: return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    val foregroundPackage: String get() = currentPackage
    val foregroundActivity: String get() = currentActivity
}

// ── Event types ───────────────────────────────────────────────────────────────

sealed class ScreenEvent {
    data class AppForeground(val packageName: String, val activityName: String) : ScreenEvent()
    data class Notification(val packageName: String, val text: String) : ScreenEvent()
    data class TextChanged(val packageName: String, val text: String) : ScreenEvent()
}

enum class ScrollDirection { UP, DOWN }

