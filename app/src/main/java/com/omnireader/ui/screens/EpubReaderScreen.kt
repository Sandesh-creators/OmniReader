package com.omnireader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnireader.data.model.EpubChapter
import com.omnireader.data.model.TtsState
import com.omnireader.ui.components.CoverImage
import com.omnireader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val currentBook by viewModel.currentBook.collectAsState()
    val progress by viewModel.readingProgress.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val isNightMode by viewModel.isNightMode.collectAsState()
    val ttsState by viewModel.ttsManager.state.collectAsState()
    val highlightedSentence by viewModel.ttsManager.onSentenceHighlight.collectAsState()

    var showChapterDrawer by remember { mutableStateOf(false) }
    var showTtsControls by remember { mutableStateOf(false) }
    var showFontSettings by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadEpub(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentBook?.title ?: "EPUB Reader",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (currentBook != null) {
                        IconButton(onClick = { showChapterDrawer = true }) {
                            Icon(Icons.Filled.List, contentDescription = "Chapters")
                        }
                        IconButton(onClick = { showFontSettings = true }) {
                            Icon(Icons.Filled.TextFormat, contentDescription = "Font settings")
                        }
                        IconButton(onClick = { showTtsControls = !showTtsControls }) {
                            Icon(
                                if (ttsState.isPlaying) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = "TTS"
                            )
                        }
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
            // TTS Controls
            AnimatedVisibility(visible = showTtsControls && currentBook != null) {
                TtsControlPanel(
                    ttsState = ttsState,
                    onPlay = {
                        if (ttsState.isPlaying) viewModel.pauseTts()
                        else if (ttsState.isPaused) viewModel.resumeTts()
                        else viewModel.speakCurrentChapter()
                    },
                    onStop = { viewModel.stopTts() },
                    onSpeedChange = { viewModel.setTtsSpeed(it) },
                    onPitchChange = { viewModel.setTtsPitch(it) }
                )
            }

            // Font settings panel
            AnimatedVisibility(visible = showFontSettings) {
                FontSettingsPanel(
                    fontSize = fontSize,
                    isNightMode = isNightMode,
                    onFontSizeChange = { viewModel.setFontSize(it) },
                    onNightModeChange = { viewModel.setNightMode(it) }
                )
            }

            // Main content
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    currentBook == null -> {
                        EmptyReaderState(
                            onOpenFile = {
                                filePickerLauncher.launch(
                                    arrayOf("application/epub+zip")
                                )
                            }
                        )
                    }
                    else -> {
                        val chapter = currentBook!!.chapters.getOrNull(progress.chapterIndex)
                        if (chapter != null) {
                            AnimatedContent(
                                targetState = chapter.index,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(260)) togetherWith fadeOut(animationSpec = tween(180))
                                }
                            ) { targetChapterIndex ->
                                val currentChapter = currentBook!!.chapters.getOrNull(targetChapterIndex) ?: chapter
                                ReaderContent(
                                    chapter = currentChapter,
                                    fontSize = fontSize,
                                    isNightMode = isNightMode,
                                    highlightedSentence = highlightedSentence,
                                    initialScrollPosition = progress.scrollPosition,
                                    onScrollPositionChange = { viewModel.updateScrollPosition(it) },
                                    coverUri = currentBook!!.coverUri
                                )
                            }
                        }
                    }
                }
            }

            // Chapter navigation
            if (currentBook != null) {
                ChapterNavigationBar(
                    currentChapter = progress.chapterIndex,
                    totalChapters = currentBook!!.chapters.size,
                    onPrevious = { viewModel.goToChapter(progress.chapterIndex - 1) },
                    onNext = { viewModel.goToChapter(progress.chapterIndex + 1) },
                    onOpenChapters = { showChapterDrawer = true }
                )
            }
        }

        // Error snackbar
        errorMessage?.let { error ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text(error)
            }
        }
    }

    // Chapter drawer
    if (showChapterDrawer && currentBook != null) {
        ChapterDrawer(
            chapters = currentBook!!.chapters,
            currentChapter = progress.chapterIndex,
            onChapterSelected = { index ->
                viewModel.goToChapter(index)
                showChapterDrawer = false
            },
            onDismiss = { showChapterDrawer = false }
        )
    }
}

@Composable
fun EmptyReaderState(onOpenFile: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No EPUB file loaded",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Open an EPUB file to start reading",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onOpenFile) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open EPUB File")
            }
        }
    }
}

