package com.alanix.llmdroid.io.wake

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object WakeWordDetector {

    private const val TAG = "WakeWordDetector"

    const val MEL_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.tflite"
    const val EMB_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.tflite"
    const val WAKE_URL = "https://github.com/Stypox/dicio-android/releases/download/v2.0/hey_dicio_v6.0.tflite"

    sealed class State {
        object NotDownloaded : State()
        data class Downloading(val progress: Float, val label: String) : State()
        object NotLoaded : State()
        object Loading : State()
        object Ready : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state

    private var model: OwwModel? = null
    val frameSize get() = OwwModel.MEL_INPUT_COUNT

    private fun owwDir(context: Context) = File(context.filesDir, "openWakeWord")
    private fun melFile(context: Context) = File(owwDir(context), "melspectrogram.tflite")
    private fun embFile(context: Context) = File(owwDir(context), "embedding.tflite")
    private fun wakeFile(context: Context) = File(owwDir(context), "wake.tflite")

    fun needsDownload(context: Context) =
        !melFile(context).exists() || !embFile(context).exists() || !wakeFile(context).exists()

    suspend fun ensureReady(context: Context, onStatus: (String) -> Unit) {
        if (_state.value == State.Ready) return

        owwDir(context).mkdirs()

        if (needsDownload(context)) {
            _state.value = State.Downloading(0f, "OWW models")
            try {
                downloadIfMissing(melFile(context), MEL_URL, "mel spectrogram", onStatus)
                downloadIfMissing(embFile(context), EMB_URL, "embedding model", onStatus)
                downloadIfMissing(wakeFile(context), WAKE_URL, "hey dicio model", onStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _state.value = State.Error("Download failed: ${e.message}")
                return
            }
            _state.value = State.NotLoaded
        } else {
            _state.value = State.NotLoaded
        }

        onStatus("Loading wake word model…")
        _state.value = State.Loading
        try {
            model = OwwModel(melFile(context), embFile(context), wakeFile(context))
            _state.value = State.Ready
            Log.i(TAG, "Wake word model loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Load failed", e)
            _state.value = State.Error("Load failed: ${e.message}")
        }
    }

    fun processFrame(audio: ShortArray): Boolean {
        val m = model ?: return false
        val floatAudio = FloatArray(audio.size) { audio[it].toFloat() / 32768.0f }
        return m.processFrame(floatAudio) > 0.8f
    }

    fun destroy() {
        model?.close()
        model = null
        _state.value = State.NotLoaded
    }

    private suspend fun downloadIfMissing(
        dest: File,
        url: String,
        label: String,
        onStatus: (String) -> Unit,
    ) {
        if (dest.exists()) return
        onStatus("Downloading $label…")
        Log.d(TAG, "Downloading $label from $url")
        withContext(Dispatchers.IO) {
            val tmp = File(dest.parent, dest.name + ".part")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connect()
                val total = conn.contentLength.toLong()
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(16384)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                _state.value = State.Downloading(
                                    downloaded.toFloat() / total, label
                                )
                            }
                        }
                    }
                }
                tmp.renameTo(dest)
                Log.d(TAG, "Downloaded $label")
            } finally {
                conn.disconnect()
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}
