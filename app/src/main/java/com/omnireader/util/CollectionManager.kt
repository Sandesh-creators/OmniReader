package com.omnireader.util

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.omnireader.data.model.BookEntry
import com.omnireader.data.model.EpubCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.collectionDataStore: DataStore<Preferences> by preferencesDataStore("epub_collections")

class CollectionManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val epubParser = EpubParser(context)
    private val fileScanner = FileScanner(context)
    private val fileMetadataCache = FileMetadataCache(context)

    companion object {
        private val COLLECTIONS_KEY = stringPreferencesKey("collections_json")
    }

    suspend fun loadCollections(): List<EpubCollection> = withContext(Dispatchers.IO) {
        try {
            val data = context.collectionDataStore.data.map { prefs ->
                prefs[COLLECTIONS_KEY] ?: "[]"
            }.first()
            json.decodeFromString<List<EpubCollection>>(data)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveCollections(collections: List<EpubCollection>) = withContext(Dispatchers.IO) {
        try {
            context.collectionDataStore.edit { prefs ->
                prefs[COLLECTIONS_KEY] = json.encodeToString(collections)
            }
        } catch (_: Exception) {}
    }

    suspend fun autoGroupByAuthor(): List<EpubCollection> = withContext(Dispatchers.IO) {
        // Merge new books into existing collections and prune dead paths.
        val prunedExisting = loadCollections().map { collection ->
            collection.copy(
                bookPaths = collection.bookPaths.filter { java.io.File(it).exists() }
            )
        }
        val existingPaths = prunedExisting.flatMap { it.bookPaths }.toSet()

        val epubFiles = fileScanner.scanEpubOnly()
        val newBooks = epubFiles.filter { it.path !in existingPaths }

        val cache = fileMetadataCache.loadAll()
        val updatedCollections = prunedExisting.toMutableList()

        if (newBooks.isEmpty()) {
            if (updatedCollections != loadCollections()) saveCollections(updatedCollections)
            return@withContext updatedCollections
        }

        val authorGroups = mutableMapOf<String, MutableList<BookEntry>>()

        for (file in newBooks) {
            try {
                val metadata = epubParser.extractMetadata(file.uri, file.path).getOrNull()
                val author = metadata?.author?.trim() ?: "Unknown Author"
                val normalizedAuthor = normalizeAuthor(author)

                val cached = cache[file.path]
                val entry = BookEntry(
                    uri = file.uri,
                    title = metadata?.title ?: cached?.title ?: file.name,
                    author = author,
                    path = file.path,
                    lastModified = file.lastModified,
                    coverUri = metadata?.coverUri ?: cached?.coverUri?.let(Uri::parse)
                )

                authorGroups.getOrPut(normalizedAuthor) { mutableListOf() }.add(entry)
            } catch (_: Exception) {
                val entry = BookEntry(
                    uri = file.uri,
                    title = file.name.substringBeforeLast("."),
                    author = "Unknown Author",
                    path = file.path,
                    lastModified = file.lastModified
                )
                authorGroups.getOrPut("unknown_author") { mutableListOf() }.add(entry)
            }
        }

        for ((authorKey, books) in authorGroups) {
            val existing = updatedCollections.find { it.authorKey == authorKey }
            val bookPaths = books.map { it.path }

            if (existing != null) {
                val merged = existing.copy(
                    bookPaths = (existing.bookPaths + bookPaths).distinct()
                )
                val idx = updatedCollections.indexOf(existing)
                updatedCollections[idx] = merged
            } else {
                val authorName = books.firstOrNull()?.author ?: "Unknown Author"
                updatedCollections.add(
                    EpubCollection(
                        id = UUID.randomUUID().toString(),
                        displayName = authorName,
                        authorKey = authorKey,
                        bookPaths = bookPaths
                    )
                )
            }
        }

        saveCollections(updatedCollections)
        updatedCollections
    }

    suspend fun renameCollection(collectionId: String, newName: String) {
        val collections = loadCollections().toMutableList()
        val index = collections.indexOfFirst { it.id == collectionId }
        if (index != -1) {
            collections[index] = collections[index].copy(displayName = newName)
            saveCollections(collections)
        }
    }

    suspend fun deleteCollection(collectionId: String) {
        val collections = loadCollections().toMutableList()
        collections.removeAll { it.id == collectionId }
        saveCollections(collections)
    }

    suspend fun getCollectionBooks(collectionId: String): List<BookEntry> = withContext(Dispatchers.IO) {
        val collections = loadCollections()
        val collection = collections.find { it.id == collectionId } ?: return@withContext emptyList()

        val cache = fileMetadataCache.loadAll()

        collection.bookPaths.mapNotNull { path ->
            runCatching {
                val file = java.io.File(path)
                if (!file.exists()) return@mapNotNull null

                val meta = cache[path]
                BookEntry(
                    uri = Uri.fromFile(file),
                    title = meta?.title ?: file.name.substringBeforeLast("."),
                    author = meta?.author ?: collection.displayName,
                    path = path,
                    lastModified = meta?.lastModified ?: file.lastModified(),
                    coverUri = meta?.coverUri?.let(Uri::parse)
                )
            }.getOrNull()
        }
    }

    private fun normalizeAuthor(author: String): String {
        return author.lowercase()
            .trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^a-z0-9_]"), "")
            .ifEmpty { "unknown_author" }
    }
}
