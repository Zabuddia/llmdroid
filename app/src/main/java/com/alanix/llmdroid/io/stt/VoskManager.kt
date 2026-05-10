package com.alanix.llmdroid.io.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume

object VoskManager {

    private const val TAG = "VoskManager"
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val SAMPLE_RATE = 44100f

    sealed class State {
        object NotDownloaded : State()
        data class Downloading(val progress: Float) : State()
        object Extracting : State()
        object NotLoaded : State()
        object Loading : State()
        object Ready : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state

    private var model: Model? = null

    // Kept alive only during manual (in-app mic button) listening
    private var manualService: SpeechService? = null

    private fun zipFile(context: Context) = File(context.filesDir, "vosk-model.zip")
    private fun modelDir(context: Context) = File(context.filesDir, "vosk-model")
    private fun modelReadyFile(context: Context) = File(modelDir(context), "ivector")

    fun needsDownload(context: Context) = !modelReadyFile(context).exists()

    fun isReady() = model != null

    suspend fun ensureReady(context: Context, onStatus: (String) -> Unit) {
        if (_state.value == State.Ready) return

        if (needsDownload(context)) {
            onStatus("Downloading Vosk model (~40 MB)…")
            _state.value = State.Downloading(0f)
            try {
                downloadZip(context)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _state.value = State.Error("Download failed: ${e.message}")
                return
            }

            onStatus("Extracting Vosk model…")
            _state.value = State.Extracting
            try {
                extractZip(context)
            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed", e)
                _state.value = State.Error("Extraction failed: ${e.message}")
                return
            }
        }

        onStatus("Loading Vosk model…")
        _state.value = State.Loading
        try {
            withContext(Dispatchers.IO) {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                model = Model(modelDir(context).absolutePath)
            }
            _state.value = State.Ready
            Log.i(TAG, "Vosk model loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Load failed", e)
            _state.value = State.Error("Load failed: ${e.message}")
        }
    }

    /**
     * Creates a fresh Recognizer + SpeechService for each call so rapid reuse doesn't corrupt
     * internal AudioRecord state. The Model is reused (expensive); Recognizer/SpeechService
     * creation is cheap.
     */
    suspend fun transcribeOnce(
        maxDurationMs: Int = 8000,
        onPartial: (String) -> Unit = {},
    ): String? {
        val m = model ?: return null
        val (recognizer, service) = withContext(Dispatchers.IO) {
            val r = Recognizer(m, SAMPLE_RATE)
            Pair(r, SpeechService(r, SAMPLE_RATE))
        }
        return try {
            withTimeoutOrNull(maxDurationMs.toLong()) {
                suspendCancellableCoroutine { cont ->
                    var finished = false

                    fun finish(text: String?) {
                        if (finished) return
                        finished = true
                        service.stop()
                        if (cont.isActive) cont.resume(text)
                    }

                    service.startListening(object : RecognitionListener {
                        override fun onPartialResult(s: String) {
                            val text = extractJsonString(s, "partial")
                            if (text.isNotBlank()) onPartial(text)
                        }

                        override fun onResult(s: String) =
                            finish(extractJsonString(s, "text").takeIf { it.isNotBlank() })

                        override fun onFinalResult(s: String) =
                            finish(extractJsonString(s, "text").takeIf { it.isNotBlank() })

                        override fun onError(e: Exception) {
                            Log.e(TAG, "SpeechService error", e)
                            finish(null)
                        }

                        override fun onTimeout() = finish(null)
                    })

                    cont.invokeOnCancellation { service.stop() }
                }
            }
        } finally {
            service.shutdown()
        }
    }

    fun startManualListening(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onEnd: () -> Unit,
    ) {
        val m = model ?: run { onEnd(); return }
        stopManualListening() // clean up any prior session

        val recognizer = Recognizer(m, SAMPLE_RATE)
        val service = SpeechService(recognizer, SAMPLE_RATE)
        manualService = service

        var finished = false

        fun finish(text: String?) {
            if (finished) return
            finished = true
            service.stop()
            manualService = null
            if (text != null) onResult(text)
            onEnd()
        }

        service.startListening(object : RecognitionListener {
            override fun onPartialResult(s: String) {
                val text = extractJsonString(s, "partial")
                if (text.isNotBlank()) onPartial(text)
            }

            override fun onResult(s: String) =
                finish(extractJsonString(s, "text").takeIf { it.isNotBlank() })

            override fun onFinalResult(s: String) =
                finish(extractJsonString(s, "text").takeIf { it.isNotBlank() })

            override fun onError(e: Exception) {
                Log.e(TAG, "SpeechService error in manual mode", e)
                finish(null)
            }

            override fun onTimeout() = finish(null)
        })
    }

    fun stopManualListening() {
        manualService?.stop()
        manualService?.shutdown()
        manualService = null
    }

    fun destroy() {
        stopManualListening()
        model = null
        _state.value = State.NotLoaded
    }

    private fun extractJsonString(json: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.getOrNull(1) ?: ""

    private suspend fun downloadZip(context: Context) = withContext(Dispatchers.IO) {
        val dest = zipFile(context)
        val tmp = File(context.cacheDir, "vosk-model.zip.part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            conn.connect()
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(65536)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) _state.value = State.Downloading(downloaded.toFloat() / total)
                    }
                }
            }
            tmp.renameTo(dest)
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    private suspend fun extractZip(context: Context) = withContext(Dispatchers.IO) {
        val zip = zipFile(context)
        val dest = modelDir(context)
        dest.deleteRecursively()
        dest.mkdirs()

        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val parts = entry.name.split("/")
                val rel = parts.drop(1).joinToString("/")
                if (rel.isNotEmpty()) {
                    val target = File(dest, rel)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zip.delete()
        Log.i(TAG, "Vosk model extracted to $dest")
    }
}
