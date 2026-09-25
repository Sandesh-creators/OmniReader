package com.omnireader.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnireader.data.model.CachedFileMeta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.fileMetadataDataStore: DataStore<Preferences> by preferencesDataStore("file_metadata_cache")

class FileMetadataCache(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val META_KEY = stringPreferencesKey("meta_json")

    suspend fun loadAll(): Map<String, CachedFileMeta> {
        return runCatching {
            val data = context.fileMetadataDataStore.data.map { it[META_KEY] ?: "{}" }.first()
            json.decodeFromString<Map<String, CachedFileMeta>>(data)
        }.getOrDefault(emptyMap())
    }

    suspend fun loadEntry(path: String): CachedFileMeta? = loadAll()[path]

    suspend fun put(path: String, meta: CachedFileMeta) {
        val all = loadAll().toMutableMap()
        all[path] = meta
        putAll(all)
    }

    suspend fun putAll(entries: Map<String, CachedFileMeta>) {
        runCatching {
            context.fileMetadataDataStore.edit { prefs ->
                prefs[META_KEY] = json.encodeToString(entries)
            }
        }
    }
}