package com.alanix.llmdroid.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import com.alanix.llmdroid.LLMDroidApp
import com.alanix.llmdroid.MainActivity
import com.alanix.llmdroid.accessibility.LLMAccessibilityService
import com.alanix.llmdroid.data.SettingsStore
import com.alanix.llmdroid.io.stt.VoskManager
import com.alanix.llmdroid.io.stt.WhisperStt
import com.alanix.llmdroid.io.tts.AndroidTts
import com.alanix.llmdroid.io.wake.WakeWordDetector
import com.alanix.llmdroid.model.AgentStatus
import com.alanix.llmdroid.model.LogEntry
import com.alanix.llmdroid.model.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val FOREGROUND_CHANNEL_ID = "llmdroid_wake_foreground"
        private const val LISTENING_CHANNEL_ID = "llmdroid_wake_listening"
        private const val FOREGROUND_NOTIFICATION_ID = 19803672
        private const val LISTENING_NOTIFICATION_ID = 19803673
        private const val WAKE_BACKOFF_MS = 4000L

        const val ACTION_START = "com.alanix.llmdroid.WAKE_START"
        const val ACTION_STOP = "com.alanix.llmdroid.WAKE_STOP"

        val isRunning = MutableStateFlow(false)
        val listeningText = MutableStateFlow<String?>(null)
    }

    private val settings get() = (application as LLMDroidApp).settingsStore

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var listenJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopListening(); stopSelf(); return START_NOT_STICKY }
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.value = false
        listeningText.value = null
        notificationManager.cancel(LISTENING_NOTIFICATION_ID)
        serviceJob.cancel()
        WakeWordDetector.destroy()
        AndroidTts.destroy()
        super.onDestroy()
    }

    private fun startListening() {
        if (listenJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permission not granted")
            stopSelf()
            return
        }

        isRunning.value = true
        createChannels()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification("Initializing…"))

        listenJob = serviceScope.launch {
            try {
                initModels()
                updateForegroundNotification("Listening for 'Hey Dicio'")
                detectionLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in detection loop", e)
                updateForegroundNotification("Error — tap to restart")
            } finally {
                isRunning.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        isRunning.value = false
        listeningText.value = null
        notificationManager.cancel(LISTENING_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun initModels() {
        WakeWordDetector.ensureReady(this) { msg -> updateForegroundNotification(msg) }
        if (WakeWordDetector.state.value is WakeWordDetector.State.Error) {
            throw RuntimeException("Wake word model failed: ${WakeWordDetector.state.value}")
        }

        val engine = settings.sttEngine.first()
        if (engine == SettingsStore.STT_ENGINE_VOSK) {
            VoskManager.ensureReady(this) { msg -> updateForegroundNotification(msg) }
            if (VoskManager.state.value is VoskManager.State.Error) {
                throw RuntimeException("Vosk model failed: ${VoskManager.state.value}")
            }
        }

        withContext(Dispatchers.Main) { AndroidTts.init(this@WakeWordService) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun detectionLoop() {
        val frameSamples = WakeWordDetector.frameSize
        val buf = ShortArray(frameSamples)
        var nextWakeAllowedMs = 0L

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000, // OWW model requires 16 kHz
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            6400,
        )

        recorder.startRecording()
        try {
            while (coroutineContext.isActive) {
                val read = recorder.read(buf, 0, frameSamples)
                if (read < frameSamples) {
                    delay(50)
                    continue
                }

                val detected = WakeWordDetector.processFrame(buf)
                val now = System.currentTimeMillis()
                if (detected && now > nextWakeAllowedMs) {
                    nextWakeAllowedMs = now + WAKE_BACKOFF_MS
                    Log.i(TAG, "Wake word detected!")
                    recorder.stop()
                    onWakeWordDetected()
                    // Reset backoff from NOW so OWW buffer residue can't immediately re-trigger
                    nextWakeAllowedMs = System.currentTimeMillis() + WAKE_BACKOFF_MS
                    recorder.startRecording()
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
    }

    private suspend fun onWakeWordDetected() {
        if (AgentService.status.value == AgentStatus.Running) {
            Log.d(TAG, "Agent already running, ignoring wake word")
            return
        }

        val ttsSent = AndroidTts.speak("I'm listening")
        if (ttsSent) delay(800) // let TTS finish before recording

        showListeningNotification("Listening…")
        listeningText.value = "Listening…"

        val engine = settings.sttEngine.first()
        val command = if (engine == SettingsStore.STT_ENGINE_WHISPER) {
            val url = settings.whisperUrl.first()
            val whisperModel = settings.whisperModel.first()
            val apiKey = settings.apiKey.first()
            WhisperStt.transcribeOnce(
                whisperUrl = url,
                whisperModel = whisperModel,
                apiKey = apiKey,
                maxDurationMs = 8000,
                onPartial = { partial ->
                    listeningText.value = partial
                    updateListeningNotification(partial)
                },
            )?.trim()
        } else {
            VoskManager.transcribeOnce(
                maxDurationMs = 8000,
                onPartial = { partial ->
                    listeningText.value = partial
                    updateListeningNotification(partial)
                },
            )?.trim()
        }

        listeningText.value = null
        notificationManager.cancel(LISTENING_NOTIFICATION_ID)
        updateForegroundNotification("Listening for 'Hey Dicio'")

        if (command.isNullOrBlank()) {
            Log.d(TAG, "No command heard")
            return
        }
        Log.i(TAG, "Command: $command")
        AgentService.log(LogType.System, "Heard: \"$command\"")

        // "text [name] [message]" — SMS shortcut, agent taps send
        val textMatch = Regex("^text\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)
        if (textMatch != null && settings.skillTextEnabled.first()) {
            if (!LLMAccessibilityService.isRunning.value) {
                AndroidTts.speak("Accessibility service is not enabled")
                return
            }
            AgentService.log(LogType.System, "Skill: text — query=\"${textMatch.groupValues[1].trim()}\"")
            val contactFound = handleTextCommand(textMatch.groupValues[1].trim())
            if (!contactFound) {
                AgentService.log(LogType.System, "Skill: text — no contact found, stopping")
                AndroidTts.speak("No matching contact found")
                return
            }
            AgentService.log(LogType.System, "Skill: text — contact found, handing to agent")
            delay(1000)
            startForegroundService(
                Intent(this, AgentService::class.java).apply {
                    action = AgentService.ACTION_START
                    putExtra(AgentService.EXTRA_GOAL, "The message is already composed and ready. Tap the send button to send it.")
                    putExtra(AgentService.EXTRA_VOICE_MODE, true)
                }
            )
            return
        }

        // "message [...]" — open messaging app, agent handles it
        val messageMatch = Regex("^message\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)
        if (messageMatch != null && settings.skillMessageEnabled.first()) {
            if (!LLMAccessibilityService.isRunning.value) {
                AndroidTts.speak("Accessibility service is not enabled")
                return
            }
            AgentService.log(LogType.System, "Skill: message — opening messaging app")
            val messagingPkg = settings.messagingApp.first()
            if (messagingPkg.isNotBlank()) {
                packageManager.getLaunchIntentForPackage(messagingPkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                AgentService.log(LogType.System, "Skill: message — launched $messagingPkg")
                delay(1000)
            } else {
                AgentService.log(LogType.System, "Skill: message — no default messaging app set, agent will handle")
            }
            startForegroundService(
                Intent(this, AgentService::class.java).apply {
                    action = AgentService.ACTION_START
                    putExtra(AgentService.EXTRA_GOAL, command)
                    putExtra(AgentService.EXTRA_VOICE_MODE, true)
                }
            )
            return
        }

        // "call [name]" — direct call, no LLM
        val callMatch = Regex("^call\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)
        if (callMatch != null && settings.skillCallEnabled.first()) {
            val name = callMatch.groupValues[1].trim()
            AgentService.log(LogType.System, "Skill: call — looking up \"$name\"")
            handleCallCommand(name)
            return
        }

        // "open [app]" — instant launch if found, else fall through to agent
        val openMatch = Regex("^open\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)
        if (openMatch != null && settings.skillOpenEnabled.first()) {
            val query = openMatch.groupValues[1].trim()
            AgentService.log(LogType.System, "Skill: open — looking up \"$query\"")
            val result = AppLookup.findBest(packageManager, query)
            if (result != null) {
                AgentService.log(LogType.System, "Skill: open — matched \"${result.label}\" (${result.packageName})")
                handleOpenCommand(result)
                return
            } else {
                AgentService.log(LogType.System, "Skill: open — no match for \"$query\", falling through to agent")
            }
        }

        // "play [...]" — open music app, agent handles playback
        val playMatch = Regex("^play\\s+.+$", RegexOption.IGNORE_CASE).find(command)
        if (playMatch != null && settings.skillPlayEnabled.first()) {
            if (!LLMAccessibilityService.isRunning.value) {
                AndroidTts.speak("Accessibility service is not enabled")
                return
            }
            AgentService.log(LogType.System, "Skill: play — opening music app")
            val musicPkg = settings.musicApp.first()
            if (musicPkg.isNotBlank()) {
                packageManager.getLaunchIntentForPackage(musicPkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                AgentService.log(LogType.System, "Skill: play — launched $musicPkg")
                delay(1000)
            } else {
                AgentService.log(LogType.System, "Skill: play — no default music app set, agent will handle")
            }
            startForegroundService(
                Intent(this, AgentService::class.java).apply {
                    action = AgentService.ACTION_START
                    putExtra(AgentService.EXTRA_GOAL, command)
                    putExtra(AgentService.EXTRA_VOICE_MODE, true)
                }
            )
            return
        }

        if (!LLMAccessibilityService.isRunning.value) {
            AndroidTts.speak("Accessibility service is not enabled")
            return
        }

        startForegroundService(
            Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_START
                putExtra(AgentService.EXTRA_GOAL, command)
                putExtra(AgentService.EXTRA_VOICE_MODE, true)
            }
        )
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
                    AgentService.log(LogType.System, "Skill: text — i=$i \"$namePart\" → \"${result.name}\" dist=$dist")
                    contactNumber = result.number
                    messageStart = i
                } else {
                    AgentService.log(LogType.System, "Skill: text — i=$i \"$namePart\" (norm=\"$queryNorm\") no match")
                }
            }
        }

        val message = words.drop(messageStart).joinToString(" ")
        AgentService.log(LogType.System, "Skill: text — number=${contactNumber ?: "none"}, message=\"$message\"")
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
        Log.i(TAG, "Direct call: $name")
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

    private fun createChannels() {
        // Low-importance persistent foreground notification
        NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "Wake Word Listener",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Always-on 'Hey Dicio' wake word detection"
            setShowBadge(false)
        }.also { notificationManager.createNotificationChannel(it) }

        // High-importance channel so the listening alert pops up as a heads-up notification
        NotificationChannel(
            LISTENING_CHANNEL_ID,
            "Voice Listening Alert",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shown when 'Hey Dicio' is detected and the app is listening"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }.also { notificationManager.createNotificationChannel(it) }
    }

    private fun tapIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildForegroundNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LLMDroid")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tapIntent())
            .addAction(NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Stop", stopIntent
            ))
            .build()
    }

    private fun buildListeningNotification(text: String): Notification =
        NotificationCompat.Builder(this, LISTENING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening…")
            .setContentText(text.ifBlank { "Say your command" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(tapIntent())
            .build()

    private fun showListeningNotification(text: String) =
        notificationManager.notify(LISTENING_NOTIFICATION_ID, buildListeningNotification(text))

    private fun updateListeningNotification(text: String) =
        notificationManager.notify(LISTENING_NOTIFICATION_ID, buildListeningNotification(text))

    private fun updateForegroundNotification(text: String) =
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(text))
}
