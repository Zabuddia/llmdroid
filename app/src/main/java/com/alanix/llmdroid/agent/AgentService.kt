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
import android.accessibilityservice.AccessibilityService
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
    private var ttsEnabled = false
    private var unlockMode = false
    private var unlockPin = ""

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
                if (status.value == AgentStatus.Running) stopAgent()
                startAgent(goal)
            }
            ACTION_STOP -> stopAgent()
        }
        return START_NOT_STICKY
    }

    private fun startAgent(goal: String) {
        // Reset UI state and start the foreground notification synchronously —
        // Android requires startForeground() before the first suspension point.
        logs.value = emptyList()
        transcript.value = emptyList()
        status.value = AgentStatus.Running
        currentMessage.value = "Starting…"
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))

        agentJob = lifecycleScope.launch {
            val normalized = goal.replace(Regex("[,;\\n\\r]+"), " ").replace(Regex("\\s+"), " ").trim()
            var current = normalized
            var agentGoal = normalized
            log(LogType.System, "Goal: \"$current\"")

            // --- Skill dispatch ---

            // "unlock [task]" → store PIN, strip prefix, fall through to inner task's skills
            val unlockMatch = Regex("^unlock\\s+(.+)$", RegexOption.IGNORE_CASE).find(current)
            if (unlockMatch != null && settings.skillUnlockEnabled.first()) {
                val innerTask = unlockMatch.groupValues[1].trim()
                    .replace(Regex("^(and|then)\\s+", RegexOption.IGNORE_CASE), "")
                    .trim()
                unlockMode = true
                unlockPin = settings.unlockPin.first()
                current = innerTask
                agentGoal = innerTask
                log(LogType.System, "Skill: unlock — inner task=\"$innerTask\"")
            }

            // "text [name] [message]" → open SMS app pre-filled, agent taps send
            val textMatch = Regex("^text\\s+(.+)$", RegexOption.IGNORE_CASE).find(current)
            if (textMatch != null && settings.skillTextEnabled.first()) {
                log(LogType.System, "Skill: text — query=\"${textMatch.groupValues[1].trim()}\"")
                val contactFound = handleTextCommand(textMatch.groupValues[1].trim())
                if (!contactFound) {
                    log(LogType.System, "Skill: text — no contact found, stopping")
                    if (voiceMode) AndroidTts.speak("No matching contact found")
                    finishWithoutAgent()
                    return@launch
                }
                agentGoal = "The message is already composed and ready. Tap the send button to send it."
                log(LogType.System, "Skill: text — contact found, handing to agent")
            }

            // "message [...]" → open messaging app, agent handles it
            val messageMatch = Regex("^message\\s+(.+)$", RegexOption.IGNORE_CASE).find(current)
            if (messageMatch != null && settings.skillMessageEnabled.first()) {
                val messagingPkg = settings.messagingApp.first()
                if (messagingPkg.isNotBlank()) {
                    packageManager.getLaunchIntentForPackage(messagingPkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { startActivity(it) }
                    log(LogType.System, "Skill: message — launched $messagingPkg")
                } else {
                    log(LogType.System, "Skill: message — no default app set, agent will handle")
                }
            }

            // "call [name]" → call directly, no agent
            val callMatch = Regex("^call\\s+(.+)$", RegexOption.IGNORE_CASE).find(current)
            if (callMatch != null && settings.skillCallEnabled.first()) {
                val name = callMatch.groupValues[1].trim()
                log(LogType.System, "Skill: call — looking up \"$name\"")
                updateNotification("Calling…")
                handleCallCommand(name)
                finishWithoutAgent()
                return@launch
            }

            // "open [app]" → launch directly if found, no agent
            val openMatch = Regex("^open\\s+(.+)$", RegexOption.IGNORE_CASE).find(current)
            if (openMatch != null && settings.skillOpenEnabled.first()) {
                val query = openMatch.groupValues[1].trim()
                log(LogType.System, "Skill: open — looking up \"$query\"")
                val result = AppLookup.findBest(packageManager, query)
                if (result != null) {
                    log(LogType.System, "Skill: open — matched \"${result.label}\" (${result.packageName})")
                    updateNotification("Opening ${result.label}…")
                    handleOpenCommand(result)
                    finishWithoutAgent()
                    return@launch
                } else {
                    log(LogType.System, "Skill: open — no match for \"$query\", falling through to agent")
                }
            }

            // "play [...]" → open music app, agent handles playback
            val playMatch = Regex("^play\\s+.+$", RegexOption.IGNORE_CASE).find(current)
            if (playMatch != null && settings.skillPlayEnabled.first()) {
                val musicPkg = settings.musicApp.first()
                if (musicPkg.isNotBlank()) {
                    packageManager.getLaunchIntentForPackage(musicPkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { startActivity(it) }
                    log(LogType.System, "Skill: play — launched $musicPkg")
                } else {
                    log(LogType.System, "Skill: play — no default app set, agent will handle")
                }
            }

            // "search [...]" → open search app, agent handles the search
            val searchMatch = Regex("^search\\s+.+$", RegexOption.IGNORE_CASE).find(current)
            if (searchMatch != null && settings.skillSearchEnabled.first()) {
                val searchPkg = settings.searchApp.first()
                if (searchPkg.isNotBlank()) {
                    packageManager.getLaunchIntentForPackage(searchPkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { startActivity(it) }
                    log(LogType.System, "Skill: search — launched $searchPkg")
                } else {
                    log(LogType.System, "Skill: search — no default app set, agent will handle")
                }
            }

            // --- Agent loop setup ---

            val a11y = LLMAccessibilityService.instance
            if (a11y == null) {
                log(LogType.Error, "Accessibility service is not running")
                if (voiceMode) AndroidTts.speak("Accessibility service is not enabled")
                status.value = AgentStatus.Error
                currentMessage.value = "Accessibility service not enabled"
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }

            overlay = AgentOverlay(this@AgentService).also { it.show() }
            ttsEnabled = settings.ttsEnabled.first()
            AndroidTts.setRate(settings.ttsRate.first())
            AndroidTts.setPitch(settings.ttsPitch.first())

            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (km.isKeyguardLocked && !unlockMode) {
                log(LogType.Error, "Phone is locked. Say \"unlock ${agentGoal}\" to unlock it first.")
                if (voiceMode) AndroidTts.speak("Phone is locked")
                status.value = AgentStatus.Error
                currentMessage.value = "Phone is locked"
                stopForeground(STOP_FOREGROUND_REMOVE)
                overlay?.destroy(); overlay = null
                return@launch
            }

            if (unlockMode && unlockPin.isNotBlank()) {
                enterPinDirectly(unlockPin)
            }

            if (km.isKeyguardLocked) {
                log(LogType.Error, "Unlock: phone is still locked after PIN entry — check your PIN in Skills settings.")
                if (voiceMode) AndroidTts.speak("Unlock failed, check your PIN in settings")
                status.value = AgentStatus.Error
                currentMessage.value = "Unlock failed — wrong PIN?"
                stopForeground(STOP_FOREGROUND_REMOVE)
                overlay?.destroy(); overlay = null
                return@launch
            }

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
                            if (unlockMode) {
                                LLMAccessibilityService.instance?.performGlobalAction(
                                    AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                                )
                                unlockMode = false
                                unlockPin = ""
                            }
                        }
                    }
                    is AgentUpdate.Message -> {
                        currentMessage.value = update.text
                        updateNotification(update.text)
                        if ((voiceMode || ttsEnabled) && update.text.isNotBlank()) {
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
            loop.run(agentGoal)
        }
    }

    private fun finishWithoutAgent() {
        status.value = AgentStatus.Stopped
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun stopAgent() {
        AndroidTts.stop()
        agentLoop?.stop()
        agentJob?.cancel()
        agentJob = null
        agentLoop = null
        unlockMode = false
        unlockPin = ""
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

    private suspend fun enterPinDirectly(pin: String) {
        val a11y = LLMAccessibilityService.instance ?: return

        @Suppress("DEPRECATION")
        val wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "llmdroid:unlock"
            )
        wake.acquire(15_000L)

        try {
            kotlinx.coroutines.delay(300)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                kotlinx.coroutines.delay(200)
            }

            val m = resources.displayMetrics
            val cx = m.widthPixels / 2
            val cy = m.heightPixels / 2
            val swipeFrom = (m.heightPixels * 0.80).toInt()
            val swipeTo   = (m.heightPixels * 0.25).toInt()
            a11y.swipeGesture(cx, cy, cx, cy, 50)
            kotlinx.coroutines.delay(400)
            a11y.swipeGesture(cx, swipeFrom, cx, swipeTo, 300)
            kotlinx.coroutines.delay(800)

            val setTextOk = a11y.trySetTextOnEditableNode(pin)
            log(LogType.System, "Unlock: set-text → ${if (setTextOk) "ok" else "no editable field, trying digit buttons"}")

            if (!setTextOk) {
                for (digit in pin) {
                    var tapped = a11y.tapNodeWithText(digit.toString())
                    if (!tapped) {
                        a11y.swipeGesture(cx, swipeFrom, cx, swipeTo, 300)
                        kotlinx.coroutines.delay(500)
                        tapped = a11y.tapNodeWithText(digit.toString())
                    }
                    log(LogType.System, "Unlock: digit '$digit' → ${if (tapped) "ok" else "missed"}")
                    kotlinx.coroutines.delay(200)
                }
            }

            kotlinx.coroutines.delay(200)
            val confirmed = a11y.tapConfirmButton()
            log(LogType.System, "Unlock: confirm → ${if (confirmed) "tapped" else "not found, trying keyevent"}")
            if (!confirmed) {
                try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "66")) } catch (_: Exception) {}
            }

            kotlinx.coroutines.delay(1000)
            log(LogType.System, "Unlock: PIN entry done")
        } finally {
            wake.release()
        }
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
        val intent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return
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
