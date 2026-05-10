package com.alanix.llmdroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.alanix.llmdroid.model.UIElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class LLMAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LLMAccessibility"
        val isRunning = MutableStateFlow(false)
        var instance: LLMAccessibilityService? = null

        fun isEnabledOnDevice(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val ourComponent = ComponentName(context, LLMAccessibilityService::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK)
                .any { info ->
                    info.resolveInfo.serviceInfo.let { si ->
                        ComponentName(si.packageName, si.name) == ourComponent
                    }
                }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        instance = this
        isRunning.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Snapshots are captured on-demand via getScreenTree()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
        instance = null
        isRunning.value = false
    }

    suspend fun getScreenTree(): List<UIElement> {
        // Retry with increasing delays — some apps take 500ms+ to render after launch
        val delays = longArrayOf(50, 100, 200, 300, 500)
        for (delayMs in delays) {
            val root = rootInActiveWindow
            if (root != null) {
                val elements = ScreenTreeBuilder.capture(root)
                if (elements.isEmpty() && delayMs < delays.last()) {
                    delay(delayMs)
                    continue
                }
                return elements
            }
            delay(delayMs)
        }
        Log.w(TAG, "rootInActiveWindow null or empty after retries")
        return emptyList()
    }

    fun findNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeAtRecursive(root, x, y)
    }

    private fun findNodeAtRecursive(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        if (!rect.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeAtRecursive(child, x, y)
            if (found != null) return found
        }

        return if (node.isClickable || node.isLongClickable || node.isEditable || node.isFocusable) {
            node
        } else {
            null
        }
    }
}
