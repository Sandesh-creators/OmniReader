package com.omnireader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.omnireader.ui.library.CollectionsScreen
import com.omnireader.ui.library.HomeScreen
import com.omnireader.ui.reader.ReaderScreen
import com.omnireader.ui.settings.SettingsScreen
import com.omnireader.ui.theme.OmniReaderTheme
import com.omnireader.util.CollectionManager
import com.omnireader.util.DesktopBookSourceProvider
import com.omnireader.util.EpubParser
import com.omnireader.util.FileCoverStore
import com.omnireader.util.FileMetadataCache
import com.omnireader.util.FilePreferencesStore
import com.omnireader.util.ProcessSpeechBackend
import com.omnireader.util.ReadingProgressManager
import com.omnireader.util.SettingsManager
import com.omnireader.util.TtsManager
import com.omnireader.viewmodel.DefaultAppController
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private const val FOLDERS_KEY = "library_folders"

private enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Reader("Reader", Icons.Filled.MenuBook),
    Collections("Collections", Icons.Filled.FolderSpecial),
    Settings("Settings", Icons.Filled.Settings)
}

fun main() = application {
    val scope = rememberCoroutineScope()
    val configDir = remember { File(System.getProperty("user.home"), ".omnireader") }
    val provider = remember { DesktopBookSourceProvider() }
    val folderStore = remember { FilePreferencesStore(File(configDir, "settings.json")) }

    val controller = remember {
        val parser = EpubParser(
            openStream = { source -> File(source).takeIf { it.isFile }?.inputStream() },
            coverStore = FileCoverStore(File(configDir, "cache/covers"))
        )
        val metadataCache = FileMetadataCache(
            FilePreferencesStore(File(configDir, "file_metadata_cache.json"))
        )
        val tts = TtsManager(ProcessSpeechBackend())

        DefaultAppController(
            scope = scope,
            epubParser = parser,
            sourceProvider = provider,
            collectionManager = CollectionManager(
                FilePreferencesStore(File(configDir, "epub_collections.json")),
                parser,
                provider,
                metadataCache
            ),
            fileMetadataCache = metadataCache,
            readingProgressManager = ReadingProgressManager(
                FilePreferencesStore(File(configDir, "reading_progress.json"))
            ),
            settingsManager = SettingsManager(
                FilePreferencesStore(File(configDir, "settings.json"))
            ),
            ttsManager = tts,
            logger = { message, error ->
                if (error != null) System.err.println("$message: ${error.message}") else println(message)
            }
        ).also { it.initialize() }
    }

    DisposableEffect(Unit) {
        scope.launch {
            val stored = folderStore.get(FOLDERS_KEY)
                ?.split('\n')
                ?.filter { it.isNotBlank() }
                .orEmpty()
            provider.folders = stored.ifEmpty { defaultLibraryFolders() }
            controller.start()
        }
        onDispose { controller.dispose() }
    }

    val isNightMode by controller.isNightMode.collectAsState()
    var destination by remember { mutableStateOf(Destination.Home) }

    fun addFolder(folder: File) {
        val current = provider.folders
        if (current.any { File(it).absolutePath == folder.absolutePath }) return
        val updated = current + folder.absolutePath
        provider.folders = updated
        scope.launch { folderStore.put(FOLDERS_KEY, updated.joinToString("\n")) }
        controller.loadFiles()
        controller.autoScanCollectionsIfNeeded()
    }

    fun openEpubFile(file: File) {
        addFolder(file.parentFile ?: return)
        controller.openEpub(file.absolutePath)
        destination = Destination.Reader
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "OmniReader",
        state = rememberWindowState(width = 1180.dp, height = 820.dp)
    ) {
        OmniReaderTheme(darkTheme = isNightMode) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = destination == entry,
                                onClick = { destination = entry },
                                icon = { Icon(entry.icon, contentDescription = entry.label) },
                                label = { Text(entry.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    when (destination) {
                        Destination.Home -> HomeScreen(
                            controller = controller,
                            onNavigateToReader = { destination = Destination.Reader },
                            onNavigateToCollections = { destination = Destination.Collections },
                            onOpenFile = {
                                pickEpubFile { file -> openEpubFile(file) }
                            },
                            libraryEmptyHint = "No EPUB folders added yet. Use \"Add books\" to open a file, then its folder is added automatically."
                        )

                        Destination.Reader -> ReaderScreen(
                            controller = controller,
                            onBack = { destination = Destination.Home },
                            onOpenFile = {
                                pickEpubFile { file -> openEpubFile(file) }
                            }
                        )

                        Destination.Collections -> CollectionsScreen(
                            controller = controller,
                            onBack = { destination = Destination.Home },
                            onOpenReader = { destination = Destination.Reader }
                        )

                        Destination.Settings -> SettingsScreen(
                            controller = controller,
                            onBack = { destination = Destination.Home },
                            appVersion = "1.0.0"
                        )
                    }
                }
            }
        }
    }
}

private fun defaultLibraryFolders(): List<String> {
    val home = File(System.getProperty("user.home"))
    return listOf("Books", "Documents", "Downloads")
        .map { File(home, it) }
        .filter { it.isDirectory }
        .map { it.absolutePath }
}

private fun pickEpubFile(onPicked: (File) -> Unit) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Open EPUB"
        fileFilter = FileNameExtensionFilter("EPUB books", "epub")
        isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.let(onPicked)
    }
}
