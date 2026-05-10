package com.alanix.llmdroid.data

import com.alanix.llmdroid.network.ChatMessage

class ConversationHistory(private val maxNonSystemTurns: Int = 20) {

    private val messages = mutableListOf<ChatMessage>()

    fun setSystemPrompt(prompt: String) {
        if (messages.isNotEmpty() && messages[0].role == "system") {
            messages[0] = ChatMessage("system", prompt)
        } else {
            messages.add(0, ChatMessage("system", prompt))
        }
    }

    fun addUserMessage(content: String) {
        messages.add(ChatMessage("user", content))
        trim()
    }

    fun addAssistantMessage(content: String) {
        messages.add(ChatMessage("assistant", content))
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun clear() {
        val systemMsg = messages.firstOrNull { it.role == "system" }
        messages.clear()
        if (systemMsg != null) messages.add(systemMsg)
    }

    private fun trim() {
        val systemOffset = if (messages.firstOrNull()?.role == "system") 1 else 0
        val nonSystemCount = messages.size - systemOffset
        if (nonSystemCount > maxNonSystemTurns) {
            val excess = nonSystemCount - maxNonSystemTurns
            repeat(excess) {
                if (messages.size > systemOffset) {
                    messages.removeAt(systemOffset)
                }
            }
        }
    }
}
