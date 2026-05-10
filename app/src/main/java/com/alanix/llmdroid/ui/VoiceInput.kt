package com.alanix.llmdroid.ui

import com.alanix.llmdroid.io.stt.VoskManager

class VoiceInput(
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onEnd: () -> Unit,
) {
    val available get() = VoskManager.isReady()

    fun start() {
        VoskManager.startManualListening(onPartial, onResult, onEnd)
    }

    fun stop() {
        VoskManager.stopManualListening()
        onEnd()
    }

    fun destroy() {
        VoskManager.stopManualListening()
    }
}
