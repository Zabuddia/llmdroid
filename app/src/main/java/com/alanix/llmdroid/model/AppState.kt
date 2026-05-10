package com.alanix.llmdroid.model

enum class AgentStatus {
    Idle,
    Running,
    Done,
    Error,
    Stopped
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val type: LogType,
    val content: String
)

enum class LogType {
    UserTurn,
    AssistantRaw,
    ParsedResponse,
    ActionResult,
    Error,
    System
}

data class TranscriptEntry(
    val isAssistant: Boolean,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
