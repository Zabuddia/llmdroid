package com.alanix.llmdroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alanix.llmdroid.agent.AgentService
import com.alanix.llmdroid.model.LogEntry
import com.alanix.llmdroid.model.LogType
import com.alanix.llmdroid.ui.theme.LogColorActionFail
import com.alanix.llmdroid.ui.theme.LogColorActionOk
import com.alanix.llmdroid.ui.theme.LogColorAssistantRaw
import com.alanix.llmdroid.ui.theme.LogColorError
import com.alanix.llmdroid.ui.theme.LogColorParsed
import com.alanix.llmdroid.ui.theme.LogColorSystem
import com.alanix.llmdroid.ui.theme.LogColorUser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun LogsScreen() {
    val logs by AgentService.logs.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${logs.size} entries",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                IconButton(onClick = {
                    val text = logs.joinToString("\n") { entry ->
                        val time = timeFormat.format(Date(entry.timestamp))
                        val label = logLabel(entry)
                        "[$time][$label] ${entry.content}"
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        putExtra(Intent.EXTRA_SUBJECT, "LLMDroid Logs")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share logs"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share logs")
                }
                IconButton(onClick = { AgentService.logs.value = emptyList() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                }
            }
        }

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = logColor(entry)
    val label = logLabel(entry)
    val time = timeFormat.format(Date(entry.timestamp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = time,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.Gray,
            modifier = Modifier.padding(end = 6.dp, top = 1.dp)
        )
        Text(
            text = "[$label] ",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(end = 4.dp, top = 1.dp)
        )
        Text(
            text = entry.content,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun logColor(entry: LogEntry): Color = when (entry.type) {
    LogType.UserTurn -> LogColorUser
    LogType.AssistantRaw -> LogColorAssistantRaw
    LogType.ParsedResponse -> LogColorParsed
    LogType.ActionResult -> if (entry.content.contains("FAIL", ignoreCase = true)) LogColorActionFail else LogColorActionOk
    LogType.Error -> LogColorError
    LogType.System -> LogColorSystem
}

private fun logLabel(entry: LogEntry): String = when (entry.type) {
    LogType.UserTurn -> "USER"
    LogType.AssistantRaw -> "RAW"
    LogType.ParsedResponse -> "PARSED"
    LogType.ActionResult -> "ACTION"
    LogType.Error -> "ERROR"
    LogType.System -> "SYS"
}
