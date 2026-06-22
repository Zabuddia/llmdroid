package com.alanix.llmdroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.alanix.llmdroid.model.AgentAction
import com.alanix.llmdroid.model.UIElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ActionResult(val success: Boolean, val error: String? = null, val data: String? = null)

class GestureExecutor(private val service: LLMAccessibilityService) {

    companion object {
        private const val TAG = "GestureExecutor"
    }

    suspend fun execute(action: AgentAction, tree: List<UIElement>): ActionResult {
        return try {
            when (action.action) {
                "tap" -> tapIndex(action.index ?: 0, tree)
                "longpress" -> longPressIndex(action.index ?: 0, tree)
                "focus" -> focusIndex(action.index ?: 0, tree)
                "replace_text" -> replaceText(action.index ?: 0, action.text ?: "", tree)
                "type" -> typeText(action.text ?: "")
                "paste" -> pasteIndex(action.index ?: 0, tree)
                "clear" -> clearIndex(action.index ?: 0, tree)
                "enter" -> doEnter()
                "back" -> globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "notifications" -> globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "recents" -> globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "lock_screen" -> globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                "wait" -> doWait(action.duration ?: 1000)
                "swipe" -> doSwipe(action.x1 ?: 0, action.y1 ?: 0, action.x2 ?: 0, action.y2 ?: 0, action.duration ?: 300)
                "launch" -> doLaunch(action.packageName ?: "")
                "open_url" -> doOpenUrl(action.url ?: "")
                "open_settings" -> doOpenSettings(action.setting)
                "keyevent" -> doKeyEvent(action.code ?: 0)
                "intent" -> doIntent(action)
                "clipboard_set" -> doClipboardSet(action.text ?: "")
                "clipboard_get" -> doClipboardGet()
                else -> ActionResult(false, "Unknown action: ${action.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Action ${action.action} threw", e)
            ActionResult(false, e.message)
        }
    }

    // --- Index helpers ---

    private fun elementAt(index: Int, tree: List<UIElement>): UIElement? = tree.getOrNull(index)

    private fun nodeAt(index: Int, tree: List<UIElement>): AccessibilityNodeInfo? {
        val el = elementAt(index, tree) ?: return null
        return service.findNodeAt(el.center[0], el.center[1])
    }

    // --- Action implementations ---

    private suspend fun tapIndex(index: Int, tree: List<UIElement>): ActionResult {
        val el = elementAt(index, tree) ?: return ActionResult(false, "Index $index out of range")
        val node = service.findNodeAt(el.center[0], el.center[1])
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ActionResult(true)
        return dispatchTap(el.center[0], el.center[1])
    }

    private suspend fun longPressIndex(index: Int, tree: List<UIElement>): ActionResult {
        val el = elementAt(index, tree) ?: return ActionResult(false, "Index $index out of range")
        val node = service.findNodeAt(el.center[0], el.center[1])
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return ActionResult(true)
        return dispatchSwipe(el.center[0], el.center[1], el.center[0], el.center[1], 1000)
    }

    private fun focusIndex(index: Int, tree: List<UIElement>): ActionResult {
        val node = nodeAt(index, tree) ?: return ActionResult(false, "Index $index not found")
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) ||
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return ActionResult(focused, if (!focused) "Focus failed" else null)
    }

    private fun replaceText(index: Int, text: String, tree: List<UIElement>): ActionResult {
        val node = nodeAt(index, tree) ?: return ActionResult(false, "Index $index not found")
        // 1. Focus the node
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // 2. Try direct setText
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return ActionResult(true)
        }

        // 3. Fallback: select-all then paste
        doClipboardSet(text)
        node.performAction(android.R.id.selectAll)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            ActionResult(true)
        } else {
            ActionResult(false, "replace_text fallback paste also failed")
        }
    }

    private fun typeText(text: String): ActionResult {
        val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return ActionResult(false, "No focused editable node")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return ActionResult(ok, if (!ok) "SET_TEXT failed on focused node" else null)
    }

    private fun pasteIndex(index: Int, tree: List<UIElement>): ActionResult {
        val node = nodeAt(index, tree) ?: return ActionResult(false, "Index $index not found")
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return ActionResult(ok, if (!ok) "Paste failed" else null)
    }

    private fun clearIndex(index: Int, tree: List<UIElement>): ActionResult {
        val node = nodeAt(index, tree) ?: return ActionResult(false, "Index $index not found")
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return ActionResult(true)
        }
        // Fallback: select-all + delete
        node.performAction(android.R.id.selectAll)
        return doKeyEvent(android.view.KeyEvent.KEYCODE_DEL)
    }

    private fun doEnter(): ActionResult {
        val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return ActionResult(true)
            }
        }
        return doKeyEvent(android.view.KeyEvent.KEYCODE_ENTER)
    }

    private fun globalAction(action: Int): ActionResult {
        val ok = service.performGlobalAction(action)
        return ActionResult(ok, if (!ok) "Global action failed" else null)
    }

    private suspend fun doWait(duration: Int): ActionResult {
        delay(duration.toLong().coerceIn(0, 10_000))
        return ActionResult(true)
    }

    private suspend fun doSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): ActionResult =
        dispatchSwipe(x1, y1, x2, y2, duration)

    private fun doLaunch(packageName: String): ActionResult {
        if (packageName.isEmpty()) return ActionResult(false, "packageName is empty")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "Package not found: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return ActionResult(true)
    }

    private fun doOpenUrl(url: String): ActionResult {
        if (url.isEmpty()) return ActionResult(false, "url is empty")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(intent)
        return ActionResult(true)
    }

    private fun doOpenSettings(setting: String?): ActionResult {
        val action = when (setting) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "dnd" -> "android.settings.ZEN_MODE_SETTINGS"
            "network" -> Settings.ACTION_WIRELESS_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            service.startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            ActionResult(true)
        } catch (e: Exception) {
            ActionResult(false, "Settings intent failed: ${e.message}")
        }
    }

    private fun doKeyEvent(code: Int): ActionResult {
        return try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", code.toString()))
            ActionResult(true)
        } catch (e: Exception) {
            ActionResult(false, "keyevent failed: ${e.message}")
        }
    }

    private fun doIntent(action: AgentAction): ActionResult {
        val intentAction = action.intentAction
            ?: return ActionResult(false, "intentAction field is required for intent action")
        val uri = action.uri?.let { Uri.parse(it) }
        val intent = Intent(intentAction).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uri != null) data = uri
            action.packageName?.let { setPackage(it) }
        }
        return try {
            service.startActivity(intent)
            ActionResult(true)
        } catch (e: Exception) {
            ActionResult(false, "Intent failed: ${e.message}")
        }
    }

    private fun doClipboardSet(text: String): ActionResult {
        val cm = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("llmdroid", text))
        return ActionResult(true)
    }

    private fun doClipboardGet(): ActionResult {
        val cm = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return ActionResult(true, data = text)
    }

    // --- Gesture helpers ---

    private suspend fun dispatchTap(x: Int, y: Int): ActionResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): ActionResult {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.toLong())
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult =
        suspendCancellableCoroutine { cont ->
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(ActionResult(true))
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(ActionResult(false, "Gesture cancelled"))
                    }
                },
                null
            )
        }
}
