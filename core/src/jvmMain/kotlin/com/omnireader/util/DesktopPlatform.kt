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
    private var piper: String? = null
    private var piperModel: File? = null
    private var ffmpeg: String? = null

    override fun initialize(onReady: () -> Unit) {
        if (!tempDir.isDirectory) tempDir.mkdirs()
        resolveEngine()
        onReady()
    }

    private fun resolveEngine() {
        if (isWindows && piper != null) return
        if (!isWindows && (piper != null || spdSay != null || espeak != null)) return

        piper = findPiper()
        if (piper != null) {
            piperModel = findPiperModel()
            player = findPlayer()
            ffmpeg = findCommand("ffmpeg")
            return
        }

        if (isWindows) {
            if (player == null) player = listOf("powershell.exe")
            return
        }

        spdSay = findCommand("spd-say")
        if (spdSay == null) {
            espeak = findCommand("espeak-ng") ?: findCommand("espeak")
            player = findPlayer()
        }
    }

    private fun findPiper(): String? {
        findCommand("piper")?.let { return it }
        val home = System.getProperty("user.home")
        val candidates = buildList {
            ttsDir()?.let { dir ->
                add("$dir/venv/bin/piper")
                add("$dir/bin/piper")
                add("$dir/piper")
            }
            add("$home/.local/share/omnireader/tts/venv/bin/piper")
            add("$home/.local/share/omnireader/tts/piper")
            add("$home/.local/bin/piper")
            add("$home/.local/share/piper/piper")
            add("$home/bin/piper")
        }
        return candidates
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    private fun ttsDir(): String? {
        val explicit = System.getenv("OMNIREADER_TTS_DIR")?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val appTtsDir = File(System.getProperty("user.dir"), ".tts")
        return if (appTtsDir.isDirectory) appTtsDir.absolutePath else null
    }

    private fun findPiperModel(): File? {
        val home = System.getProperty("user.home")
        val override = System.getenv("OMNIREADER_PIPER_VOICE")?.takeIf { it.isNotBlank() }
        val voiceDirs = buildList {
            ttsDir()?.let { add("$it/voices") }
            add("$home/.local/share/omnireader/tts/voices")
            add("$home/.local/share/piper/voices")
            add("$home/.config/piper/voices")
            add("$home/.local/share/omnireader/tts/piper-voices")
        }

        val models = voiceDirs
            .map { File(it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles()?.filter { it.name.endsWith(".onnx") }.orEmpty() }
            .filter { File(it.parentFile, it.name + ".json").isFile || File(it.parentFile, it.name.replace(".onnx", ".onnx.json")).isFile }
            .sortedBy { it.name }

        if (models.isEmpty()) return null
        override?.let { wanted ->
            return models.firstOrNull { it.name.removeSuffix(".onnx") == wanted }
                ?: models.firstOrNull { it.name.contains(wanted, ignoreCase = true) }
                ?: models.first()
        }
        return models.firstOrNull { it.name.contains("en_US", ignoreCase = true) } ?: models.first()
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
        piper?.let { engine -> return buildPiperCommands(engine, text, rate, pitch) }

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

    private fun buildPiperCommands(
        engine: String,
        text: String,
        rate: Float,
        pitch: Float
    ): List<List<String>> {
        val model = piperModel ?: return emptyList()
        val playback = player ?: return emptyList()

        val input = File(tempDir, "sentence.txt")
        val rawWav = File(tempDir, "piper-raw.wav")
        val wav = File(tempDir, "sentence.wav")
        input.writeText(text)
        runCatching { rawWav.delete() }
        runCatching { wav.delete() }

        val lengthScale = (1f / rate.coerceIn(0.5f, 2.0f))
        val commands = mutableListOf<List<String>>()

        commands += listOf(
            engine,
            "--model", model.absolutePath,
            "--output-file", rawWav.absolutePath,
            "--input-file", input.absolutePath,
            "--length-scale", lengthScale.toString(),
            "--sentence-silence", "0.15"
        )

        val ffmpegPath = ffmpeg
        if (pitch != 1f && ffmpegPath != null) {
            val shifted = File(tempDir, "piper-pitched.wav")
            val factor = pitch.coerceIn(0.5f, 2.0f)
            commands += listOf(
                ffmpegPath,
                "-y",
                "-loglevel", "error",
                "-i", rawWav.absolutePath,
                "-filter:a", "asetrate=22050*$factor,aresample=22050,atempo=${1f / factor}",
                shifted.absolutePath
            )
            commands += playback + shifted.absolutePath
        } else {
            commands += playback + rawWav.absolutePath
        }

        return commands
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
