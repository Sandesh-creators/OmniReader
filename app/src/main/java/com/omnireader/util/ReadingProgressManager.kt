package com.omnireader.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnireader.data.model.SavedProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.progressDataStore: DataStore<Preferences> by preferencesDataStore("reading_progress")

class ReadingProgressManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val PROGRESS_KEY = stringPreferencesKey("progress_map")

    suspend fun loadAll(): Map<String, SavedProgress> {
        return runCatching {
            val data = context.progressDataStore.data.map { it[PROGRESS_KEY] ?: "{}" }.first()
            json.decodeFromString<Map<String, SavedProgress>>(data)
        }.getOrDefault(emptyMap())
    }

    suspend fun loadFor(path: String): SavedProgress? = loadAll()[path]

    suspend fun saveFor(path: String, progress: SavedProgress) {
        runCatching {
            val all = loadAll().toMutableMap()
            all[path] = progress
            context.progressDataStore.edit { prefs ->
                prefs[PROGRESS_KEY] = json.encodeToString(all)
            }
        }
    }

    suspend fun pruneMissingFiles() {
        runCatching {
            val all = loadAll()
            if (all.isEmpty()) return
            val valid = all.filterKeys { java.io.File(it).exists() }
            if (valid.size == all.size) return
            context.progressDataStore.edit { prefs ->
                prefs[PROGRESS_KEY] = json.encodeToString(valid)
            }
        }
    }
}