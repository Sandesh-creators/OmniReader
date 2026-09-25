package com.omnireader.util

import com.omnireader.data.model.CachedFileMeta
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileMetadataCache(private val store: PreferencesStore) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val META_KEY = "meta_json"
    }

    suspend fun loadAll(): Map<String, CachedFileMeta> {
        return runCatching {
            val data = store.get(META_KEY) ?: "{}"
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
            store.put(META_KEY, json.encodeToString(entries))
        }
    }
}
