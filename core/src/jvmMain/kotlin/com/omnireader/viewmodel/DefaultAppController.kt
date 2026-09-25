package com.omnireader.viewmodel

import com.omnireader.data.model.AppFile
import com.omnireader.data.model.BookEntry
import com.omnireader.data.model.CachedFileMeta
import com.omnireader.data.model.EpubBook
import com.omnireader.data.model.EpubCollection
import com.omnireader.data.model.FileType
import com.omnireader.data.model.ReadingProgress
import com.omnireader.data.model.SavedProgress
import com.omnireader.data.model.TtsState
import com.omnireader.util.BookSourceProvider
import com.omnireader.util.CollectionManager
import com.omnireader.util.EpubParser
import com.omnireader.util.FileMetadataCache
import com.omnireader.util.ReadingProgressManager
import com.omnireader.util.SettingsManager
import com.omnireader.util.TtsManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class DefaultAppController(
    private val scope: CoroutineScope,
    private val epubParser: EpubParser,
    private val sourceProvider: BookSourceProvider,
    private val collectionManager: CollectionManager,
    private val fileMetadataCache: FileMetadataCache,
    private val readingProgressManager: ReadingProgressManager,
    private val settingsManager: SettingsManager,
    val ttsManager: TtsManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> }
) : AppController {

    private val _currentBook = MutableStateFlow<EpubBook?>(null)
    override val currentBook: StateFlow<EpubBook?> = _currentBook.asStateFlow()

    private var currentBookKey: String? = null

    private val _readingProgress = MutableStateFlow(ReadingProgress())
    override val readingProgress: StateFlow<ReadingProgress> = _readingProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _files = MutableStateFlow<List<AppFile>>(emptyList())
    override val files: StateFlow<List<AppFile>> = _files.asStateFlow()

    private val _selectedFilter = MutableStateFlow(FileType.EPUB)
    override val selectedFilter: StateFlow<FileType> = _selectedFilter.asStateFlow()

    private val _collections = MutableStateFlow<List<EpubCollection>>(emptyList())
    override val collections: StateFlow<List<EpubCollection>> = _collections.asStateFlow()

    private val _selectedCollectionBooks = MutableStateFlow<List<BookEntry>>(emptyList())
    override val selectedCollectionBooks: StateFlow<List<BookEntry>> = _selectedCollectionBooks.asStateFlow()

    private val _isScanningCollections = MutableStateFlow(false)
    override val isScanningCollections: StateFlow<Boolean> = _isScanningCollections.asStateFlow()

    private val _collectionScanProgress = MutableStateFlow("")
    override val collectionScanProgress: StateFlow<String> = _collectionScanProgress.asStateFlow()

    private val _fontSize = MutableStateFlow(16)
    override val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _isNightMode = MutableStateFlow(false)
    override val isNightMode: StateFlow<Boolean> = _isNightMode.asStateFlow()

    private val _autoAdvanceChapters = MutableStateFlow(true)
    override val autoAdvanceChapters: StateFlow<Boolean> = _autoAdvanceChapters.asStateFlow()

    override val ttsState: StateFlow<TtsState> = ttsManager.state
    override val highlightedSentence: StateFlow<Int> = ttsManager.onSentenceHighlight

    private var progressSaveJob: Job? = null

    private val metadataGate = Semaphore(4)

    private var started = false

    fun initialize() {
        runCatching { ttsManager.initialize() }
        ttsManager.setOnComplete { onTtsChapterComplete() }
    }

    override fun start() {
        if (started) return
        started = true
        restoreSettings()
        loadFiles()
        loadCollections()
        autoScanCollectionsIfNeeded()
        scope.launch { runCatching { readingProgressManager.pruneMissingFiles() } }
    }

    private fun restoreSettings() {
        scope.launch {
            val settings = settingsManager.load()
            _fontSize.value = settings.fontSize.coerceIn(12, 32)
            _isNightMode.value = settings.nightMode
            _autoAdvanceChapters.value = settings.autoAdvanceChapters
            runCatching {
                ttsManager.setSpeed(settings.ttsSpeed)
                ttsManager.setPitch(settings.ttsPitch)
            }
        }
    }

    override fun openEpub(source: String) {
        scope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val key = withContext(ioDispatcher) {
                runCatching { sourceProvider.resolvePath(source) }.getOrDefault(source)
            }

            val result = withContext(ioDispatcher) {
                epubParser.parseEpub(source)
            }

            result
                .onSuccess { book ->
                    logger("OmniReaderOpen: opened '${book.title}' chapters=${book.chapters.size} key=$key", null)
                    _currentBook.value = book
                    currentBookKey = key
                    val saved = withContext(ioDispatcher) {
                        readingProgressManager.loadFor(key)
                    }
                    _readingProgress.value = if (saved != null) {
                        ReadingProgress(
                            chapterIndex = saved.chapterIndex.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0)),
                            scrollPosition = saved.scrollPosition
                        )
                    } else {
                        ReadingProgress()
                    }
                }
                .onFailure { e ->
                    logger("OmniReaderOpen: open failed for $source", e)
                    _errorMessage.value = "Failed to open EPUB: ${e.message}"
                }

            _isLoading.value = false
        }
    }

    override fun goToChapter(index: Int) {
        val book = _currentBook.value ?: return
        if (index in book.chapters.indices) {
            _readingProgress.value = _readingProgress.value.copy(
                chapterIndex = index,
                scrollPosition = 0f
            )
            currentBookKey?.let { scheduleProgressSave(it, book) }
        }
    }

    override fun updateScrollPosition(position: Float) {
        val key = currentBookKey ?: return
        val book = _currentBook.value ?: return
        val current = _readingProgress.value
        if (current.chapterIndex !in book.chapters.indices) return
        _readingProgress.value = current.copy(scrollPosition = position)
        scheduleProgressSave(key, book)
    }

    private fun scheduleProgressSave(key: String, book: EpubBook) {
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            delay(500)
            val p = _readingProgress.value
            if (p.chapterIndex in book.chapters.indices) {
                runCatching {
                    readingProgressManager.saveFor(
                        key,
                        SavedProgress(chapterIndex = p.chapterIndex, scrollPosition = p.scrollPosition)
                    )
                }
            }
        }
    }

    override fun speakCurrentChapter() {
        val book = _currentBook.value ?: return
        val chapterIndex = _readingProgress.value.chapterIndex
        if (chapterIndex in book.chapters.indices) {
            val text = epubParser.extractTextForTts(book.chapters[chapterIndex])
            ttsManager.speak(text, chapterIndex)
        }
    }

    override fun pauseTts() = ttsManager.pause()

    override fun resumeTts() = ttsManager.resume()

    override fun stopTts() = ttsManager.stop()

    override fun setTtsSpeed(speed: Float) {
        ttsManager.setSpeed(speed)
        scope.launch { settingsManager.saveTtsSpeed(speed) }
    }

    override fun setTtsPitch(pitch: Float) {
        ttsManager.setPitch(pitch)
        scope.launch { settingsManager.saveTtsPitch(pitch) }
    }

    override fun setAutoAdvanceChapters(enabled: Boolean) {
        _autoAdvanceChapters.value = enabled
        scope.launch { settingsManager.saveAutoAdvanceChapters(enabled) }
    }

    private fun onTtsChapterComplete() {
        if (!_autoAdvanceChapters.value) return
        val book = _currentBook.value ?: return
        val currentIndex = _readingProgress.value.chapterIndex
        val nextChapterIndex = (currentIndex + 1..book.chapters.lastIndex)
            .firstOrNull { !book.chapters[it].isFrontMatter }
        nextChapterIndex?.let { next ->
            goToChapter(next)
            speakCurrentChapter()
        }
    }

    override fun loadFiles() {
        scope.launch {
            val scanned = withContext(ioDispatcher) {
                try {
                    sourceProvider.scanAll()
                } catch (e: Exception) {
                    logger("OmniReaderScan: scanAll failed", e)
                    emptyList()
                }
            }
            logger("OmniReaderScan: scanned ${scanned.size} files", null)
            _files.value = enrichFilesWithMetadata(scanned)
        }
    }

    override fun filterFiles(type: FileType) {
        _selectedFilter.value = type
        scope.launch {
            val scanned = withContext(ioDispatcher) {
                if (type == FileType.UNKNOWN) {
                    sourceProvider.scanAll()
                } else {
                    sourceProvider.scan(type)
                }
            }
            _files.value = enrichFilesWithMetadata(scanned)
        }
    }

    private suspend fun enrichFilesWithMetadata(files: List<AppFile>): List<AppFile> {
        val cache = fileMetadataCache.loadAll()

        val enriched: MutableList<AppFile> = files.map { file ->
            if (file.fileType != FileType.EPUB) return@map file
            val meta = cache[file.path]
            val coverValid = meta?.coverSource?.let { File(it).exists() } ?: false
            if (meta != null && meta.lastModified == file.lastModified && (meta.coverSource == null || coverValid)) {
                file.copy(
                    name = meta.title ?: file.name,
                    coverSource = meta.coverSource
                )
            } else {
                file
            }
        }.toMutableList()

        val missing = files.filter { file ->
            if (file.fileType != FileType.EPUB) return@filter false
            val meta = cache[file.path]
            meta == null ||
                meta.title == null ||
                meta.coverSource == null ||
                !File(meta.coverSource).exists()
        }

        val missingMeta: List<Pair<AppFile, com.omnireader.data.model.EpubMetadata?>> = coroutineScope {
            missing.map { file ->
                async(ioDispatcher) {
                    metadataGate.withPermit {
                        file to runCatching {
                            epubParser.extractMetadata(file.source, file.path).getOrNull()
                        }.getOrNull()
                    }
                }
            }.awaitAll()
        }

        for ((file, meta) in missingMeta) {
            if (meta?.coverSource != null || meta?.title != null) {
                runCatching {
                    fileMetadataCache.put(
                        file.path,
                        CachedFileMeta(
                            title = meta.title,
                            author = meta.author,
                            coverSource = meta.coverSource,
                            lastModified = file.lastModified
                        )
                    )
                }
                val idx = enriched.indexOfFirst { it.path == file.path }
                if (idx >= 0) {
                    enriched[idx] = enriched[idx].copy(
                        name = meta.title ?: enriched[idx].name,
                        coverSource = meta.coverSource ?: enriched[idx].coverSource
                    )
                }
            }
        }

        return enriched
    }

    override fun loadCollections() {
        scope.launch {
            _collections.value = collectionManager.loadCollections()
        }
    }

    override fun scanAndGroupCollections() {
        if (_isScanningCollections.value) return

        scope.launch {
            _isScanningCollections.value = true
            _collectionScanProgress.value = "Extracting metadata..."
            val result = withContext(ioDispatcher) {
                collectionManager.autoGroupByAuthor()
            }
            _collections.value = result
            _collectionScanProgress.value = ""
            _isScanningCollections.value = false
        }
    }

    override fun autoScanCollectionsIfNeeded() {
        scope.launch {
            runCatching {
                val existing = collectionManager.loadCollections()
                val updated = withCatchingContext { collectionManager.autoGroupByAuthor() }
                if (updated != existing) _collections.value = updated
            }
        }
    }

    private suspend fun <T> withCatchingContext(block: suspend () -> T): T =
        withContext(ioDispatcher) { block() }

    override fun rescanCollections() {
        scanAndGroupCollections()
        scope.launch { runCatching { readingProgressManager.pruneMissingFiles() } }
        loadFiles()
    }

    override fun openCollection(collectionId: String) {
        scope.launch {
            _selectedCollectionBooks.value = collectionManager.getCollectionBooks(collectionId)
        }
    }

    override fun renameCollection(collectionId: String, newName: String) {
        scope.launch {
            collectionManager.renameCollection(collectionId, newName)
            loadCollections()
        }
    }

    override fun deleteCollection(collectionId: String) {
        scope.launch {
            collectionManager.deleteCollection(collectionId)
            loadCollections()
        }
    }

    override fun setFontSize(size: Int) {
        _fontSize.value = size.coerceIn(12, 32)
        scope.launch {
            settingsManager.saveFontSize(_fontSize.value)
        }
    }

    override fun setNightMode(enabled: Boolean) {
        _isNightMode.value = enabled
        scope.launch {
            settingsManager.saveNightMode(enabled)
        }
    }

    override fun clearError() {
        _errorMessage.value = null
    }

    override fun dispose() {
        currentBookKey?.let { key ->
            val p = _readingProgress.value
            val book = _currentBook.value
            if (book != null && p.chapterIndex in book.chapters.indices) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        readingProgressManager.saveFor(
                            key,
                            SavedProgress(chapterIndex = p.chapterIndex, scrollPosition = p.scrollPosition)
                        )
                    }
                }
            }
        }
        ttsManager.shutdown()
    }
}
