package com.shev.agent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * User-enabled bridge for assistive UI automation. Android requires the device
 * owner to enable this service explicitly in system Settings.
 */
class MegaAgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        active.set(this)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_LAST_PACKAGE, rootInActiveWindow?.packageName?.toString().orEmpty())
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_LAST_PACKAGE, event.packageName?.toString().orEmpty())
            .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis())
            .apply()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        active.compareAndSet(this, null)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        active.compareAndSet(this, null)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        super.onDestroy()
    }

    fun visibleUiSnapshot(maxNodes: Int = 120): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val output = ArrayList<String>()
        collectVisibleText(root, output, maxNodes.coerceIn(1, 300))
        return output
    }

    fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        output: MutableList<String>,
        limit: Int
    ) {
        if (output.size >= limit) return
        val text = sequenceOf(node.text, node.contentDescription)
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" | ")
        if (text.isNotBlank()) output.add(text.take(500))
        for (index in 0 until node.childCount) {
            if (output.size >= limit) break
            node.getChild(index)?.let { child ->
                try { collectVisibleText(child, output, limit) } finally { child.recycle() }
            }
        }
    }

    companion object {
        private const val PREFS = "megaagent_device_bridge"
        private const val KEY_ENABLED = "accessibility_enabled"
        private const val KEY_LAST_PACKAGE = "last_package"
        private const val KEY_LAST_EVENT_AT = "last_event_at"
        private val active = AtomicReference<MegaAgentAccessibilityService?>()

        fun isEnabled(): Boolean = active.get() != null
        fun snapshot(maxNodes: Int = 120): List<String> = active.get()?.visibleUiSnapshot(maxNodes).orEmpty()
        fun tap(x: Float, y: Float): Boolean = active.get()?.tap(x, y) ?: false
        fun back(): Boolean = active.get()?.global(GLOBAL_ACTION_BACK) ?: false
        fun home(): Boolean = active.get()?.global(GLOBAL_ACTION_HOME) ?: false
        fun recents(): Boolean = active.get()?.global(GLOBAL_ACTION_RECENTS) ?: false
    }
}
