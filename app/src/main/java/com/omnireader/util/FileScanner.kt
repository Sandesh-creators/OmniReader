package com.omnireader.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.omnireader.data.model.AppFile
import com.omnireader.data.model.FileType
import java.io.File

class FileScanner(private val context: Context) {

    private val supportedExtensions = setOf("epub", "sh", "exe")

    fun scanForFiles(): List<AppFile> {
        val files = scanMediaStore()
        val fsFiles = scanFileSystem()
        return (files + fsFiles).distinctBy { it.path }.sortedByDescending { it.lastModified }
    }

    fun scanEpubOnly(): List<AppFile> {
        val files = scanMediaStoreForType(FileType.EPUB)
        val fsFiles = scanFileSystemForExtension("epub")
        return (files + fsFiles).distinctBy { it.path }.sortedByDescending { it.lastModified }
    }

    fun scanByType(type: FileType): List<AppFile> {
        return when (type) {
            FileType.EPUB -> scanEpubOnly()
            FileType.SHELL -> scanMediaStoreForType(FileType.SHELL) + scanFileSystemForExtension("sh")
            FileType.EXECUTABLE -> scanMediaStoreForType(FileType.EXECUTABLE) + scanFileSystemForExtension("exe")
            FileType.DIRECTORY -> scanFileSystem().filter { it.fileType == FileType.DIRECTORY }
            FileType.UNKNOWN -> scanForFiles()
        }.distinctBy { it.path }.sortedByDescending { it.lastModified }
    }

    private fun scanMediaStore(): List<AppFile> {
        val files = mutableListOf<AppFile>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%.epub", "%.sh", "%.exe")

        try {
            context.contentResolver.query(
                collection, projection, selection, selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                mapCursorToFiles(cursor)?.let { files.addAll(it) }
            }
        } catch (_: Exception) {}

        return files
    }

    private fun scanMediaStoreForType(type: FileType): List<AppFile> {
        val ext = type.extension
        val files = mutableListOf<AppFile>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        try {
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%.$ext"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                mapCursorToFiles(cursor, typeFilter = type)?.let { files.addAll(it) }
            }
        } catch (_: Exception) {}

        return files
    }

    private fun mapCursorToFiles(
        cursor: android.database.Cursor,
        typeFilter: FileType? = null
    ): List<AppFile>? {
        if (!cursor.moveToFirst()) return null

        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
        val relPathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

        val files = mutableListOf<AppFile>()

        do {
            val name = cursor.getString(nameCol) ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            val fileType = FileType.fromExtension(ext)

            if (fileType == FileType.UNKNOWN) continue
            if (typeFilter != null && fileType != typeFilter) continue
            if (ext !in supportedExtensions) continue

            val path = if (relPathCol >= 0 && !cursor.isNull(relPathCol)) {
                resolveMediaPath(cursor.getString(relPathCol), name)
            } else {
                cursor.getString(pathCol) ?: ""
            }
            if (path.isBlank()) continue

            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id
            )

            files.add(
                AppFile(
                    uri = uri,
                    name = name,
                    path = path,
                    size = cursor.getLong(sizeCol),
                    lastModified = cursor.getLong(dateCol) * 1000,
                    fileType = fileType
                )
            )
        } while (cursor.moveToNext())

        return files
    }

    fun resolveMediaPath(relativePath: String?, displayName: String): String {
        val base = Environment.getExternalStorageDirectory().absolutePath
        val rel = relativePath?.trim('/')
        return if (rel.isNullOrEmpty()) "$base/$displayName" else "$base/$rel/$displayName"
    }

    // Resolve a stable absolute filesystem path for any supported Uri, so reading
    // progress / metadata caches can be keyed consistently across MediaStore,
    // file:// and collection flows.
    fun resolvePath(uri: Uri): String {
        if (uri.scheme == "file") return uri.path ?: uri.toString()

        if (uri.scheme == "content") {
            val projection = arrayOf(
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA
            )
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val relCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                        val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                        val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                        if (name != null) {
                            val rel = if (relCol >= 0 && !cursor.isNull(relCol)) cursor.getString(relCol) else null
                            return resolveMediaPath(rel, name)
                        }
                        val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                        if (!data.isNullOrEmpty()) return data
                    }
                }
            } catch (_: Exception) {}
        }

        return uri.toString()
    }

    private fun scanFileSystem(): List<AppFile> {
        val files = mutableListOf<AppFile>()
        val root = Environment.getExternalStorageDirectory()

        val targetDirs = listOf(root, File(root, "Download"), File(root, "Documents"), File(root, "Books"))

        for (dir in targetDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            scanDirectory(dir, files, maxDepth = 3)
        }

        return files
    }

    private fun scanDirectory(dir: File, files: MutableList<AppFile>, maxDepth: Int) {
        if (maxDepth <= 0) return

        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue

            if (child.isFile) {
                val ext = child.extension.lowercase()
                if (ext in supportedExtensions) {
                    files.add(
                        AppFile(
                            uri = Uri.fromFile(child),
                            name = child.name,
                            path = child.absolutePath,
                            size = child.length(),
                            lastModified = child.lastModified(),
                            fileType = FileType.fromExtension(ext)
                        )
                    )
                }
            } else if (child.isDirectory) {
                scanDirectory(child, files, maxDepth - 1)
            }
        }
    }

    private fun scanFileSystemForExtension(ext: String): List<AppFile> {
        val files = mutableListOf<AppFile>()
        val root = Environment.getExternalStorageDirectory()

        val targetDirs = listOf(root, File(root, "Download"), File(root, "Documents"), File(root, "Books"))

        for (dir in targetDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            scanDirectoryForExt(dir, files, ext, maxDepth = 3)
        }

        return files
    }

    private fun scanDirectoryForExt(dir: File, files: MutableList<AppFile>, targetExt: String, maxDepth: Int) {
        if (maxDepth <= 0) return

        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue

            if (child.isFile && child.extension.lowercase() == targetExt) {
                files.add(
                    AppFile(
                        uri = Uri.fromFile(child),
                        name = child.name,
                        path = child.absolutePath,
                        size = child.length(),
                        lastModified = child.lastModified(),
                        fileType = FileType.fromExtension(targetExt)
                    )
                )
            } else if (child.isDirectory) {
                scanDirectoryForExt(child, files, targetExt, maxDepth - 1)
            }
        }
    }
}
