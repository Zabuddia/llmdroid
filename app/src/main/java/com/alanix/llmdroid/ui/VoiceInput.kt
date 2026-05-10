package com.alanix.llmdroid.ui

import com.alanix.llmdroid.data.SettingsStore
import com.alanix.llmdroid.io.stt.VoskManager
import com.alanix.llmdroid.io.stt.WhisperStt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoiceInput(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onEnd: () -> Unit,
) {
    val available: Boolean = true

    fun start() {
        scope.launch {
            val engine = settings.sttEngine.first()
            if (engine == SettingsStore.STT_ENGINE_WHISPER) {
                val url = settings.whisperUrl.first()
                val whisperModel = settings.whisperModel.first()
                val apiKey = settings.apiKey.first()
                WhisperStt.startManualListening(scope, url, whisperModel, apiKey, onPartial, onResult, onEnd)
            } else {
                VoskManager.startManualListening(onPartial, onResult, onEnd)
            }
        }
    }

    fun stop() {
        VoskManager.stopManualListening()
        WhisperStt.stopManualListening()
        onEnd()
    }

    fun destroy() {
        VoskManager.stopManualListening()
        WhisperStt.stopManualListening()
    }
}
