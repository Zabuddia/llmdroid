package com.alanix.llmdroid.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.alanix.llmdroid.accessibility.GestureExecutor
import com.alanix.llmdroid.accessibility.LLMAccessibilityService
import com.alanix.llmdroid.accessibility.ScreenTreeBuilder
import com.alanix.llmdroid.data.ConversationHistory
import com.alanix.llmdroid.data.SettingsStore
import com.alanix.llmdroid.model.AgentAction
import com.alanix.llmdroid.model.AgentResponse
import com.alanix.llmdroid.model.AgentStatus
import com.alanix.llmdroid.model.LogEntry
import com.alanix.llmdroid.model.LogType
import com.alanix.llmdroid.model.UIElement
import com.alanix.llmdroid.network.OpenAiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class AgentUpdate {
    data class StatusChange(val status: AgentStatus) : AgentUpdate()
    data class Message(val text: String) : AgentUpdate()
    data class Log(val entry: LogEntry) : AgentUpdate()
    data class TranscriptAdd(val isAssistant: Boolean, val content: String) : AgentUpdate()
}

class AgentLoop(
    private val accessibilityService: LLMAccessibilityService,
    private val client: OpenAiClient,
    private val settings: SettingsStore,
    private val onUpdate: (AgentUpdate) -> Unit
) {
    companion object {
        private const val POST_ACTION_SETTLE_MS = 500L
        private const val MAX_ACTIONS_PER_TURN = 10
    }

    private val context: Context get() = accessibilityService
    private val parser = CommandParser()

    @Volatile
    private var shouldStop = false

    fun stop() { shouldStop = true }

    suspend fun run(goal: String) {
        shouldStop = false

        val maxIterations = settings.maxIterations.first()
        val maxHistoryTurns = settings.maxHistoryTurns.first()
        val systemPrompt = settings.systemPrompt.first()

        val history = ConversationHistory(maxHistoryTurns)
        history.setSystemPrompt(systemPrompt)

        val executor = GestureExecutor(accessibilityService)

        onUpdate(AgentUpdate.StatusChange(AgentStatus.Running))
        onUpdate(AgentUpdate.Log(LogEntry(type = LogType.System, content = "Goal: $goal")))
        onUpdate(AgentUpdate.TranscriptAdd(false, goal))

        val installedApps = buildInstalledAppList()
        val currentDateTime = buildDateTimeString()

        var iteration = 0
        var lastActionResults: List<String> = emptyList()
        var lastScreenHash: String? = null

        while (iteration < maxIterations && !shouldStop) {
            iteration++
            onUpdate(AgentUpdate.Log(LogEntry(type = LogType.System, content = "Turn $iteration / $maxIterations")))

            val tree = withContext(Dispatchers.Main) { accessibilityService.getScreenTree() }
            val currentHash = ScreenTreeBuilder.computeScreenHash(tree)
            val screenChanged = lastScreenHash != null && currentHash != lastScreenHash
            lastScreenHash = currentHash

            val userContent = buildUserTurn(
                goal, tree, iteration, lastActionResults, screenChanged,
                if (iteration == 1) installedApps else null,
                if (iteration == 1) currentDateTime else null,
            )
            history.addUserMessage(userContent)
            onUpdate(AgentUpdate.Log(LogEntry(type = LogType.UserTurn, content = userContent)))

            val rawContent = callLlmWithRetry(history.getMessages())
                ?: run {
                    onUpdate(AgentUpdate.StatusChange(AgentStatus.Error))
                    onUpdate(AgentUpdate.Message("API error — see logs"))
                    return
                }

            onUpdate(AgentUpdate.Log(LogEntry(type = LogType.AssistantRaw, content = rawContent)))
            history.addAssistantMessage(rawContent)

            val response = parseWithRepair(rawContent, history)
                ?: run {
                    onUpdate(AgentUpdate.StatusChange(AgentStatus.Error))
                    onUpdate(AgentUpdate.Message("Model could not produce valid JSON"))
                    return
                }

            onUpdate(AgentUpdate.Log(LogEntry(
                type = LogType.ParsedResponse,
                content = "message=${response.message} done=${response.done} actions=${response.actions.size}"
            )))
            onUpdate(AgentUpdate.Message(response.message))
            onUpdate(AgentUpdate.TranscriptAdd(true, response.message))

            if (response.done || shouldStop) {
                onUpdate(AgentUpdate.StatusChange(if (shouldStop) AgentStatus.Stopped else AgentStatus.Done))
                return
            }

            val (hardFailed, actionResults) = executeActions(response, tree, executor)
            lastActionResults = actionResults
            if (hardFailed) {
                onUpdate(AgentUpdate.StatusChange(AgentStatus.Error))
                return
            }

            delay(POST_ACTION_SETTLE_MS)
        }

        if (shouldStop) {
            onUpdate(AgentUpdate.StatusChange(AgentStatus.Stopped))
            onUpdate(AgentUpdate.Message("Stopped by user"))
        } else {
            onUpdate(AgentUpdate.StatusChange(AgentStatus.Error))
            onUpdate(AgentUpdate.Message("Reached max iterations ($maxIterations)"))
        }
    }

    private suspend fun callLlmWithRetry(
        messages: List<com.alanix.llmdroid.network.ChatMessage>
    ): String? {
        val first = withContext(Dispatchers.IO) { client.chatCompletion(messages) }
        if (first.isSuccess) return first.getOrThrow()

        val firstError = first.exceptionOrNull()?.message ?: "unknown"
        onUpdate(AgentUpdate.Log(LogEntry(type = LogType.Error, content = "API error: $firstError — retrying")))

        val retry = withContext(Dispatchers.IO) { client.chatCompletion(messages) }
        return if (retry.isSuccess) {
            retry.getOrThrow()
        } else {
            onUpdate(AgentUpdate.Log(LogEntry(type = LogType.Error, content = "Retry failed: ${retry.exceptionOrNull()?.message}")))
            null
        }
    }

    private suspend fun parseWithRepair(raw: String, history: ConversationHistory): AgentResponse? {
        val result = parser.parse(raw)
        if (result is ParseResult.Success) return result.response

        onUpdate(AgentUpdate.Log(LogEntry(type = LogType.Error, content = "Parse error: ${(result as ParseResult.Failure).error}")))

        val repairPrompt = "Your last response was not valid JSON. Return ONLY a JSON object in the required format."
        history.addUserMessage(repairPrompt)
        val repairRaw = callLlmWithRetry(history.getMessages()) ?: return null
        history.addAssistantMessage(repairRaw)

        return when (val r = parser.parse(repairRaw)) {
            is ParseResult.Success -> r.response
            is ParseResult.Failure -> {
                onUpdate(AgentUpdate.Log(LogEntry(type = LogType.Error, content = "Repair failed: ${r.error}")))
                null
            }
        }
    }

    private fun handleCall(name: String): Pair<Boolean, String> {
        val result = ContactLookup.findBest(context.contentResolver, name)
        val (uri, summary) = if (result != null) {
            Pair("tel:${result.number}", "calling ${result.name} (${result.number})")
        } else {
            Pair("tel:", "contact '$name' not found, opening dialer")
        }

        val canCallDirectly = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
        context.startActivity(
            Intent(action, Uri.parse(uri)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
        return Pair(true, "call: $summary")
    }

    private suspend fun executeActions(
        response: AgentResponse,
        tree: List<UIElement>,
        executor: GestureExecutor,
    ): Pair<Boolean, List<String>> {
        val actions = response.actions.take(MAX_ACTIONS_PER_TURN)
        val results = mutableListOf<String>()

        for (action in actions) {
            if (shouldStop) return Pair(false, results)
            onUpdate(AgentUpdate.Log(LogEntry(type = LogType.ActionResult, content = "→ ${action.action}")))

            if (action.action == "call") {
                val (ok, msg) = handleCall(action.contactName ?: action.text ?: "")
                results.add(msg)
                onUpdate(AgentUpdate.Log(LogEntry(type = LogType.ActionResult, content = msg)))
                continue
            }

            val result = executor.execute(action, tree)
            val summary = buildString {
                append(action.action)
                if (action.index != null) append("[${action.index}]")
                if (action.text != null) append(" \"${action.text.take(40)}\"")
                append(": ")
                if (result.success) append("success") else append("FAILED — ${result.error}")
                if (result.data != null) append(" (data: ${result.data})")
            }
            results.add(summary)
            onUpdate(AgentUpdate.Log(LogEntry(type = LogType.ActionResult, content = summary)))

            if (!result.success && action.action in setOf("launch", "open_url", "intent")) {
                onUpdate(AgentUpdate.Message("Action failed: $summary"))
                return Pair(true, results)
            }
        }
        return Pair(false, results)
    }

    private fun buildInstalledAppList(): String {
        val pm = accessibilityService.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == "com.alanix.llmdroid") return@mapNotNull null
                "${ri.loadLabel(pm)}=${pkg}"
            }
            .sortedBy { it.lowercase() }
            .joinToString(", ")
        return "Installed apps (use launch action — never navigate the launcher UI):\n$apps"
    }

    private fun buildDateTimeString(): String {
        val fmt = SimpleDateFormat("EEEE, MMMM d yyyy, h:mm a", Locale.getDefault())
        return "Current date/time: ${fmt.format(Date())}"
    }

    private fun buildUserTurn(
        goal: String,
        tree: List<UIElement>,
        turn: Int,
        lastActionResults: List<String>,
        screenChanged: Boolean,
        installedApps: String? = null,
        dateTime: String? = null,
    ): String {
        val treeText = tree.mapIndexed { i, el ->
            val attrs = buildString {
                if (el.text.isNotEmpty()) append(" text=\"${el.text}\"")
                if (el.hint.isNotEmpty()) append(" hint=\"${el.hint}\"")
                if (el.clickable) append(" clickable")
                if (el.editable) append(" editable")
                if (el.scrollable) append(" scrollable")
                if (el.checked) append(" checked")
                if (el.focused) append(" focused")
                if (!el.enabled) append(" disabled")
            }
            "[$i] ${el.type}$attrs"
        }.joinToString("\n")

        val feedback = buildString {
            if (lastActionResults.isNotEmpty()) {
                append("\nPrevious action results:\n")
                lastActionResults.forEach { append("  $it\n") }
                append("Screen changed after actions: $screenChanged\n")
            }
        }

        val ctx = buildString {
            if (dateTime != null) append("$dateTime\n")
            if (installedApps != null) append("$installedApps\n")
        }

        return "Goal: $goal\n$ctx$feedback\nUI (turn $turn):\n$treeText"
    }
}
