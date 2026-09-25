package com.omnireader.data.model

import kotlinx.serialization.Serializable

data class EpubBook(
    val source: String,
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
    val coverSource: String? = null
)

data class EpubMetadata(
    val title: String,
    val author: String,
    val coverSource: String? = null
)

data class EpubChapter(
    val index: Int,
    val title: String,
    val htmlContent: String,
    val plainText: String,
    val isFrontMatter: Boolean = false
)

data class ReadingProgress(
    val chapterIndex: Int = 0,
    val scrollPosition: Float = 0f
)

data class TtsState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val currentChapterIndex: Int = 0,
    val currentSentenceIndex: Int = 0
)

data class BookEntry(
    val source: String,
    val title: String,
    val author: String,
    val path: String,
    val lastModified: Long,
    val coverSource: String? = null
)

@Serializable
data class CachedFileMeta(
    val title: String? = null,
    val author: String? = null,
    val coverSource: String? = null,
    val lastModified: Long = 0L
)

@Serializable
data class SavedProgress(
    val chapterIndex: Int = 0,
    val scrollPosition: Float = 0f
)

@Serializable
data class EpubCollection(
    val id: String,
    val displayName: String,
    val authorKey: String,
    val bookPaths: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)
