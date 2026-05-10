package com.alanix.llmdroid.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alanix.llmdroid.LLMDroidApp
import com.alanix.llmdroid.MainActivity
import com.alanix.llmdroid.accessibility.LLMAccessibilityService
import com.alanix.llmdroid.model.AgentStatus
import com.alanix.llmdroid.model.LogEntry
import com.alanix.llmdroid.model.LogType
import com.alanix.llmdroid.model.TranscriptEntry
import com.alanix.llmdroid.network.OpenAiClient
import com.alanix.llmdroid.io.tts.AndroidTts
import com.alanix.llmdroid.overlay.AgentOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AgentService : LifecycleService() {

    companion object {
        private const val TAG = "AgentService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "llmdroid_agent"
        private const val MAX_LOG_ENTRIES = 500

        const val ACTION_START = "com.alanix.llmdroid.START"
        const val ACTION_STOP = "com.alanix.llmdroid.STOP"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_VOICE_MODE = "voice_mode"

        val status = MutableStateFlow(AgentStatus.Idle)
        val currentMessage = MutableStateFlow("")
        val logs = MutableStateFlow<List<LogEntry>>(emptyList())
        val transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    }

    private lateinit var client: OpenAiClient
    private var agentJob: Job? = null
    private var agentLoop: AgentLoop? = null
    private var overlay: AgentOverlay? = null
    private var voiceMode = false

    inner class LocalBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    override fun onCreate() {
        super.onCreate()
        val settings = (application as LLMDroidApp).settingsStore
        client = OpenAiClient(settings)
        createNotificationChannel()
        AndroidTts.init(this)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val goal = intent.getStringExtra(EXTRA_GOAL) ?: return START_NOT_STICKY
                voiceMode = intent.getBooleanExtra(EXTRA_VOICE_MODE, false)
                startAgent(goal)
            }
            ACTION_STOP -> stopAgent()
        }
        return START_NOT_STICKY
    }

    private fun startAgent(goal: String) {
        val a11y = LLMAccessibilityService.instance
        if (a11y == null) {
            addLog(LogEntry(type = LogType.Error, content = "Accessibility service is not running"))
            status.value = AgentStatus.Error
            currentMessage.value = "Accessibility service not enabled"
            return
        }

        logs.value = emptyList()
        transcript.value = emptyList()
        status.value = AgentStatus.Running
        currentMessage.value = "Starting…"

        startForeground(NOTIFICATION_ID, buildNotification("Running…"))

        overlay = AgentOverlay(this).also { it.show() }

        val settings = (application as LLMDroidApp).settingsStore
        val loop = AgentLoop(
            accessibilityService = a11y,
            client = client,
            settings = settings
        ) { update ->
            when (update) {
                is AgentUpdate.StatusChange -> {
                    status.value = update.status
                    if (update.status != AgentStatus.Running) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        overlay?.destroy()
                        overlay = null
                    }
                }
                is AgentUpdate.Message -> {
                    currentMessage.value = update.text
                    updateNotification(update.text)
                    if (voiceMode && update.text.isNotBlank()) {
                        AndroidTts.speak(update.text)
                    }
                }
                is AgentUpdate.Log -> addLog(update.entry)
                is AgentUpdate.TranscriptAdd -> {
                    transcript.value = transcript.value +
                        TranscriptEntry(isAssistant = update.isAssistant, content = update.content)
                }
            }
        }

        agentLoop = loop
        agentJob = lifecycleScope.launch {
            loop.run(goal)
        }
    }

    fun stopAgent() {
        agentLoop?.stop()
        agentJob?.cancel()
        agentJob = null
        agentLoop = null
        status.value = AgentStatus.Stopped
        currentMessage.value = "Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        overlay?.destroy()
        overlay = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAgent()
        client.close()
        Log.i(TAG, "AgentService destroyed")
    }

    private fun addLog(entry: LogEntry) {
        logs.value = (logs.value + entry).takeLast(MAX_LOG_ENTRIES)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LLMDroid Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "LLMDroid automation agent status" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LLMDroid")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        if (status.value != AgentStatus.Running) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
