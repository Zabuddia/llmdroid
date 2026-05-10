package com.alanix.llmdroid

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.alanix.llmdroid.agent.WakeWordService
import com.alanix.llmdroid.ui.screens.ChatScreen
import com.alanix.llmdroid.ui.screens.LogsScreen
import com.alanix.llmdroid.ui.screens.SettingsScreen
import com.alanix.llmdroid.ui.screens.SkillsScreen
import com.alanix.llmdroid.ui.theme.LLMDroidTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
        }
        permissionLauncher.launch(perms.toTypedArray())

        val settings = (applicationContext as LLMDroidApp).settingsStore

        setContent {
            LLMDroidTheme {
                // Auto-start wake word service if it was enabled
                LaunchedEffect(Unit) {
                    if (settings.wakeWordEnabled.first() && !WakeWordService.isRunning.value) {
                        startForegroundService(
                            Intent(this@MainActivity, WakeWordService::class.java).apply {
                                action = WakeWordService.ACTION_START
                            }
                        )
                    }
                }

                var selectedTab by remember { mutableIntStateOf(0) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                                label = { Text("Chat") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                label = { Text("Logs") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                                label = { Text("Skills") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (selectedTab) {
                            0 -> ChatScreen()
                            1 -> LogsScreen()
                            2 -> SkillsScreen()
                            3 -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
