package com.alanix.llmdroid.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sms
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alanix.llmdroid.LLMDroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen() {
    val context = LocalContext.current
    val settings = (context.applicationContext as LLMDroidApp).settingsStore
    val scope = rememberCoroutineScope()

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
                hasContactsPermission = (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED)
                hasCallPermission = (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    var musicAppPkg by remember { mutableStateOf("") }
    var messagingAppPkg by remember { mutableStateOf("") }
    var skillCallEnabled by remember { mutableStateOf(true) }
    var skillOpenEnabled by remember { mutableStateOf(true) }
    var skillPlayEnabled by remember { mutableStateOf(true) }
    var skillTextEnabled by remember { mutableStateOf(true) }
    var skillMessageEnabled by remember { mutableStateOf(true) }
    var skillUnlockEnabled by remember { mutableStateOf(false) }
    var unlockPin by remember { mutableStateOf("") }
    var searchAppPkg by remember { mutableStateOf("") }
    var skillSearchEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        musicAppPkg = settings.musicApp.first()
        messagingAppPkg = settings.messagingApp.first()
        skillCallEnabled = settings.skillCallEnabled.first()
        skillOpenEnabled = settings.skillOpenEnabled.first()
        skillPlayEnabled = settings.skillPlayEnabled.first()
        skillTextEnabled = settings.skillTextEnabled.first()
        skillMessageEnabled = settings.skillMessageEnabled.first()
        skillUnlockEnabled = settings.skillUnlockEnabled.first()
        unlockPin = settings.unlockPin.first()
        searchAppPkg = settings.searchApp.first()
        skillSearchEnabled = settings.skillSearchEnabled.first()
    }

    @Suppress("DEPRECATION")
    val installedApps = remember {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == "com.alanix.llmdroid") return@mapNotNull null
            Pair(ri.loadLabel(context.packageManager).toString(), pkg)
        }.sortedBy { it.first.lowercase() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Skills", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Skills are instant shortcuts that bypass the AI agent. " +
            "Say the trigger phrase after \"Hey Dicio\" or type it in the chat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        SkillCard(
            icon = { Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(28.dp)) },
            name = "Call Contact",
            trigger = "\"call [name]\"",
            description = "Looks up the contact by name and places the call directly — no agent loop, no API call.",
            examples = listOf("Hey Dicio, call Alan Fife", "Hey Dicio, call mom", "Type: call Alan Fife"),
            requirements = listOf(
                Pair("Contacts permission", hasContactsPermission),
                Pair("Phone permission", hasCallPermission),
            ),
            enabled = skillCallEnabled,
            onEnabledChange = {
                skillCallEnabled = it
                scope.launch { settings.setSkillCallEnabled(it) }
            }
        )

        SkillCard(
            icon = { Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(28.dp)) },
            name = "Open App",
            trigger = "\"open [app name]\"",
            description = "Fuzzy-matches the app name against installed apps and launches it instantly. Falls back to the AI agent if no match is found.",
            examples = listOf("Hey Dicio, open Spotify", "Hey Dicio, open camera", "Type: open maps"),
            requirements = emptyList(),
            enabled = skillOpenEnabled,
            onEnabledChange = {
                skillOpenEnabled = it
                scope.launch { settings.setSkillOpenEnabled(it) }
            }
        )

        SkillCard(
            icon = { Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(28.dp)) },
            name = "Send Text (SMS)",
            trigger = "\"text [name] [message]\"",
            description = "Looks up the contact and opens your SMS app with the contact and message pre-filled. The AI agent then taps send.",
            examples = listOf(
                "Hey Dicio, text Alan Fife I'm on my way",
                "Hey Dicio, text mom happy birthday",
                "Type: text john see you at 5",
            ),
            requirements = listOf(Pair("Contacts permission", hasContactsPermission)),
            enabled = skillTextEnabled,
            onEnabledChange = {
                skillTextEnabled = it
                scope.launch { settings.setSkillTextEnabled(it) }
            }
        )

        SkillCard(
            icon = { Icon(Icons.Default.Message, contentDescription = null, modifier = Modifier.size(28.dp)) },
            name = "Message (App)",
            trigger = "\"message [name] [message]\"",
            description = "Opens your default messaging app and hands the full command to the AI agent, which is already inside the app and can find the contact and send the message.",
            examples = listOf(
                "Hey Dicio, message Alan Fife I'm on my way",
                "Hey Dicio, message mom on WhatsApp",
                "Type: message john see you at 5",
            ),
            requirements = emptyList(),
            enabled = skillMessageEnabled,
            onEnabledChange = {
                skillMessageEnabled = it
                scope.launch { settings.setSkillMessageEnabled(it) }
            },
            config = {
                var expanded by remember { mutableStateOf(false) }
                val selectedLabel = installedApps.firstOrNull { it.second == messagingAppPkg }?.first
                    ?: if (messagingAppPkg.isBlank()) "Not set — AI agent will handle it" else messagingAppPkg

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default messaging app") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None (AI agent handles it)") },
                            onClick = {
                                messagingAppPkg = ""
                                scope.launch { settings.setMessagingApp("") }
                                expanded = false
                            }
                        )
                        installedApps.forEach { (label, pkg) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    messagingAppPkg = pkg
                                    scope.launch { settings.setMessagingApp(pkg) }
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        SkillCard(
            icon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(28.dp)) },
            name = "Unlock & Run",
            trigger = "\"unlock [task]\"",
            description = "Enters your PIN on the lock screen, runs the task, then locks the phone again when done. Works with other skills — \"unlock play …\" will unlock then open your music app.",
            examples = listOf(
                "Hey Dicio, unlock play when I grow up",
                "Hey Dicio, unlock open camera",
                "Type: unlock text mom I'm on my way",
            ),
            requirements = emptyList(),
            enabled = skillUnlockEnabled,
            onEnabledChange = {
                skillUnlockEnabled = it
                scope.launch { settings.setSkillUnlockEnabled(it) }
            },
            config = {
                OutlinedTextField(
                    value = unlockPin,
                    onValueChange = {
                        unlockPin = it
                        scope.launch { settings.setUnlockPin(it) }
                    },
                    label = { Text("Phone PIN / Password") },
                    placeholder = { Text("Enter your lock screen PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        )

        SkillCard(
            icon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(28.dp)) },
            name = "Search",
            trigger = "\"search [query]\"",
            description = "Opens your default search app and hands the query to the AI agent, which is already inside the app and can type and run the search.",
            examples = listOf(
                "Hey Dicio, search weather in New York",
                "Hey Dicio, search how to make sourdough",
                "Type: search best hiking trails near me",
            ),
            requirements = emptyList(),
            enabled = skillSearchEnabled,
            onEnabledChange = {
                skillSearchEnabled = it
                scope.launch { settings.setSkillSearchEnabled(it) }
            },
            config = {
                var expanded by remember { mutableStateOf(false) }
                val selectedLabel = installedApps.firstOrNull { it.second == searchAppPkg }?.first
                    ?: if (searchAppPkg.isBlank()) "Not set — AI agent will handle it" else searchAppPkg

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default search app") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None (AI agent handles it)") },
                            onClick = {
                                searchAppPkg = ""
                                scope.launch { settings.setSearchApp("") }
                                expanded = false
                            }
                        )
                        installedApps.forEach { (label, pkg) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    searchAppPkg = pkg
                                    scope.launch { settings.setSearchApp(pkg) }
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        SkillCard(
            icon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(28.dp)) },
            name = "Play Music",
            trigger = "\"play [song / artist / playlist]\"",
            description = "Opens your default music app and then hands the command to the AI agent, which is now already inside the app and can search and play what you asked for.",
            examples = listOf(
                "Hey Dicio, play Wake Up by Rage Against the Machine",
                "Hey Dicio, play lofi hip hop",
                "Type: play my liked songs",
            ),
            requirements = emptyList(),
            enabled = skillPlayEnabled,
            onEnabledChange = {
                skillPlayEnabled = it
                scope.launch { settings.setSkillPlayEnabled(it) }
            },
            config = {
                var expanded by remember { mutableStateOf(false) }
                val selectedLabel = installedApps.firstOrNull { it.second == musicAppPkg }?.first
                    ?: if (musicAppPkg.isBlank()) "Not set — AI agent will handle it" else musicAppPkg

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default music app") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None (AI agent handles it)") },
                            onClick = {
                                musicAppPkg = ""
                                scope.launch { settings.setMusicApp("") }
                                expanded = false
                            }
                        )
                        installedApps.forEach { (label, pkg) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    musicAppPkg = pkg
                                    scope.launch { settings.setMusicApp(pkg) }
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillCard(
    icon: @Composable () -> Unit,
    name: String,
    trigger: String,
    description: String,
    examples: List<String>,
    requirements: List<Pair<String, Boolean>> = emptyList(),
    enabled: Boolean = true,
    onEnabledChange: (Boolean) -> Unit = {},
    config: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Trigger: $trigger",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            Text(description, style = MaterialTheme.typography.bodySmall)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Examples:", style = MaterialTheme.typography.labelMedium)
                examples.forEach { example ->
                    Text(
                        "• $example",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (requirements.isNotEmpty()) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Required permissions:", style = MaterialTheme.typography.labelMedium)
                    requirements.forEach { (label, granted) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (granted) Color(0xFF388E3C) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (config != null) {
                HorizontalDivider()
                config()
            }
        }
    }
}
