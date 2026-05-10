package com.alanix.llmdroid.network

import android.util.Log
import com.alanix.llmdroid.data.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class OpenAiClient(private val settings: SettingsStore) {

    companion object {
        private const val TAG = "OpenAiClient"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    suspend fun chatCompletion(messages: List<ChatMessage>): Result<String> {
        return try {
            val serverUrl = settings.serverUrl.first()
            val apiKey = settings.apiKey.first()
            val model = settings.model.first()

            val response = httpClient.post("$serverUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
                setBody(ChatCompletionRequest(model = model, messages = messages))
            }

            val completion = response.body<ChatCompletionResponse>()
            val content = completion.choices.firstOrNull()?.message?.content
                ?: return Result.failure(Exception("Model returned empty content"))

            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "chatCompletion failed", e)
            Result.failure(e)
        }
    }

    suspend fun testConnection(): Result<Unit> {
        return try {
            val serverUrl = settings.serverUrl.first()
            val apiKey = settings.apiKey.first()
            val model = settings.model.first()

            val response = httpClient.post("$serverUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
                setBody(
                    ChatCompletionRequest(
                        model = model,
                        messages = listOf(ChatMessage("user", "ping")),
                        maxTokens = 5
                    )
                )
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("HTTP ${response.status.value}: ${response.status.description}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed", e)
            Result.failure(e)
        }
    }

    fun close() = httpClient.close()
}
