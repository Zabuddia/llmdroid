package com.alanix.llmdroid.io.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

object WhisperStt {

    private const val TAG = "WhisperStt"
    private const val SAMPLE_RATE = 16000

    @Volatile private var stopFlag = false
    private var manualJob: Job? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun isReady(whisperUrl: String) = whisperUrl.isNotBlank()

    /**
     * Records until silence or [maxDurationMs], then transcribes via the Whisper API.
     * [onPartial] is called with a status string ("Recording…", "Transcribing…") since
     * Whisper is batch — no streaming partials.
     */
    suspend fun transcribeOnce(
        whisperUrl: String,
        whisperModel: String,
        apiKey: String,
        maxDurationMs: Int = 8000,
        silenceMs: Int = 2500,
        onPartial: (String) -> Unit = {},
    ): String? {
        stopFlag = false
        onPartial("Recording…")
        val pcm = record(maxDurationMs, silenceMs) ?: return null
        onPartial("Transcribing…")
        return apiTranscribe(pcm, whisperUrl, whisperModel, apiKey)
    }

    fun startManualListening(
        scope: CoroutineScope,
        whisperUrl: String,
        whisperModel: String,
        apiKey: String,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onEnd: () -> Unit,
    ) {
        stopManualListening()
        stopFlag = false
        manualJob = scope.launch(Dispatchers.IO) {
            onPartial("Recording…")
            val pcm = record(maxDurationMs = 30_000, silenceMs = 2500)
            if (pcm == null) { onEnd(); return@launch }
            onPartial("Transcribing…")
            val text = apiTranscribe(pcm, whisperUrl, whisperModel, apiKey)
            if (text != null) onResult(text)
            onEnd()
        }
    }

    fun stopManualListening() {
        stopFlag = true
        manualJob?.cancel()
        manualJob = null
    }

    @SuppressLint("MissingPermission")
    private fun record(maxDurationMs: Int, silenceMs: Int): ShortArray? {
        val chunkSamples = SAMPLE_RATE / 10  // 100 ms chunks
        val maxChunks = maxDurationMs / 100
        val silenceChunksNeeded = silenceMs / 100
        val rmsThreshold = 300

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, chunkSamples * 4),
        )

        val allSamples = ArrayList<Short>(maxDurationMs * SAMPLE_RATE / 1000)
        val buf = ShortArray(chunkSamples)
        var speechDetected = false
        var silenceChunks = 0
        var totalChunks = 0

        recorder.startRecording()
        try {
            while (totalChunks < maxChunks && !stopFlag) {
                val read = recorder.read(buf, 0, chunkSamples)
                if (read <= 0) break
                totalChunks++

                var sumSq = 0.0
                for (i in 0 until read) sumSq += buf[i].toLong() * buf[i]
                val rms = sqrt(sumSq / read).toInt()

                if (rms >= rmsThreshold) {
                    speechDetected = true
                    silenceChunks = 0
                } else if (speechDetected) {
                    silenceChunks++
                    if (silenceChunks >= silenceChunksNeeded) break
                }

                for (i in 0 until read) allSamples.add(buf[i])
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }

        if (!speechDetected || allSamples.isEmpty()) return null
        return allSamples.toShortArray()
    }

    private suspend fun apiTranscribe(
        pcm: ShortArray,
        whisperUrl: String,
        whisperModel: String,
        apiKey: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val wav = pcmToWav(pcm)
            val url = whisperUrl.trimEnd('/') + "/v1/audio/transcriptions"

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "audio.wav",
                    wav.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", whisperModel)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .apply { if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey") }
                .build()

            val response = http.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                Log.e(TAG, "Whisper API error ${response.code}: $responseBody")
                return@withContext null
            }

            Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
                .find(responseBody)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            null
        }
    }

    private fun pcmToWav(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)                        // PCM
        buf.putShort(1)                        // mono
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)            // byte rate
        buf.putShort(2)                        // block align
        buf.putShort(16)                       // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }
}
