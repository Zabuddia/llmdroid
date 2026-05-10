package com.alanix.llmdroid.model

import kotlinx.serialization.Serializable

@Serializable
data class AgentResponse(
    val message: String = "",
    val done: Boolean = false,
    val actions: List<AgentAction> = emptyList()
)