@Composable
fun ReaderContent(
    chapter: EpubChapter,
    fontSize: Int,
    isNightMode: Boolean,
    highlightedSentence: Int,
    initialScrollPosition: Float,
    onScrollPositionChange: (Float) -> Unit,
    coverUri: android.net.Uri? = null
) {
    val scrollState = rememberScrollState()

    // Suppress the first scroll report until the saved position is restored.
    var readyToReport by remember { mutableStateOf(false) }

    LaunchedEffect(chapter.index) {
        readyToReport = false
        onScrollPositionChange(0f)
        val target = initialScrollPosition.toInt().coerceAtLeast(0)
        if (target > 0) {
            runCatching { scrollState.scrollTo(target) }
        }
        readyToReport = true
    }

    LaunchedEffect(scrollState.value) {
        if (readyToReport) {
            onScrollPositionChange(scrollState.value.toFloat())
        }
    }

    val backgroundColor = if (isNightMode) Color(0xFF15151F) else Color(0xFFFDFCF9)
    val textColor = if (isNightMode) Color(0xFFE0E0E0) else Color(0xFF1A1A1A)
    val highlightColor = animateColorAsState(
        targetValue = if (highlightedSentence >= 0) MaterialTheme.colorScheme.primary else textColor,
        animationSpec = tween(300)
    ).value
    val highlightBgColor = animateColorAsState(
        targetValue = if (highlightedSentence >= 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
        animationSpec = tween(300)
    ).value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(scrollState)
            .padding(20.dp)
            .padding(bottom = 24.dp)
    ) {
        // Cover header
        coverUri?.let { uri ->
            CoverImage(
                coverUri = uri,
                placeholderTitle = chapter.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .padding(bottom = 24.dp)
            )
            Divider(
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // Chapter title
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Divider(
            modifier = Modifier.padding(bottom = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Chapter content
        val sentences = remember(chapter.plainText) {
            chapter.plainText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        }

        sentences.forEachIndexed { index, sentence ->
            val isHighlighted = index == highlightedSentence

            Text(
                text = sentence,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.6).sp,
                    fontFamily = FontFamily.Serif
                ),
                color = if (isHighlighted) highlightColor else textColor,
                modifier = Modifier
                    .then(
                        if (isHighlighted) {
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(highlightBgColor)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        } else Modifier
                    )
                    .padding(vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun TtsControlPanel(
    ttsState: TtsState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Text-to-Speech",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }

                FilledIconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        when {
                            ttsState.isPlaying -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = if (ttsState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Speed: ${"%.1f".format(ttsState.speed)}x", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = ttsState.speed,
                    onValueChange = onSpeedChange,
                    valueRange = 0.5f..2.0f,
                    steps = 6,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pitch: ${"%.1f".format(ttsState.pitch)}x", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = ttsState.pitch,
                    onValueChange = onPitchChange,
                    valueRange = 0.5f..2.0f,
                    steps = 6,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
fun FontSettingsPanel(
    fontSize: Int,
    isNightMode: Boolean,
    onFontSizeChange: (Int) -> Unit,
    onNightModeChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Display Settings", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Font Size: ${fontSize}sp", style = MaterialTheme.typography.bodyMedium)
                Row {
                    IconButton(onClick = { onFontSizeChange(fontSize - 1) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Decrease font")
                    }
                    IconButton(onClick = { onFontSizeChange(fontSize + 1) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Increase font")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Night Mode", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = isNightMode,
                    onCheckedChange = onNightModeChange
                )
            }
        }
    }
}

@Composable
fun ChapterNavigationBar(
    currentChapter: Int,
    totalChapters: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenChapters: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onPrevious,
                enabled = currentChapter > 0
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                Text("Previous")
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenChapters)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Chapter ${currentChapter + 1} of $totalChapters",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "View chapters",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            TextButton(
                onClick = onNext,
                enabled = currentChapter < totalChapters - 1
            ) {
                Text("Next")
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterDrawer(
    chapters: List<EpubChapter>,
    currentChapter: Int,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val filtered = remember(chapters, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            chapters
        } else {
            chapters.filter { chapter ->
                chapter.title.lowercase().contains(q) ||
                    "chapter ${chapter.index + 1}".contains(q)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (currentChapter in chapters.indices) {
            listState.animateScrollToItem(currentChapter)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Chapters",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "${chapters.size} total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search chapters...") },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No chapters found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(filtered, key = { _, chapter -> chapter.index }) { _, chapter ->
                        val isCurrent = chapter.index == currentChapter
                        ListItem(
                            headlineContent = {
                                Text(chapter.title, maxLines = 1)
                            },
                            leadingContent = {
                                Text(
                                    "${chapter.index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isCurrent)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                if (isCurrent) {
                                    Text(
                                        "Current",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isCurrent) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            RoundedCornerShape(12.dp)
                                        )
                                    } else Modifier
                                )
                                .clickable { onChapterSelected(chapter.index) }
                        )
                    }
                }
            }
        }
    }
}
