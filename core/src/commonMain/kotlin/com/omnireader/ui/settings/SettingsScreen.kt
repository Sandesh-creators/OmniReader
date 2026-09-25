package com.omnireader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnireader.viewmodel.AppController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    controller: AppController,
    onBack: () -> Unit,
    appVersion: String = "1.0.0",
    showExecutionSection: Boolean = false
) {
    val fontSize by controller.fontSize.collectAsState()
    val isNightMode by controller.isNightMode.collectAsState()
    val ttsState by controller.ttsState.collectAsState()
    val autoAdvanceChapters by controller.autoAdvanceChapters.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Appearance") {
                SettingsItem(
                    icon = Icons.Filled.TextFormat,
                    title = "Default Font Size",
                    subtitle = "${fontSize}sp",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { controller.setFontSize(fontSize - 1) }) {
                                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
                            }
                            Text("$fontSize", modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { controller.setFontSize(fontSize + 1) }) {
                                Icon(Icons.Filled.Add, contentDescription = "Increase")
                            }
                        }
                    }
                )

                SettingsItem(
                    icon = Icons.Filled.DarkMode,
                    title = "Night Mode",
                    subtitle = "Dark background for reading",
                    trailing = {
                        Switch(
                            checked = isNightMode,
                            onCheckedChange = { controller.setNightMode(it) }
                        )
                    }
                )
            }

            SettingsSection(title = "Text-to-Speech") {
                SettingsItem(
                    icon = Icons.Filled.Speed,
                    title = "Playback Speed",
                    subtitle = "${"%.1f".format(ttsState.speed)}x",
                    trailing = {
                        Slider(
                            value = ttsState.speed,
                            onValueChange = { controller.setTtsSpeed(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 6,
                            modifier = Modifier.width(120.dp)
                        )
                    }
                )

                SettingsItem(
                    icon = Icons.Filled.GraphicEq,
                    title = "Pitch",
                    subtitle = "${"%.1f".format(ttsState.pitch)}x",
                    trailing = {
                        Slider(
                            value = ttsState.pitch,
                            onValueChange = { controller.setTtsPitch(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 6,
                            modifier = Modifier.width(120.dp)
                        )
                    }
                )

                SettingsItem(
                    icon = Icons.Filled.Repeat,
                    title = "Auto-read next chapter",
                    subtitle = "Continue to the next chapter automatically",
                    trailing = {
                        Switch(
                            checked = autoAdvanceChapters,
                            onCheckedChange = { controller.setAutoAdvanceChapters(it) }
                        )
                    }
                )
            }

            if (showExecutionSection) {
                SettingsSection(title = "Execution Engine") {
                    SettingsItem(
                        icon = Icons.Filled.Shield,
                        title = "Permission Checks",
                        subtitle = "Validate scripts before execution",
                        trailing = {
                            Switch(checked = true, onCheckedChange = null)
                        }
                    )

                    SettingsItem(
                        icon = Icons.Filled.Warning,
                        title = "Architecture Warnings",
                        subtitle = "Warn before running .exe on ARM",
                        trailing = {
                            Switch(checked = true, onCheckedChange = null)
                        }
                    )
                }
            }

            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Filled.Info,
                    title = "OmniReader",
                    subtitle = "Version $appVersion"
                )

                SettingsItem(
                    icon = Icons.Filled.Code,
                    title = "Build Info",
                    subtitle = "Kotlin 1.9.22 • Compose Multiplatform"
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            content()
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        trailing?.invoke()
    }
}
