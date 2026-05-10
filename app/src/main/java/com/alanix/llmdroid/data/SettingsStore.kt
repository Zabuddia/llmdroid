package com.alanix.llmdroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "llmdroid_settings")

class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val KEY_HAS_ONBOARDED = booleanPreferencesKey("has_onboarded")
        private val KEY_MAX_ITERATIONS = intPreferencesKey("max_iterations")
        private val KEY_MAX_HISTORY_TURNS = intPreferencesKey("max_history_turns")
        private val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")

        const val DEFAULT_SERVER_URL = "http://alan-framework:4000"
        const val DEFAULT_MODEL = "qwen3.6-35b-a3b"

        val DEFAULT_SYSTEM_PROMPT = """
You are an Android automation agent. You control a real device by reading its UI accessibility tree and issuing actions.

For every turn you receive the user's goal and the current indexed UI tree. Respond with EXACTLY ONE JSON object and nothing else — no prose, no markdown, no code fences.

Response format:
{"message":"One-sentence description of this step.","done":false,"actions":[{"action":"tap","index":0}]}

When the goal is complete, set "done":true. When done, actions may be empty.
The actions array is executed in order. Maximum 10 actions per response.

Supported actions (all fields shown, optional fields may be omitted):
  tap           {"action":"tap","index":N}
  longpress     {"action":"longpress","index":N}
  focus         {"action":"focus","index":N}
  replace_text  {"action":"replace_text","index":N,"text":"..."}
  type          {"action":"type","text":"..."}
  paste         {"action":"paste","index":N}
  clear         {"action":"clear","index":N}
  enter         {"action":"enter"}
  back          {"action":"back"}
  home          {"action":"home"}
  notifications {"action":"notifications"}
  recents       {"action":"recents"}
  wait          {"action":"wait","duration":500}
  swipe         {"action":"swipe","x1":0,"y1":500,"x2":0,"y2":100}
  launch        {"action":"launch","packageName":"com.example.app"}
  open_url      {"action":"open_url","url":"https://example.com"}
  open_settings {"action":"open_settings","setting":"wifi"}
  keyevent      {"action":"keyevent","code":66}
  intent        {"action":"intent","intentAction":"android.intent.action.VIEW","uri":"..."}
  clipboard_set {"action":"clipboard_set","text":"..."}
  clipboard_get {"action":"clipboard_get"}

Rules:
- To open any app, ALWAYS use {"action":"launch","packageName":"..."} — never navigate the home screen or app drawer.
- Prefer replace_text(index,text) over type(text) whenever the target field index is known.
- Output only valid JSON. Never add markdown, code fences, or any text outside the JSON.
- Keep "message" to one sentence maximum.
- If the goal is already done or cannot be completed, set "done":true and explain in "message".
- When activated by voice, keep "message" short and natural for speech.
""".trimIndent()
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL }
    val apiKey: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }
    val model: Flow<String> = context.dataStore.data.map { it[KEY_MODEL] ?: DEFAULT_MODEL }
    val systemPrompt: Flow<String> = context.dataStore.data.map { it[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT }
    val hasOnboarded: Flow<Boolean> = context.dataStore.data.map { it[KEY_HAS_ONBOARDED] ?: false }
    val maxIterations: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_ITERATIONS] ?: 30 }
    val maxHistoryTurns: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_HISTORY_TURNS] ?: 20 }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_WORD_ENABLED] ?: false }

    suspend fun setServerUrl(value: String) = context.dataStore.edit { it[KEY_SERVER_URL] = value }
    suspend fun setApiKey(value: String) = context.dataStore.edit { it[KEY_API_KEY] = value }
    suspend fun setModel(value: String) = context.dataStore.edit { it[KEY_MODEL] = value }
    suspend fun setSystemPrompt(value: String) = context.dataStore.edit { it[KEY_SYSTEM_PROMPT] = value }
    suspend fun setHasOnboarded(value: Boolean) = context.dataStore.edit { it[KEY_HAS_ONBOARDED] = value }
    suspend fun setMaxIterations(value: Int) = context.dataStore.edit { it[KEY_MAX_ITERATIONS] = value }
    suspend fun setMaxHistoryTurns(value: Int) = context.dataStore.edit { it[KEY_MAX_HISTORY_TURNS] = value }
    suspend fun setWakeWordEnabled(value: Boolean) = context.dataStore.edit { it[KEY_WAKE_WORD_ENABLED] = value }
}
