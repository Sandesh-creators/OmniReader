package com.omnireader.util

import com.omnireader.data.model.AppFile
import com.omnireader.data.model.FileType

interface BookSourceProvider {
    fun scanAll(): List<AppFile>

    fun scanEpub(): List<AppFile>

    fun scan(type: FileType): List<AppFile>

    fun resolvePath(source: String): String
}

interface CoverStore {
    fun load(key: String): String?

    fun save(key: String, data: ByteArray, extension: String): String?
}

interface PreferencesStore {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)
}

interface SpeechBackend {
    fun initialize(onReady: () -> Unit)

    fun speak(text: String, rate: Float, pitch: Float, onDone: () -> Unit, onError: () -> Unit)

    /**
     * Prepare audio for [text] ahead of time so that the following sentence
     * can start playing without waiting for engine start-up. Backends that
     * cannot pre-render may ignore this.
     */
    fun prefetch(text: String, rate: Float, pitch: Float) {}

    /** Human readable name of the engine actually in use, for the settings screen. */
    fun engineName(): String = ""

    fun stop()

    fun shutdown()
}
