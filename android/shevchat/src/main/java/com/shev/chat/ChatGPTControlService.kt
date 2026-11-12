package com.shev.chat

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional, user-enabled helper. It only reacts to ChatGPT package windows and
 * only performs the explicitly queued "new chat" action.
 */
class ChatGPTControlService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != CHATGPT_PACKAGE) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_NEW_CHAT_REQUESTED, false)) return

        val requestedAt = prefs.getLong(KEY_NEW_CHAT_REQUESTED_AT, 0L)
        if (requestedAt <= 0L || System.currentTimeMillis() - requestedAt > REQUEST_TTL_MILLIS) {
            prefs.edit().remove(KEY_NEW_CHAT_REQUESTED).remove(KEY_NEW_CHAT_REQUESTED_AT).apply()
            return
        }

        val root = rootInActiveWindow ?: return
        if (clickNewChat(root)) {
            prefs.edit().remove(KEY_NEW_CHAT_REQUESTED).remove(KEY_NEW_CHAT_REQUESTED_AT).apply()
        }
    }

    private fun clickNewChat(root: AccessibilityNodeInfo): Boolean {
        val labels = listOf(
            "New chat", "Новый чат", "Start a new chat",
            "Начать новый чат", "New conversation", "Новый разговор"
        )
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                if (node.isVisibleToUser && node.isEnabled) {
                    val target = clickableAncestor(node) ?: node
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
            }
        }
        return false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    override fun onInterrupt() = Unit

    companion object {
        const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        const val PREFS = "shev_chat_control"
        const val KEY_NEW_CHAT_REQUESTED = "new_chat_requested"
        const val KEY_NEW_CHAT_REQUESTED_AT = "new_chat_requested_at"
        private const val REQUEST_TTL_MILLIS = 20_000L
    }
}
