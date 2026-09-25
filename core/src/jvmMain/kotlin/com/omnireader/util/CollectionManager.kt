package com.omnireader.util

import com.omnireader.data.model.BookEntry
import com.omnireader.data.model.EpubCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class CollectionManager(
    private val store: PreferencesStore,
    private val epubParser: EpubParser,
    private val sourceProvider: BookSourceProvider,
    private val fileMetadataCache: FileMetadataCache
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        const val COLLECTIONS_KEY = "collections_json"
    }

    suspend fun loadCollections(): List<EpubCollection> {
        return runCatching {
            val data = store.get(COLLECTIONS_KEY) ?: "[]"
            json.decodeFromString<List<EpubCollection>>(data)
        }.getOrDefault(emptyList())
    }

    suspend fun saveCollections(collections: List<EpubCollection>) {
        runCatching {
            store.put(COLLECTIONS_KEY, json.encodeToString(collections))
        }
    }

    suspend fun autoGroupByAuthor(): List<EpubCollection> {
        val prunedExisting = loadCollections().map { collection ->
            collection.copy(
                bookPaths = collection.bookPaths.filter { File(it).exists() }
            )
        }
        val existingPaths = prunedExisting.flatMap { it.bookPaths }.toSet()

        val epubFiles = sourceProvider.scanEpub()
        val newBooks = epubFiles.filter { it.path !in existingPaths }

        val cache = fileMetadataCache.loadAll()
        val updatedCollections = prunedExisting.toMutableList()

        if (newBooks.isEmpty()) {
            if (updatedCollections != loadCollections()) saveCollections(updatedCollections)
            return updatedCollections
        }

        val authorGroups = mutableMapOf<String, MutableList<BookEntry>>()

        for (file in newBooks) {
            try {
                val metadata = epubParser.extractMetadata(file.source, file.path).getOrNull()
                val author = metadata?.author?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown Author"
                val normalizedAuthor = normalizeAuthor(author)

                val cached = cache[file.path]
                val entry = BookEntry(
                    source = file.source,
                    title = metadata?.title ?: cached?.title ?: file.name,
                    author = author,
                    path = file.path,
                    lastModified = file.lastModified,
                    coverSource = metadata?.coverSource ?: cached?.coverSource
                )

                authorGroups.getOrPut(normalizedAuthor) { mutableListOf() }.add(entry)
            } catch (_: Exception) {
                val entry = BookEntry(
                    source = file.source,
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
        return updatedCollections
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

    suspend fun getCollectionBooks(collectionId: String): List<BookEntry> {
        val collections = loadCollections()
        val collection = collections.find { it.id == collectionId } ?: return emptyList()

        val cache = fileMetadataCache.loadAll()

        return withContext(Dispatchers.IO) {
            collection.bookPaths.mapNotNull { path ->
                runCatching {
                    val file = File(path)
                    if (!file.exists()) return@mapNotNull null

                    val meta = cache[path]
                    BookEntry(
                        source = file.absolutePath,
                        title = meta?.title ?: file.name.substringBeforeLast("."),
                        author = meta?.author ?: collection.displayName,
                        path = path,
                        lastModified = meta?.lastModified ?: file.lastModified(),
                        coverSource = meta?.coverSource
                    )
                }.getOrNull()
            }
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
