package com.omnireader.util

import com.omnireader.data.model.SavedProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ReadingProgressManager(private val store: PreferencesStore) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val PROGRESS_KEY = "progress_map"
    }

    suspend fun loadAll(): Map<String, SavedProgress> {
        return runCatching {
            val data = store.get(PROGRESS_KEY) ?: "{}"
            json.decodeFromString<Map<String, SavedProgress>>(data)
        }.getOrDefault(emptyMap())
    }

    suspend fun loadFor(path: String): SavedProgress? = loadAll()[path]

    suspend fun saveFor(path: String, progress: SavedProgress) {
        runCatching {
            val all = loadAll().toMutableMap()
            all[path] = progress
            store.put(PROGRESS_KEY, json.encodeToString(all))
        }
    }

    suspend fun pruneMissingFiles() {
        runCatching {
            val all = loadAll()
            if (all.isEmpty()) return
            val valid = withContext(Dispatchers.IO) {
                all.filterKeys { File(it).exists() }
            }
            if (valid.size == all.size) return
            store.put(PROGRESS_KEY, json.encodeToString(valid))
        }
    }
}
