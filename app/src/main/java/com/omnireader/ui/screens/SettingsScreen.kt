package com.omnireader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnireader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val fontSize by viewModel.fontSize.collectAsState()
    val isNightMode by viewModel.isNightMode.collectAsState()
    val ttsState by viewModel.ttsManager.state.collectAsState()
    val autoAdvanceChapters by viewModel.autoAdvanceChapters.collectAsState()

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
            // Appearance Section
            SettingsSection(title = "Appearance") {
                SettingsItem(
                    icon = Icons.Filled.TextFormat,
                    title = "Default Font Size",
                    subtitle = "${fontSize}sp",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.setFontSize(fontSize - 1) }) {
                                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
                            }
                            Text("$fontSize", modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = { viewModel.setFontSize(fontSize + 1) }) {
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
                            onCheckedChange = { viewModel.setNightMode(it) }
                        )
                    }
                )
            }

            // TTS Section
            SettingsSection(title = "Text-to-Speech") {
                SettingsItem(
                    icon = Icons.Filled.Speed,
                    title = "Playback Speed",
                    subtitle = "${"%.1f".format(ttsState.speed)}x",
                    trailing = {
                        Slider(
                            value = ttsState.speed,
                            onValueChange = { viewModel.setTtsSpeed(it) },
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
                            onValueChange = { viewModel.setTtsPitch(it) },
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
                            onCheckedChange = { viewModel.setAutoAdvanceChapters(it) }
                        )
                    }
                )
            }

            // Execution Section
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

            // About Section
            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Filled.Info,
                    title = "OmniReader & TaskRunner",
                    subtitle = "Version 1.0.0"
                )

                SettingsItem(
                    icon = Icons.Filled.Code,
                    title = "Build Info",
                    subtitle = "Kotlin 1.9.22 • Compose BOM 2024.01"
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
