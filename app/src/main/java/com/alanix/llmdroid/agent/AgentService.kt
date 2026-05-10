package com.alanix.llmdroid.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alanix.llmdroid.LLMDroidApp
import com.alanix.llmdroid.MainActivity
import com.alanix.llmdroid.accessibility.LLMAccessibilityService
import com.alanix.llmdroid.model.AgentStatus
import com.alanix.llmdroid.model.LogEntry
import com.alanix.llmdroid.model.LogType
import com.alanix.llmdroid.model.TranscriptEntry
import com.alanix.llmdroid.data.SettingsStore
import com.alanix.llmdroid.network.OpenAiClient
import com.alanix.llmdroid.io.tts.AndroidTts
import com.alanix.llmdroid.overlay.AgentOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

        fun log(type: LogType, content: String) {
            logs.value = (logs.value + LogEntry(type = type, content = content)).takeLast(MAX_LOG_ENTRIES)
        }
    }

    private lateinit var settings: SettingsStore
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
        settings = (application as LLMDroidApp).settingsStore
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
        var agentGoal = goal
        var handled = false  // true = shortcut fully handled, don't start agent loop

        runBlocking {
            val trimmed = goal.trim()
            log(LogType.System, "Goal: \"$trimmed\"")

            // "text [name] [message]" → open SMS app pre-filled, agent taps send
            val textMatch = Regex("^text\\s+(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
            if (textMatch != null && settings.skillTextEnabled.first()) {
                log(LogType.System, "Skill: text — query=\"${textMatch.groupValues[1].trim()}\"")
                val contactFound = handleTextCommand(textMatch.groupValues[1].trim())
                if (!contactFound) {
                    log(LogType.System, "Skill: text — no contact found, stopping")
                    handled = true
                    return@runBlocking
                }
                agentGoal = "The message is already composed and ready. Tap the send button to send it."
                log(LogType.System, "Skill: text — contact found, handing to agent")
                return@runBlocking
            }

            // "message [name] [message]" → open messaging app, agent handles it
            val messageMatch = Regex("^message\\s+(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
            if (messageMatch != null && settings.skillMessageEnabled.first()) {
                log(LogType.System, "Skill: message — opening messaging app")
                val messagingPkg = settings.messagingApp.first()
                if (messagingPkg.isNotBlank()) {
                    packageManager.getLaunchIntentForPackage(messagingPkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { startActivity(it) }
                    log(LogType.System, "Skill: message — launched $messagingPkg")
                } else {
                    log(LogType.System, "Skill: message — no default messaging app set, agent will handle")
                }
                return@runBlocking
            }

            // "call [name]" → call directly, no agent
            val callMatch = Regex("^call\\s+(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
            if (callMatch != null && settings.skillCallEnabled.first()) {
                val name = callMatch.groupValues[1].trim()
                log(LogType.System, "Skill: call — looking up \"$name\"")
                startForeground(NOTIFICATION_ID, buildNotification("Calling…"))
                handleCallCommand(name)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                handled = true
                return@runBlocking
            }

            // "open [app]" → launch directly if app found, no agent
            val openMatch = Regex("^open\\s+(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
            if (openMatch != null && settings.skillOpenEnabled.first()) {
                val query = openMatch.groupValues[1].trim()
                log(LogType.System, "Skill: open — looking up \"$query\"")
                val result = AppLookup.findBest(packageManager, query)
                if (result != null) {
                    log(LogType.System, "Skill: open — matched \"${result.label}\" (${result.packageName})")
                    startForeground(NOTIFICATION_ID, buildNotification("Opening ${result.label}…"))
                    handleOpenCommand(result)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    handled = true
                    return@runBlocking
                } else {
                    log(LogType.System, "Skill: open — no match for \"$query\", falling through to agent")
                }
            }

            // "play [...]" → open music app, agent handles playback
            val playMatch = Regex("^play\\s+.+$", RegexOption.IGNORE_CASE).find(trimmed)
            if (playMatch != null && settings.skillPlayEnabled.first()) {
                log(LogType.System, "Skill: play — opening music app")
                val musicPkg = settings.musicApp.first()
                if (musicPkg.isNotBlank()) {
                    packageManager.getLaunchIntentForPackage(musicPkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { startActivity(it) }
                    log(LogType.System, "Skill: play — launched $musicPkg")
                } else {
                    log(LogType.System, "Skill: play — no default music app set, agent will handle")
                }
            }
        }

        if (handled) return

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
            loop.run(agentGoal)
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

    private fun handleTextCommand(query: String): Boolean {
        val words = query.trim().split("\\s+".toRegex())
        val hasContacts = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        var contactNumber: String? = null
        var messageStart = 0
        if (hasContacts) {
            for (i in 1..minOf(words.size, 4)) {
                val namePart = words.take(i).joinToString(" ")
                val result = ContactLookup.findBestForName(contentResolver, namePart)
                val queryNorm = ContactLookup.normalize(namePart)
                if (result != null) {
                    val prefixNorm = ContactLookup.normalize(
                        result.name.trim().split("\\s+".toRegex()).take(i).joinToString(" ")
                    )
                    val dist = ContactLookup.editDistance(queryNorm, prefixNorm)
                    log(LogType.System, "Skill: text — i=$i \"$namePart\" → \"${result.name}\" dist=$dist")
                    contactNumber = result.number
                    messageStart = i
                } else {
                    log(LogType.System, "Skill: text — i=$i \"$namePart\" (norm=\"$queryNorm\") no match")
                }
            }
        }

        val message = words.drop(messageStart).joinToString(" ")
        log(LogType.System, "Skill: text — number=${contactNumber ?: "none"}, message=\"$message\"")
        val uri = Uri.parse(if (contactNumber != null) "smsto:$contactNumber" else "smsto:")
        startActivity(
            Intent(Intent.ACTION_SENDTO, uri).apply {
                if (message.isNotBlank()) putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return contactNumber != null
    }

    private fun handleOpenCommand(app: AppLookup.AppResult) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun handleCallCommand(name: String) {
        val hasContacts = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val result = if (hasContacts) ContactLookup.findBest(contentResolver, name) else null
        val uri = if (result != null) "tel:${result.number}" else "tel:"
        val canCallDirectly = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(action, Uri.parse(uri)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
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
