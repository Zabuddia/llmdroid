package com.alanix.llmdroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.alanix.llmdroid.model.UIElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    fun tapNodeWithText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return tapNodeWithTextRecursive(root, text)
    }

    private fun tapNodeWithTextRecursive(node: AccessibilityNodeInfo, text: String): Boolean {
        val label = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (label == text && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (tapNodeWithTextRecursive(child, text)) return true
        }
        return false
    }

    suspend fun swipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
        }
    }

    fun tapConfirmButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        return tapConfirmRecursive(root)
    }

    private fun tapConfirmRecursive(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
            if (label in listOf("ok", "enter", "done", "confirm", "unlock", "→", "➜")) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (tapConfirmRecursive(child)) return true
        }
        return false
    }

    fun trySetTextOnEditableNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return trySetTextRecursive(root, text)
    }

    private fun trySetTextRecursive(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.isEditable || node.isPassword) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (trySetTextRecursive(child, text)) return true
        }
        return false
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
