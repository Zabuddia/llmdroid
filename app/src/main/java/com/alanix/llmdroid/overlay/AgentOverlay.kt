package com.alanix.llmdroid.overlay

import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.alanix.llmdroid.agent.AgentService
import com.alanix.llmdroid.model.AgentStatus
import com.alanix.llmdroid.ui.theme.LLMDroidTheme

class AgentOverlay(private val service: LifecycleService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)

    // A self-contained lifecycle that starts at INITIALIZED so performRestore() is happy,
    // then is advanced to RESUMED in show() and DESTROYED in destroy().
    private val overlayLifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    // SavedStateRegistryController.performRestore() calls performAttach() which asserts
    // lifecycle is INITIALIZED — satisfied here because overlayLifecycleOwner.registry
    // starts at INITIALIZED (property initialisation order: overlayLifecycleOwner first).
    private val savedStateOwner = object : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = overlayLifecycleOwner.lifecycle
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        init { controller.performRestore(null) }
    }

    private var pillView: ComposeView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 220
    }

    fun show() {
        if (!Settings.canDrawOverlays(service)) return
        if (pillView != null) return

        overlayLifecycleOwner.registry.currentState = Lifecycle.State.RESUMED

        val view = ComposeView(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setViewTreeLifecycleOwner(overlayLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                LLMDroidTheme(darkTheme = true, dynamicColor = false) {
                    OverlayPill()
                }
            }
        }
        setupDrag(view)
        pillView = view
        windowManager.addView(view, params)
    }

    fun destroy() {
        pillView?.let { windowManager.removeView(it) }
        pillView = null
        if (overlayLifecycleOwner.registry.currentState != Lifecycle.State.DESTROYED) {
            overlayLifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private fun setupDrag(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && AgentService.status.value == AgentStatus.Running) {
                        service.startService(
                            Intent(service, AgentService::class.java).apply {
                                action = AgentService.ACTION_STOP
                            }
                        )
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }
}
