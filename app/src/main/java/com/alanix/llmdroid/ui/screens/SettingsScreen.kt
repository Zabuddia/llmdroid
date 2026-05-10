package com.alanix.llmdroid.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alanix.llmdroid.LLMDroidApp
import com.alanix.llmdroid.accessibility.LLMAccessibilityService
import com.alanix.llmdroid.agent.WakeWordService
import com.alanix.llmdroid.data.SettingsStore
import com.alanix.llmdroid.io.stt.VoskManager
import com.alanix.llmdroid.io.wake.WakeWordDetector
import com.alanix.llmdroid.network.OpenAiClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = (context.applicationContext as LLMDroidApp).settingsStore
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val a11yRunning by LLMAccessibilityService.isRunning.collectAsState()

    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED)
    }
    var hasContactsPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED)
    }
    var hasCallPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
                hasMicPermission = (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED)
                hasContactsPermission = (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED)
                hasCallPermission = (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var maxIterations by remember { mutableStateOf("") }
    var sttEngine by remember { mutableStateOf(SettingsStore.STT_ENGINE_VOSK) }
    var whisperUrl by remember { mutableStateOf("") }
    var whisperModel by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    val wakeServiceRunning by WakeWordService.isRunning.collectAsState()
    val owwState by WakeWordDetector.state.collectAsState()
    val voskState by VoskManager.state.collectAsState()

    // Load current values once
    if (!initialized) {
        scope.launch {
            serverUrl = settings.serverUrl.first()
            apiKey = settings.apiKey.first()
            model = settings.model.first()
            systemPrompt = settings.systemPrompt.first()
            maxIterations = settings.maxIterations.first().toString()
            sttEngine = settings.sttEngine.first()
            whisperUrl = settings.whisperUrl.first()
            whisperModel = settings.whisperModel.first()
            initialized = true
        }
    }

    var testStatus by remember { mutableStateOf<TestState>(TestState.Idle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        HorizontalDivider()

        // --- Permissions ---
        Text("Permissions", style = MaterialTheme.typography.titleMedium)

        PermissionRow(
            label = "Accessibility Service",
            granted = a11yRunning,
            onFix = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        )

        PermissionRow(
            label = "Draw Over Other Apps (overlay)",
            granted = canDrawOverlays,
            onFix = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        )

        PermissionRow(
            label = "Microphone (voice input)",
            granted = hasMicPermission,
            onFix = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        )

        PermissionRow(
            label = "Contacts (call by name)",
            granted = hasContactsPermission,
            onFix = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        )

        PermissionRow(
            label = "Phone (place calls directly)",
            granted = hasCallPermission,
            onFix = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        )

        HorizontalDivider()

        // --- API Config ---
        Text("API Configuration", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = serverUrl,
            onValueChange = {
                serverUrl = it
                scope.launch { settings.setServerUrl(it) }
            },
            label = { Text("Base URL") },
            placeholder = { Text(SettingsStore.DEFAULT_SERVER_URL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                scope.launch { settings.setApiKey(it) }
            },
            label = { Text("API Key (optional)") },
            placeholder = { Text("Leave empty if not required") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        OutlinedTextField(
            value = model,
            onValueChange = {
                model = it
                scope.launch { settings.setModel(it) }
            },
            label = { Text("Model") },
            placeholder = { Text(SettingsStore.DEFAULT_MODEL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Test API button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    testStatus = TestState.Testing
                    scope.launch {
                        val client = OpenAiClient(settings)
                        val result = client.testConnection()
                        client.close()
                        testStatus = if (result.isSuccess) {
                            TestState.Success
                        } else {
                            TestState.Failure(result.exceptionOrNull()?.message ?: "Unknown error")
                        }
                    }
                },
                enabled = testStatus != TestState.Testing
            ) {
                if (testStatus == TestState.Testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Test API")
                }
            }

            when (val s = testStatus) {
                TestState.Idle -> {}
                TestState.Testing -> Text("Testing…", style = MaterialTheme.typography.labelSmall)
                TestState.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF388E3C), modifier = Modifier.size(18.dp))
                    Text(" Connected", color = Color(0xFF388E3C), fontSize = 13.sp)
                }
                is TestState.Failure -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(" ${s.message.take(60)}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }
        }

        HorizontalDivider()

        // --- Loop Config ---
        Text("Agent Loop", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = maxIterations,
            onValueChange = { v ->
                maxIterations = v
                v.toIntOrNull()?.let { scope.launch { settings.setMaxIterations(it) } }
            },
            label = { Text("Max Iterations") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        HorizontalDivider()

        // --- Speech-to-Text ---
        Text("Speech-to-Text", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isVosk = sttEngine == SettingsStore.STT_ENGINE_VOSK
            if (isVosk) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Vosk (offline)") }
            } else {
                OutlinedButton(
                    onClick = {
                        sttEngine = SettingsStore.STT_ENGINE_VOSK
                        scope.launch { settings.setSttEngine(SettingsStore.STT_ENGINE_VOSK) }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Vosk (offline)") }
            }
            if (!isVosk) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Whisper API") }
            } else {
                OutlinedButton(
                    onClick = {
                        sttEngine = SettingsStore.STT_ENGINE_WHISPER
                        scope.launch { settings.setSttEngine(SettingsStore.STT_ENGINE_WHISPER) }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Whisper API") }
            }
        }

        if (sttEngine == SettingsStore.STT_ENGINE_VOSK) {
            ModelStatusRow("Speech recognition (Vosk)", voskState.statusText)
            Text(
                "Vosk model is downloaded automatically on first use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedTextField(
                value = whisperUrl,
                onValueChange = {
                    whisperUrl = it
                    scope.launch { settings.setWhisperUrl(it) }
                },
                label = { Text("Whisper API URL") },
                placeholder = { Text(SettingsStore.DEFAULT_WHISPER_URL) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = whisperModel,
                onValueChange = {
                    whisperModel = it
                    scope.launch { settings.setWhisperModel(it) }
                },
                label = { Text("Whisper model") },
                placeholder = { Text(SettingsStore.DEFAULT_WHISPER_MODEL) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        HorizontalDivider()

        // --- Wake Word ---
        Text("Wake Word", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Enable 'Hey Dicio' listener", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (wakeServiceRunning) "Running" else "Stopped",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wakeServiceRunning) Color(0xFF388E3C) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = wakeServiceRunning,
                onCheckedChange = { enable ->
                    scope.launch { settings.setWakeWordEnabled(enable) }
                    if (enable) {
                        context.startForegroundService(
                            Intent(context, WakeWordService::class.java).apply {
                                action = WakeWordService.ACTION_START
                            }
                        )
                    } else {
                        context.startService(
                            Intent(context, WakeWordService::class.java).apply {
                                action = WakeWordService.ACTION_STOP
                            }
                        )
                    }
                }
            )
        }

        Text(
            "Say \"Hey Dicio\" to activate voice command.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ModelStatusRow("Wake word (OpenWakeWord)", owwState.statusText)

        HorizontalDivider()

        // --- System Prompt ---
        Text("System Prompt", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = {
                systemPrompt = it
                scope.launch { settings.setSystemPrompt(it) }
            },
            label = { Text("System Prompt") },
            modifier = Modifier.fillMaxWidth().height(220.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 8
        )

        OutlinedButton(
            onClick = {
                systemPrompt = SettingsStore.DEFAULT_SYSTEM_PROMPT
                scope.launch { settings.setSystemPrompt(SettingsStore.DEFAULT_SYSTEM_PROMPT) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset to Default Prompt")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onFix: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (granted) Color(0xFF388E3C) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "  $label",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (!granted) {
            Button(
                onClick = onFix,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Enable", fontSize = 12.sp)
            }
        }
    }
}

private sealed class TestState {
    object Idle : TestState()
    object Testing : TestState()
    object Success : TestState()
    data class Failure(val message: String) : TestState()
}

@Composable
private fun ModelStatusRow(name: String, statusLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall)
        Text(statusLabel, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val WakeWordDetector.State.statusText: String get() = when (this) {
    is WakeWordDetector.State.NotDownloaded -> "Not downloaded"
    is WakeWordDetector.State.Downloading -> "Downloading $label (${(progress * 100).toInt()}%)"
    is WakeWordDetector.State.NotLoaded -> "Not loaded"
    is WakeWordDetector.State.Loading -> "Loading…"
    is WakeWordDetector.State.Ready -> "Ready"
    is WakeWordDetector.State.Error -> "Error: ${message.take(40)}"
}

private val VoskManager.State.statusText: String get() = when (this) {
    is VoskManager.State.NotDownloaded -> "Not downloaded"
    is VoskManager.State.Downloading -> "Downloading (${(progress * 100).toInt()}%)"
    is VoskManager.State.Extracting -> "Extracting…"
    is VoskManager.State.NotLoaded -> "Not loaded"
    is VoskManager.State.Loading -> "Loading…"
    is VoskManager.State.Ready -> "Ready"
    is VoskManager.State.Error -> "Error: ${message.take(40)}"
}
