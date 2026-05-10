package com.alanix.llmdroid.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alanix.llmdroid.LLMDroidApp
import com.alanix.llmdroid.accessibility.LLMAccessibilityService
import com.alanix.llmdroid.agent.AgentService
import com.alanix.llmdroid.agent.WakeWordService
import com.alanix.llmdroid.model.AgentStatus
import com.alanix.llmdroid.model.TranscriptEntry
import com.alanix.llmdroid.ui.VoiceInput

private enum class MicState { Idle, Listening }

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val settings = (context.applicationContext as LLMDroidApp).settingsStore
    val status by AgentService.status.collectAsState()
    val currentMessage by AgentService.currentMessage.collectAsState()
    val transcript by AgentService.transcript.collectAsState()
    val a11yRunning by LLMAccessibilityService.isRunning.collectAsState()

    var goalText by remember { mutableStateOf("") }
    var micState by remember { mutableStateOf(MicState.Idle) }
    val listState = rememberLazyListState()

    val wakeListeningText by WakeWordService.listeningText.collectAsState()

    val voiceInput = remember {
        VoiceInput(
            onPartial = { goalText = it },
            onResult = { goalText = it },
            onEnd = { micState = MicState.Idle }
        )
    }
    DisposableEffect(Unit) { onDispose { voiceInput.destroy() } }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micState = MicState.Listening
            voiceInput.start()
        }
    }

    fun toggleMic() {
        when (micState) {
            MicState.Idle -> {
                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    micState = MicState.Listening
                    voiceInput.start()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            MicState.Listening -> {
                voiceInput.stop()
            }
        }
    }

    val micAvailable = voiceInput.available

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        if (!a11yRunning) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Accessibility service not enabled — go to Settings to enable it",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        StatusBar(status = status, currentMessage = currentMessage)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(transcript) { entry -> TranscriptBubble(entry) }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = goalText,
                onValueChange = { goalText = it },
                placeholder = {
                    Text(if (micState == MicState.Listening) "Listening…" else "Enter your goal…")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = status == AgentStatus.Idle || status == AgentStatus.Done ||
                    status == AgentStatus.Error || status == AgentStatus.Stopped,
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status != AgentStatus.Running && micAvailable) {
                    MicButton(micState = micState, onClick = ::toggleMic)
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (status == AgentStatus.Running) {
                    Button(
                        onClick = {
                            context.startService(
                                Intent(context, AgentService::class.java).apply {
                                    action = AgentService.ACTION_STOP
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = {
                            val trimmed = goalText.trim()
                            if (trimmed.isEmpty()) return@Button
                            context.startForegroundService(
                                Intent(context, AgentService::class.java).apply {
                                    action = AgentService.ACTION_START
                                    putExtra(AgentService.EXTRA_GOAL, trimmed)
                                }
                            )
                        },
                        enabled = goalText.isNotBlank() && a11yRunning
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start")
                    }
                }
            }
        }
    }

    // Wake word listening banner
    AnimatedVisibility(
        visible = wakeListeningText != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val pulse = rememberInfiniteTransition(label = "wakeListenPulse")
                val pulseScale by pulse.animateFloat(
                    initialValue = 0.8f, targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
                    label = "wakeListenScale"
                )
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(20.dp).scale(pulseScale)
                )
                Text(
                    text = wakeListeningText ?: "Listening…",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    } // end Box
}

@Composable
private fun MicButton(micState: MicState, onClick: () -> Unit) {
    val micColor by animateColorAsState(
        targetValue = if (micState == MicState.Listening) Color(0xFFE53935) else Color.Unspecified,
        label = "micColor"
    )
    val scale = if (micState == MicState.Listening) {
        val t = rememberInfiniteTransition(label = "micPulse")
        val s by t.animateFloat(
            initialValue = 0.9f, targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
            label = "micScale"
        )
        s
    } else 1f

    IconButton(onClick = onClick, modifier = Modifier.scale(scale)) {
        Icon(Icons.Default.Mic, contentDescription = if (micState == MicState.Listening) "Stop" else "Voice input",
            tint = micColor)
    }
}

@Composable
private fun StatusBar(status: AgentStatus, currentMessage: String) {
    val (bgColor, label) = when (status) {
        AgentStatus.Idle -> Pair(Color(0xFFEEEEEE), "Idle")
        AgentStatus.Running -> Pair(Color(0xFFE3F2FD), "Running")
        AgentStatus.Done -> Pair(Color(0xFFE8F5E9), "Done")
        AgentStatus.Error -> Pair(Color(0xFFFFEBEE), "Error")
        AgentStatus.Stopped -> Pair(Color(0xFFFFF3E0), "Stopped")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == AgentStatus.Running) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = "$label${if (currentMessage.isNotEmpty() && status == AgentStatus.Running) ": $currentMessage" else ""}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry) {
    val alignment = if (entry.isAssistant) Alignment.CenterStart else Alignment.CenterEnd
    val bgColor = if (entry.isAssistant)
        MaterialTheme.colorScheme.surfaceVariant
    else
        MaterialTheme.colorScheme.primaryContainer

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .align(alignment)
                .padding(
                    start = if (entry.isAssistant) 0.dp else 48.dp,
                    end = if (entry.isAssistant) 48.dp else 0.dp
                ),
            shape = RoundedCornerShape(
                topStart = if (entry.isAssistant) 4.dp else 12.dp,
                topEnd = if (entry.isAssistant) 12.dp else 4.dp,
                bottomStart = 12.dp,
                bottomEnd = 12.dp
            ),
            colors = CardDefaults.cardColors(containerColor = bgColor)
        ) {
            Text(
                text = entry.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 14.sp
            )
        }
    }
}
