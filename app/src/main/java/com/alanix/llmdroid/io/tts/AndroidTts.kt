package com.alanix.llmdroid.io.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object AndroidTts {

    private const val TAG = "AndroidTts"

    private var tts: TextToSpeech? = null
    private var ready = false
    private val utteranceCounter = AtomicInteger(0)

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault()) ?: -1
                if (result < 0) {
                    tts?.setLanguage(Locale.ENGLISH)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {}
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(id: String?) {}
                })
                ready = true
                Log.i(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speak(text: String): Boolean {
        if (!ready) { Log.w(TAG, "TTS not ready, dropping: $text"); return false }
        val id = "llmdroid_${utteranceCounter.incrementAndGet()}"
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        return true
    }

    fun stop() {
        tts?.stop()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
