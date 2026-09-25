package com.omnireader.ui.screens

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnireader.data.model.AppFile
import com.omnireader.data.model.FileType
import com.omnireader.ui.theme.*
import com.omnireader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenReader: () -> Unit
) {
    val files by viewModel.files.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadFiles()
    }

    val context = LocalContext.current
    val needsAllFilesAccess =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Explorer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadFiles() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
            if (needsAllFilesAccess) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "File access limited",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Enable \"All files access\" to scan EPUBs everywhere",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                runCatching { context.startActivity(intent) }
                            }
                        ) {
                            Text("Grant")
                        }
                    }
                }
            }

            // Filter chips
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == FileType.EPUB,
                        onClick = { viewModel.filterFiles(FileType.EPUB) },
                        label = { Text("EPUB") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FileEpub.copy(alpha = 0.15f),
                            selectedLabelColor = FileEpub,
                            selectedLeadingIconColor = FileEpub
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == FileType.SHELL,
                        onClick = { viewModel.filterFiles(FileType.SHELL) },
                        label = { Text("Shell Scripts") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Code,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FileShell.copy(alpha = 0.15f),
                            selectedLabelColor = FileShell,
                            selectedLeadingIconColor = FileShell
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == FileType.EXECUTABLE,
                        onClick = { viewModel.filterFiles(FileType.EXECUTABLE) },
                        label = { Text("Executables") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FileExe.copy(alpha = 0.15f),
                            selectedLabelColor = FileExe,
                            selectedLeadingIconColor = FileExe
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == FileType.UNKNOWN,
                        onClick = { viewModel.filterFiles(FileType.UNKNOWN) },
                        label = { Text("All") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // File count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${files.size} file${if (files.size != 1) "s" else ""} found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    selectedFilter.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // File list
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No files matching this filter",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(files) { file ->
                        FileExplorerItem(
                            file = file,
                            onClick = {
                                if (file.fileType == FileType.EPUB) {
                                    viewModel.executeFile(file)
                                    onOpenReader()
                                } else {
                                    viewModel.executeFile(file)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileExplorerItem(
    file: AppFile,
    onClick: () -> Unit
) {
    val iconTint = when (file.fileType) {
        FileType.EPUB -> FileEpub
        FileType.SHELL -> FileShell
        FileType.EXECUTABLE -> FileExe
        FileType.DIRECTORY -> FileFolder
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val bgColor = when (file.fileType) {
        FileType.EPUB -> FileEpub.copy(alpha = 0.08f)
        FileType.SHELL -> FileShell.copy(alpha = 0.08f)
        FileType.EXECUTABLE -> FileExe.copy(alpha = 0.08f)
        FileType.DIRECTORY -> FileFolder.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (file.fileType) {
                        FileType.EPUB -> Icons.Filled.MenuBook
                        FileType.SHELL -> Icons.Filled.Terminal
                        FileType.EXECUTABLE -> Icons.Filled.Memory
                        FileType.DIRECTORY -> Icons.Filled.Folder
                        else -> Icons.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = file.fileType.extension.uppercase().ifEmpty { "DIR" },
                        style = MaterialTheme.typography.labelSmall,
                        color = iconTint,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "  •  ${formatSize(file.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action button based on type
            when (file.fileType) {
                FileType.EPUB -> {
                    FilledTonalIconButton(
                        onClick = onClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Read",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                FileType.SHELL -> {
                    FilledTonalIconButton(
                        onClick = onClick,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = FileShell.copy(alpha = 0.2f)
                        )
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Execute",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                FileType.EXECUTABLE -> {
                    FilledTonalIconButton(
                        onClick = onClick,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = FileExe.copy(alpha = 0.2f)
                        )
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Run",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                else -> {}
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
