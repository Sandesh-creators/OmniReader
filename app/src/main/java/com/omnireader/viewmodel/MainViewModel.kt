package com.omnireader.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnireader.data.model.*
import com.omnireader.util.CollectionManager
import com.omnireader.util.EpubParser
import com.omnireader.util.FileMetadataCache
import com.omnireader.util.FileScanner
import com.omnireader.util.ReadingProgressManager
import com.omnireader.util.SettingsManager
import com.omnireader.util.TerminalEngine
import com.omnireader.util.TtsManager
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val epubParser = EpubParser(application)
    private val fileScanner = FileScanner(application)
    private val collectionManager = CollectionManager(application)
    private val fileMetadataCache = FileMetadataCache(application)
    private val readingProgressManager = ReadingProgressManager(application)
    private val settingsManager = SettingsManager(application)
    val ttsManager = TtsManager(application)
    val terminalEngine = TerminalEngine()

    // Book state
    private val _currentBook = MutableStateFlow<EpubBook?>(null)
    val currentBook: StateFlow<EpubBook?> = _currentBook.asStateFlow()

    private var currentBookKey: String? = null

    private val _readingProgress = MutableStateFlow(ReadingProgress())
    val readingProgress: StateFlow<ReadingProgress> = _readingProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // File explorer state
    private val _files = MutableStateFlow<List<AppFile>>(emptyList())
    val files: StateFlow<List<AppFile>> = _files.asStateFlow()

    private val _selectedFilter = MutableStateFlow(FileType.EPUB)
    val selectedFilter: StateFlow<FileType> = _selectedFilter.asStateFlow()

    // Collections state
    private val _collections = MutableStateFlow<List<EpubCollection>>(emptyList())
    val collections: StateFlow<List<EpubCollection>> = _collections.asStateFlow()

    private val _selectedCollectionBooks = MutableStateFlow<List<BookEntry>>(emptyList())
    val selectedCollectionBooks: StateFlow<List<BookEntry>> = _selectedCollectionBooks.asStateFlow()

    private val _isScanningCollections = MutableStateFlow(false)
    val isScanningCollections: StateFlow<Boolean> = _isScanningCollections.asStateFlow()

    private val _collectionScanProgress = MutableStateFlow("")
    val collectionScanProgress: StateFlow<String> = _collectionScanProgress.asStateFlow()

    private val _hasStoragePermission = MutableStateFlow(false)
    val hasStoragePermission: StateFlow<Boolean> = _hasStoragePermission.asStateFlow()

    // Settings state
    private val _fontSize = MutableStateFlow(16)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _isNightMode = MutableStateFlow(false)
    val isNightMode: StateFlow<Boolean> = _isNightMode.asStateFlow()

    private val _autoAdvanceChapters = MutableStateFlow(true)
    val autoAdvanceChapters: StateFlow<Boolean> = _autoAdvanceChapters.asStateFlow()

    private var progressSaveJob: Job? = null

    // Bound concurrent EPUB metadata extraction so a large library doesn't
    // saturate IO with dozens of parallel zip parses.
    private val metadataGate = Semaphore(4)

    init {
        try {
            ttsManager.initialize()
        } catch (_: Exception) { }

        ttsManager.setOnComplete { onTtsChapterComplete() }

        restoreSettings()
    }

    private fun restoreSettings() {
        viewModelScope.launch {
            val settings = settingsManager.load()
            _fontSize.value = settings.fontSize.coerceIn(12, 32)
            _isNightMode.value = settings.nightMode
            _autoAdvanceChapters.value = settings.autoAdvanceChapters
            try {
                ttsManager.setSpeed(settings.ttsSpeed)
                ttsManager.setPitch(settings.ttsPitch)
            } catch (_: Exception) { }
        }
    }

    fun onStoragePermissionGranted() {
        if (_hasStoragePermission.value) return
        _hasStoragePermission.value = true
        loadFiles()
        loadCollections()
        autoScanCollectionsIfNeeded()
        pruneReadingProgress()
    }

    private fun pruneReadingProgress() {
        viewModelScope.launch {
            try {
                readingProgressManager.pruneMissingFiles()
            } catch (_: Exception) { }
        }
    }

    fun loadEpub(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val key = withContext(Dispatchers.IO) {
                runCatching { fileScanner.resolvePath(uri) }.getOrDefault(uri.toString())
            }

            // Parse on the IO thread — parsing a large EPUB on the main thread
            // freezes the UI and causes an ANR (perceived as a crash).
            val result = withContext(Dispatchers.IO) {
                epubParser.parseEpub(uri)
            }

            result
                .onSuccess { book ->
                    Log.i("OmniReaderOpen", "opened '${book.title}' chapters=${book.chapters.size} key=$key")
                    _currentBook.value = book
                    currentBookKey = key
                    val saved = withContext(Dispatchers.IO) {
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
                    Log.e("OmniReaderOpen", "open failed for $uri", e)
                    _errorMessage.value = "Failed to open EPUB: ${e.message}"
                }

            _isLoading.value = false
        }
    }

    fun goToChapter(index: Int) {
        val book = _currentBook.value ?: return
        if (index in book.chapters.indices) {
            _readingProgress.value = _readingProgress.value.copy(
                chapterIndex = index,
                scrollPosition = 0f
            )
            currentBookKey?.let { scheduleProgressSave(it, book) }
        }
    }

    fun updateScrollPosition(position: Float) {
        val key = currentBookKey ?: return
        val book = _currentBook.value ?: return
        val current = _readingProgress.value
        if (current.chapterIndex !in book.chapters.indices) return
        _readingProgress.value = current.copy(scrollPosition = position)
        scheduleProgressSave(key, book)
    }

    private fun scheduleProgressSave(key: String, book: EpubBook) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
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

    fun speakCurrentChapter() {
        val book = _currentBook.value ?: return
        val chapterIndex = _readingProgress.value.chapterIndex
        if (chapterIndex in book.chapters.indices) {
            val text = epubParser.extractTextForTts(book.chapters[chapterIndex])
            ttsManager.speak(text, chapterIndex)
        }
    }

    fun pauseTts() = ttsManager.pause()
    fun resumeTts() = ttsManager.resume()
    fun stopTts() = ttsManager.stop()
    fun setTtsSpeed(speed: Float) {
        ttsManager.setSpeed(speed)
        viewModelScope.launch { settingsManager.saveTtsSpeed(speed) }
    }
    fun setTtsPitch(pitch: Float) {
        ttsManager.setPitch(pitch)
        viewModelScope.launch { settingsManager.saveTtsPitch(pitch) }
    }

    fun setAutoAdvanceChapters(enabled: Boolean) {
        _autoAdvanceChapters.value = enabled
        viewModelScope.launch { settingsManager.saveAutoAdvanceChapters(enabled) }
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

    // File management
    fun loadFiles() {
        viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                try {
                    fileScanner.scanForFiles()
                } catch (e: Exception) {
                    Log.e("OmniReaderScan", "scanForFiles failed", e)
                    emptyList()
                }
            }
            Log.i("OmniReaderScan", "scanned ${scanned.size} files")
            _files.value = enrichFilesWithMetadata(scanned)
        }
    }

    fun filterFiles(type: FileType) {
        _selectedFilter.value = type
        viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                if (type == FileType.UNKNOWN) {
                    fileScanner.scanForFiles()
                } else {
                    fileScanner.scanByType(type)
                }
            }
            _files.value = enrichFilesWithMetadata(scanned)
        }
    }

    // Applies cached metadata (titles/covers) first so the UI renders fast,
    // then extracts anything still missing in the background.
    private suspend fun enrichFilesWithMetadata(files: List<AppFile>): List<AppFile> {
        val cache = fileMetadataCache.loadAll()

        val enriched: MutableList<AppFile> = files.map { file ->
            if (file.fileType != FileType.EPUB) return@map file
            val meta = cache[file.path]
            // Verify cached cover file still exists on disk
            val coverUriValid = meta?.coverUri?.let { Uri.parse(it) }?.path?.let { java.io.File(it).exists() } ?: false
            if (meta != null && meta.lastModified == file.lastModified && (meta.coverUri == null || coverUriValid)) {
                file.copy(
                    name = meta.title ?: file.name,
                    coverUri = meta.coverUri?.let { Uri.parse(it) }
                )
            } else {
                file
            }
        }.toMutableList()

        val missing = files.filter { it.fileType == FileType.EPUB &&
                (cache[it.path] == null || cache[it.path]!!.coverUri == null || 
                 cache[it.path]!!.coverUri?.let { Uri.parse(it).path?.let { !java.io.File(it).exists() } } == true) }

        // Extract missing metadata in parallel (bounded) instead of one file at a
        // time; each extraction re-parses the whole EPUB zip, so serial handling
        // made large libraries crawl while the Home grid was waiting on covers.
        val missingMeta: List<Pair<AppFile, EpubMetadata?>> = coroutineScope {
            missing.map { file ->
                async(Dispatchers.IO) {
                    metadataGate.withPermit {
                        file to runCatching {
                            epubParser.extractMetadata(file.uri, file.path).getOrNull()
                        }.getOrNull()
                    }
                }
            }.awaitAll()
        }

        for ((file, meta) in missingMeta) {
            if (meta?.coverUri != null || meta?.title != null) {
                runCatching {
                    fileMetadataCache.put(
                        file.path,
                        CachedFileMeta(
                            title = meta.title,
                            author = meta.author,
                            coverUri = meta.coverUri?.toString(),
                            lastModified = file.lastModified
                        )
                    )
                }
                val idx = enriched.indexOfFirst { it.path == file.path }
                if (idx >= 0) {
                    enriched[idx] = enriched[idx].copy(
                        name = meta.title ?: enriched[idx].name,
                        coverUri = meta.coverUri ?: enriched[idx].coverUri
                    )
                }
            }
        }

        return enriched
    }

    fun executeFile(file: AppFile) {
        when (file.fileType) {
            FileType.EPUB -> loadEpub(file.uri)
            FileType.SHELL -> terminalEngine.executeShellScript(file.path)
            FileType.EXECUTABLE -> terminalEngine.executeExeViaWine(file.path)
            FileType.DIRECTORY -> {}
            FileType.UNKNOWN -> {}
        }
    }

    // A file picked via Storage Access Framework arrives as a content:// Uri whose
    // "path" is NOT a real filesystem path, so it must be copied into our cache
    // before the terminal engine can run it. Otherwise every picked script fails
    // with "File not found" on all devices, Xiaomi included.
    fun executePickedFile(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val ext = withContext(Dispatchers.IO) {
                runCatching {
                    val name = app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                    name?.substringAfterLast('.', "")?.lowercase().orEmpty()
                }.getOrElse { uri.path?.substringAfterLast('.', "")?.lowercase().orEmpty() }
            }

            val (ok, path) = withContext(Dispatchers.IO) {
                val safeExt = ext.ifBlank { "sh" }
                val tmp = java.io.File(app.cacheDir, "picked_${System.currentTimeMillis()}.$safeExt")
                val written = runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } != null
                }.getOrDefault(false)
                written to tmp.absolutePath
            }

            if (!ok) {
                _errorMessage.value = "Failed to read the selected file"
                return@launch
            }

            when (ext) {
                "exe" -> terminalEngine.executeExeViaWine(path)
                else -> terminalEngine.executeShellScript(path)
            }
        }
    }

    fun openEpubByPath(path: String) {
        loadEpub(Uri.fromFile(java.io.File(path)))
    }

    // Collection management
    fun loadCollections() {
        viewModelScope.launch {
            _collections.value = collectionManager.loadCollections()
        }
    }

    fun scanAndGroupCollections() {
        if (_isScanningCollections.value) return

        viewModelScope.launch {
            _isScanningCollections.value = true
            _collectionScanProgress.value = "Extracting metadata..."
            val result = withContext(Dispatchers.IO) {
                collectionManager.autoGroupByAuthor()
            }
            _collections.value = result
            _collectionScanProgress.value = ""
            _isScanningCollections.value = false
        }
    }

    fun autoScanCollectionsIfNeeded() {
        viewModelScope.launch {
            try {
                val existing = collectionManager.loadCollections()
                val updated = withContext(Dispatchers.IO) {
                    collectionManager.autoGroupByAuthor()
                }
                if (updated != existing) _collections.value = updated
            } catch (_: Exception) { }
        }
    }

    fun rescanCollections() {
        scanAndGroupCollections()
        pruneReadingProgress()
        loadFiles()
    }

    fun openCollection(collectionId: String) {
        viewModelScope.launch {
            _selectedCollectionBooks.value = collectionManager.getCollectionBooks(collectionId)
        }
    }

    fun renameCollection(collectionId: String, newName: String) {
        viewModelScope.launch {
            collectionManager.renameCollection(collectionId, newName)
            loadCollections()
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            collectionManager.deleteCollection(collectionId)
            loadCollections()
        }
    }

    fun setFontSize(size: Int) {
        _fontSize.value = size.coerceIn(12, 32)
        viewModelScope.launch {
            settingsManager.saveFontSize(_fontSize.value)
        }
    }

    fun setNightMode(enabled: Boolean) {
        _isNightMode.value = enabled
        viewModelScope.launch {
            settingsManager.saveNightMode(enabled)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
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
        terminalEngine.destroy()
        super.onCleared()
    }
}