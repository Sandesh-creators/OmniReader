package com.omnireader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnireader.data.model.LineType
import com.omnireader.data.model.TerminalLine
import com.omnireader.ui.theme.*
import com.omnireader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val session by viewModel.terminalEngine.session.collectAsState()
    var commandInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showExeWarning by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.executePickedFile(it) }
    }

    LaunchedEffect(session.output.size) {
        if (session.output.isNotEmpty()) {
            listState.animateScrollToItem(session.output.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.terminalEngine.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear")
                    }
                    IconButton(onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/x-shellscript",
                                "application/x-msdownload",
                                "text/plain"
                            )
                        )
                    }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = "Open script")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Architecture warning for .exe
            if (showExeWarning) {
                ExeCompatibilityWarning(
                    onDismiss = { showExeWarning = false },
                    onProceed = {
                        showExeWarning = false
                        viewModel.terminalEngine.executeExeViaWine("")
                    }
                )
            }

            // Terminal output
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TerminalBg)
                    .padding(8.dp)
            ) {
                if (session.output.isEmpty()) {
                    TerminalWelcome()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(session.output) { line ->
                            TerminalLineItem(line)
                        }
                    }
                }

                // Running indicator
                if (session.isRunning) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TerminalGreen
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Running...",
                            color = TerminalAmber,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Command input
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$ ",
                        color = TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp)),
                        placeholder = {
                            Text(
                                "Type command...",
                                fontFamily = FontFamily.Monospace,
                                color = Color.Gray
                            )
                        },
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TerminalGreen,
                            cursorColor = TerminalGreen
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (session.isRunning) {
                        IconButton(
                            onClick = { viewModel.terminalEngine.cancel() },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (commandInput.isNotBlank()) {
                                    viewModel.terminalEngine.executeCommand(commandInput)
                                    commandInput = ""
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = "Execute")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalWelcome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "OmniReader Terminal",
            color = TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Type a command or open a script file",
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Supported: .sh scripts, .exe (via Wine/Proot)",
            color = Color.Gray.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
fun TerminalLineItem(line: TerminalLine) {
    val color = when (line.type) {
        LineType.INPUT -> TerminalGreen
        LineType.OUTPUT -> Color(0xFFB0B0B0)
        LineType.ERROR -> Color(0xFFFF6B6B)
        LineType.SYSTEM -> TerminalAmber
    }

    val prefix = when (line.type) {
        LineType.INPUT -> "$ "
        LineType.ERROR -> "✗ "
        LineType.SYSTEM -> "● "
        else -> "  "
    }

    Text(
        text = "$prefix${line.content}",
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

@Composable
fun ExeCompatibilityWarning(
    onDismiss: () -> Unit,
    onProceed: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = FileExe) },
        title = { Text("Windows Executable Warning") },
        text = {
            Column {
                Text(
                    "Running .exe files on Android requires a compatibility layer " +
                            "(Wine, Box86, or PRoot).",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "• Architecture: ARM (most Android devices are not x86)",
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    "• Performance may be significantly reduced",
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    "• Not all Windows programs are compatible",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onProceed) {
                Text("Proceed Anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
