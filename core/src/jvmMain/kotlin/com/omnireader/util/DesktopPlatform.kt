package com.omnireader.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.omnireader.ui.components.loadCoverImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

actual val platformIoDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO

class FilePreferencesStore(private val file: File) : PreferencesStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    override suspend fun get(key: String): String? = mutex.withLock { read()[key] }

    override suspend fun put(key: String, value: String) {
        mutex.withLock {
            val all = read().toMutableMap()
            all[key] = value
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(all))
        }
    }

    private fun read(): Map<String, String> {
        return runCatching {
            if (file.isFile) json.decodeFromString<Map<String, String>>(file.readText()) else emptyMap()
        }.getOrDefault(emptyMap())
    }
}

class ProcessSpeechBackend : SpeechBackend {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "omnireader-tts")
    private val textFile = File(tempDir, "sentence.txt")
    private val scriptFile = File(tempDir, "speak.ps1")

    private var spdSay: String? = null
    private var espeak: String? = null
    private var player: List<String>? = null
    private var activeProcess: Process? = null

    override fun initialize(onReady: () -> Unit) {
        if (!tempDir.isDirectory) tempDir.mkdirs()
        resolveEngine()
        onReady()
    }

    private fun resolveEngine() {
        if (isWindows) {
            if (player == null) player = listOf("powershell.exe")
            return
        }
        if (spdSay != null || espeak != null) return
        spdSay = findCommand("spd-say")
        if (spdSay == null) {
            espeak = findCommand("espeak-ng") ?: findCommand("espeak")
            player = findPlayer()
        }
    }

    override fun speak(text: String, rate: Float, pitch: Float, onDone: () -> Unit, onError: () -> Unit) {
        stop()
        resolveEngine()

        val commands = buildCommands(text, rate, pitch)
        if (commands.isEmpty()) {
            onError()
            return
        }

        val worker = Thread {
            try {
                for (command in commands) {
                    if (command.isEmpty()) continue
                    val process = ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                    activeProcess = process
                    process.inputStream.readBytes()
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        onError()
                        return@Thread
                    }
                }
                activeProcess = null
                onDone()
            } catch (_: Exception) {
                activeProcess = null
                onError()
            }
        }
        worker.isDaemon = true
        worker.name = "omnireader-tts"
        worker.start()
    }

    override fun stop() {
        runCatching { activeProcess?.destroy() }
        activeProcess = null
    }

    override fun shutdown() {
        stop()
    }

    private fun buildCommands(text: String, rate: Float, pitch: Float): List<List<String>> {
        if (isWindows) {
            val powerShell = player ?: return emptyList()
            textFile.writeText(text)
            scriptFile.writeText(
                buildString {
                    appendLine("Add-Type -AssemblyName System.Speech")
                    appendLine("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer")
                    appendLine("\$s.Rate = ${((rate - 1f) * 10f).roundToInt().coerceIn(-10, 10)}")
                    appendLine("\$s.Pitch = ${((pitch - 1f) * 10f).roundToInt().coerceIn(-10, 10)}")
                    appendLine("\$s.SetOutputToDefaultAudioDevice()")
                    appendLine("\$s.Speak([IO.File]::ReadAllText('${textFile.absolutePath.replace("'", "''")}'))")
                    appendLine("\$s.Dispose()")
                }
            )
            return listOf(
                powerShell + listOf(
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass",
                    "-File", scriptFile.absolutePath
                )
            )
        }

        spdSay?.let { command ->
            return listOf(
                listOf(
                    command,
                    "-w",
                    "-l", "en",
                    "-r", ((rate - 1f) * 100f).roundToInt().coerceIn(-100, 100).toString(),
                    "-p", ((pitch - 1f) * 100f).roundToInt().coerceIn(-100, 100).toString(),
                    text
                )
            )
        }

        val engine = espeak ?: return emptyList()
        val playback = player ?: return emptyList()
        val wav = File(tempDir, "sentence.wav")
        runCatching { wav.delete() }

        val wordsPerMinute = (175f * rate).roundToInt().coerceIn(80, 450)
        val enginePitch = (50f * pitch).roundToInt().coerceIn(0, 99)

        return listOf(
            listOf(
                engine,
                "-v", "en-us",
                "-s", wordsPerMinute.toString(),
                "-p", enginePitch.toString(),
                "-w", wav.absolutePath,
                text
            ),
            playback + wav.absolutePath
        )
    }

    private fun findPlayer(): List<String>? {
        findCommand("paplay")?.let { return listOf(it) }
        findCommand("aplay")?.let { return listOf(it, "-q") }
        findCommand("ffplay")?.let { return listOf(it, "-nodisp", "-autoexit", "-loglevel", "quiet") }
        findCommand("play")?.let { return listOf(it, "-q") }
        findCommand("mpv")?.let { return listOf(it, "--really-quiet") }
        return null
    }

    private fun findCommand(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        val candidates = if (isWindows) listOf("$name.exe", "$name.cmd", name) else listOf(name)
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (candidate in candidates) {
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file.absolutePath
            }
        }
        return null
    }
}
