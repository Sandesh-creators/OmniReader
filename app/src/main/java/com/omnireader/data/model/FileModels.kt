package com.omnireader.data.model

import android.net.Uri

data class AppFile(
    val uri: Uri,
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val fileType: FileType,
    val coverUri: Uri? = null
)

enum class FileType(val extension: String, val displayName: String) {
    EPUB("epub", "EPUB Books"),
    SHELL("sh", "Shell Scripts"),
    EXECUTABLE("exe", "Windows Executables"),
    DIRECTORY("", "Folders"),
    UNKNOWN("", "Other");

    companion object {
        fun fromExtension(ext: String): FileType = when (ext.lowercase()) {
            "epub" -> EPUB
            "sh" -> SHELL
            "exe" -> EXECUTABLE
            else -> UNKNOWN
        }
    }
}

data class TerminalLine(
    val content: String,
    val type: LineType = LineType.OUTPUT,
    val timestamp: Long = System.currentTimeMillis()
)

enum class LineType {
    INPUT, OUTPUT, ERROR, SYSTEM
}

data class TerminalSession(
    val command: String,
    val output: List<TerminalLine> = emptyList(),
    val isRunning: Boolean = false,
    val exitCode: Int? = null
)
