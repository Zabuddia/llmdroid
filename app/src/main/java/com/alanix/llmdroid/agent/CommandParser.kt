package com.alanix.llmdroid.agent

import com.alanix.llmdroid.model.AgentResponse
import kotlinx.serialization.json.Json

sealed class ParseResult {
    data class Success(val response: AgentResponse, val cleanedJson: String) : ParseResult()
    data class Failure(val error: String) : ParseResult()
}

class CommandParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): ParseResult {
        val trimmed = raw.trim()

        // 1. Try direct parse
        tryParse(trimmed)?.let { return ParseResult.Success(it, trimmed) }

        // 2. Try stripping markdown code fences
        stripMarkdown(trimmed)?.let { stripped ->
            tryParse(stripped)?.let { return ParseResult.Success(it, stripped) }
        }

        return ParseResult.Failure("Could not parse as JSON: ${trimmed.take(200)}")
    }

    private fun tryParse(text: String): AgentResponse? = try {
        json.decodeFromString<AgentResponse>(text)
    } catch (_: Exception) {
        null
    }

    private fun stripMarkdown(text: String): String? {
        val fenceRegex = Regex("```(?:json)?\\s*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
        return fenceRegex.find(text)?.groupValues?.get(1)?.trim()
    }
}
