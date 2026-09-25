package com.omnireader.viewmodel

import com.omnireader.data.model.AppFile
import com.omnireader.data.model.BookEntry
import com.omnireader.data.model.EpubBook
import com.omnireader.data.model.EpubCollection
import com.omnireader.data.model.FileType
import com.omnireader.data.model.ReadingProgress
import com.omnireader.data.model.TtsState
import kotlinx.coroutines.flow.StateFlow

interface AppController {
    val currentBook: StateFlow<EpubBook?>
    val readingProgress: StateFlow<ReadingProgress>
    val isLoading: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>

    val files: StateFlow<List<AppFile>>
    val selectedFilter: StateFlow<FileType>

    val collections: StateFlow<List<EpubCollection>>
    val selectedCollectionBooks: StateFlow<List<BookEntry>>
    val isScanningCollections: StateFlow<Boolean>
    val collectionScanProgress: StateFlow<String>

    val fontSize: StateFlow<Int>
    val isNightMode: StateFlow<Boolean>
    val autoAdvanceChapters: StateFlow<Boolean>

    val ttsState: StateFlow<TtsState>
    val highlightedSentence: StateFlow<Int>

    fun start()

    fun openEpub(source: String)

    fun goToChapter(index: Int)

    fun updateScrollPosition(position: Float)

    fun speakCurrentChapter()

    fun pauseTts()

    fun resumeTts()

    fun stopTts()

    fun setTtsSpeed(speed: Float)

    fun setTtsPitch(pitch: Float)

    fun setAutoAdvanceChapters(enabled: Boolean)

    fun loadFiles()

    fun filterFiles(type: FileType)

    fun loadCollections()

    fun scanAndGroupCollections()

    fun autoScanCollectionsIfNeeded()

    fun rescanCollections()

    fun openCollection(collectionId: String)

    fun renameCollection(collectionId: String, newName: String)

    fun deleteCollection(collectionId: String)

    fun setFontSize(size: Int)

    fun setNightMode(enabled: Boolean)

    fun clearError()

    fun dispose()
}
