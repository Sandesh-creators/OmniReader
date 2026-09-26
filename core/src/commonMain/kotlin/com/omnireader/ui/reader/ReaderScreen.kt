package com.omnireader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.omnireader.data.model.EpubChapter
import com.omnireader.data.model.TtsState
import com.omnireader.ui.components.CoverImage
import com.omnireader.viewmodel.AppController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    controller: AppController,
    onBack: () -> Unit,
    onOpenFile: () -> Unit
) {
    val currentBook by controller.currentBook.collectAsState()
    val progress by controller.readingProgress.collectAsState()
    val isLoading by controller.isLoading.collectAsState()
    val errorMessage by controller.errorMessage.collectAsState()
    val fontSize by controller.fontSize.collectAsState()
    val isNightMode by controller.isNightMode.collectAsState()
    val ttsState by controller.ttsState.collectAsState()
    val highlightedSentence by controller.highlightedSentence.collectAsState()

    var showChapterDrawer by remember { mutableStateOf(false) }
    var showTtsControls by remember { mutableStateOf(false) }
    var showFontSettings by remember { mutableStateOf(false) }

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
                        IconButton(onClick = { showFontSettings = !showFontSettings }) {
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
            AnimatedVisibility(visible = showTtsControls && currentBook != null) {
                TtsControlPanel(
                    ttsState = ttsState,
                    onPlay = {
                        if (ttsState.isPlaying) controller.pauseTts()
                        else if (ttsState.isPaused) controller.resumeTts()
                        else controller.speakCurrentChapter()
                    },
                    onStop = { controller.stopTts() },
                    onSpeedChange = { controller.setTtsSpeed(it) },
                    onPitchChange = { controller.setTtsPitch(it) }
                )
            }

            AnimatedVisibility(visible = showFontSettings) {
                FontSettingsPanel(
                    fontSize = fontSize,
                    isNightMode = isNightMode,
                    onFontSizeChange = { controller.setFontSize(it) },
                    onNightModeChange = { controller.setNightMode(it) }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    currentBook == null -> {
                        EmptyReaderState(onOpenFile = onOpenFile)
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
                                    onScrollPositionChange = { controller.updateScrollPosition(it) },
                                    coverSource = currentBook!!.coverSource
                                )
                            }
                        }
                    }
                }
            }

            if (currentBook != null) {
                ChapterNavigationBar(
                    currentChapter = progress.chapterIndex,
                    totalChapters = currentBook!!.chapters.size,
                    onPrevious = { controller.goToChapter(progress.chapterIndex - 1) },
                    onNext = { controller.goToChapter(progress.chapterIndex + 1) },
                    onOpenChapters = { showChapterDrawer = true }
                )
            }
        }

        errorMessage?.let { error ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { controller.clearError() }) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text(error)
            }
        }
    }

    if (showChapterDrawer && currentBook != null) {
        ChapterDrawer(
            chapters = currentBook!!.chapters,
            currentChapter = progress.chapterIndex,
            onChapterSelected = { index ->
                controller.goToChapter(index)
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
    coverSource: String? = null
) {
    val listState = rememberLazyListState()

    var readyToReport by remember { mutableStateOf(false) }

    val sentences = remember(chapter.plainText) {
        chapter.plainText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
    }

    LaunchedEffect(chapter.index) {
        readyToReport = false
        onScrollPositionChange(0f)
        val target = initialScrollPosition.toInt().coerceIn(0, sentences.lastIndex.coerceAtLeast(0))
        if (target > 0) {
            runCatching { listState.scrollToItem(target) }
        }
        readyToReport = true
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (readyToReport) onScrollPositionChange(index.toFloat())
            }
    }

    val backgroundColor = if (isNightMode) Color(0xFF15151F) else Color(0xFFFDFCF9)
    val textColor = if (isNightMode) Color(0xFFE0E0E0) else Color(0xFF1A1A1A)
    val highlightColor = animateColorAsState(
        targetValue = if (highlightedSentence >= 0) MaterialTheme.colorScheme.primary else textColor,
        animationSpec = tween(300)
    ).value
    val highlightBgColor = animateColorAsState(
        targetValue = if (highlightedSentence >= 0) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(300)
    ).value

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp)
    ) {
        coverSource?.let {
            item(key = "cover") {
                CoverImage(
                    coverSource = it,
                    placeholderTitle = chapter.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.padding(bottom = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }

        item(key = "title") {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        itemsIndexed(
            items = sentences,
            key = { index, _ -> index }
        ) { index, sentence ->
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

                IconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors()
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
        val targetIndex = filtered.indexOfFirst { it.index == currentChapter }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .height(560.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 8.dp)
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

                Spacer(modifier = Modifier.height(8.dp))

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
                                        color = if (isCurrent) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
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
}
