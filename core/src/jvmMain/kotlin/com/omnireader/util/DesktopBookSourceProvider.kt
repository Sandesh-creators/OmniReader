package com.omnireader.util

import com.omnireader.data.model.AppFile
import com.omnireader.data.model.FileType
import java.io.File

class DesktopBookSourceProvider : BookSourceProvider {

    @Volatile
    var folders: List<String> = emptyList()

    override fun scanAll(): List<AppFile> = scanEpub()

    override fun scanEpub(): List<AppFile> {
        val results = mutableListOf<AppFile>()
        folders.forEach { path ->
            val dir = File(path)
            if (dir.isDirectory) scanDirectory(dir, MAX_DEPTH, results)
        }
        return results.distinctBy { it.path }.sortedByDescending { it.lastModified }
    }

    override fun scan(type: FileType): List<AppFile> = when (type) {
        FileType.EPUB -> scanEpub()
        else -> emptyList()
    }

    override fun resolvePath(source: String): String = File(source).absolutePath

    private fun scanDirectory(dir: File, depth: Int, results: MutableList<AppFile>) {
        if (depth <= 0) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                scanDirectory(child, depth - 1, results)
            } else if (child.isFile && child.extension.equals("epub", ignoreCase = true)) {
                results.add(
                    AppFile(
                        source = child.absolutePath,
                        name = child.name,
                        path = child.absolutePath,
                        size = child.length(),
                        lastModified = child.lastModified(),
                        fileType = FileType.EPUB
                    )
                )
            }
        }
    }

    companion object {
        private const val MAX_DEPTH = 6
    }
}
