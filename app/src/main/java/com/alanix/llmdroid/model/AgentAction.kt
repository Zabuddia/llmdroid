package com.alanix.llmdroid.model

import kotlinx.serialization.Serializable

@Serializable
data class AgentAction(
    val action: String,
    val index: Int? = null,
    val text: String? = null,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val duration: Int? = null,
    val packageName: String? = null,
    val url: String? = null,
    val setting: String? = null,
    val code: Int? = null,
    val intentAction: String? = null,
    val uri: String? = null,
    val contactName: String? = null,
)
