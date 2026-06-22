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
    private val pendingQueue = mutableListOf<String>()
    private var rate = 1.0f
    private var pitch = 1.0f

    fun init(context: Context) {
        if (ready) return
        tts?.shutdown()
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val errorCode = tts?.setLanguage(Locale.getDefault()) ?: -1
                if (errorCode >= 0) {
                    tts?.setSpeechRate(rate)
                    tts?.setPitch(pitch)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(id: String?) {}
                    })
                    ready = true
                    Log.i(TAG, "TTS initialized")
                    synchronized(pendingQueue) {
                        pendingQueue.forEach { speakNow(it) }
                        pendingQueue.clear()
                    }
                } else {
                    Log.e(TAG, "TTS language not supported: $errorCode")
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speak(text: String): Boolean {
        if (!ready) {
            Log.i(TAG, "TTS not ready, queuing: $text")
            synchronized(pendingQueue) { pendingQueue.add(text) }
            return false
        }
        speakNow(text)
        return true
    }

    private fun speakNow(text: String) {
        val id = "llmdroid_${utteranceCounter.incrementAndGet()}"
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun setRate(value: Float) {
        rate = value
        if (ready) tts?.setSpeechRate(value)
    }

    fun setPitch(value: Float) {
        pitch = value
        if (ready) tts?.setPitch(value)
    }

    fun stop() {
        tts?.stop()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        synchronized(pendingQueue) { pendingQueue.clear() }
    }
}
