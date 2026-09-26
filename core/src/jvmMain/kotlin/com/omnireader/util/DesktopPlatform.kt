package com.omnireader.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.sound.sampled.AudioSystem
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

/**
 * Speech backend for the desktop app.
 *
 * Every sentence used to start a brand new engine process, which meant the
 * user paid the engine start-up cost (a PowerShell launch on Windows, an ONNX
 * model load for Piper) as dead air between sentences. Audio for the next
 * sentence is now rendered while the current one plays, and Windows keeps a
 * single SAPI host process alive for the whole session.
 */
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

    private val lock = Any()
    private val pendingRenders = ArrayDeque<String>()
    private val readyAudio = ArrayDeque<Pair<String, File>>()
    private var renderThread: Thread? = null
    private var isPlaying = false
    private var liveRenderWaiting = false

    /** Guards the single long-lived Piper process. */
    private val streamLock = Any()
    private var streamProcess: Process? = null
    private var streamWriter: BufferedWriter? = null
    private var streamDir: File? = null
    private var streamRate: Float = -1f

    private var hostProcess: Process? = null
    private var hostWriter: BufferedWriter? = null
    private var hostReader: BufferedReader? = null

    private companion object {
        /** How many sentences may be rendered ahead of the one being spoken. */
        const val MAX_PRERENDERED = 3

        /** Upper bound on how long one streamed sentence may take. */
        const val STREAM_SYNTHESIS_TIMEOUT_MILLIS = 30_000L

    }

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
            if (piperModel != null) {
                player = findPlayer()
                ffmpeg = findCommand("ffmpeg")
                return
            }
            piper = null
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

    override fun engineName(): String = when {
        piper != null && piperModel != null ->
            "Piper · ${piperModel?.name?.removeSuffix(".onnx")}"
        isWindows -> "Windows SAPI"
        spdSay != null -> "speech-dispatcher"
        espeak != null -> "espeak-ng"
        else -> "unavailable"
    }

    private fun findPiper(): String? {
        findCommand("piper")?.let { return it }
        val home = System.getProperty("user.home")
        val candidates = buildList {
            ttsDir()?.let { dir ->
                add("$dir/venv/bin/piper")
                add("$dir/venv/Scripts/piper.exe")
                add("$dir/bin/piper")
                add("$dir/piper")
            }
            add("$home/.local/share/omnireader/tts/venv/bin/piper")
            add("$home/.local/share/omnireader/tts/piper")
            add("$home/.local/bin/piper")
            add("$home/.local/share/piper/piper")
            add("$home/bin/piper")
            add("$home/AppData/Local/Programs/OmniReader/tts/venv/Scripts/piper.exe")
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
            add("$home/AppData/Local/Programs/OmniReader/tts/voices")
        }

        val models = voiceDirs
            .map { File(it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles()?.filter { it.name.endsWith(".onnx") }.orEmpty() }
            .filter {
                File(it.parentFile, it.name + ".json").isFile ||
                    File(it.parentFile, it.name.replace(".onnx", ".onnx.json")).isFile
            }
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
        stopPlayback()
        resolveEngine()

        Thread({
            try {
                val engine = piper
                if (engine != null && piperModel != null) {
                    speakWithPiper(text, rate, pitch, onDone, onError)
                } else if (isWindows) {
                    speakWithWindowsHost(text, rate, pitch, onDone, onError)
                } else {
                    speakWithProcesses(text, rate, pitch, onDone, onError)
                }
            } catch (_: Exception) {
                onError()
            }
        }, "omnireader-tts").apply {
            isDaemon = true
            start()
        }
    }

    private fun speakWithPiper(
        text: String,
        rate: Float,
        pitch: Float,
        onDone: () -> Unit,
        onError: () -> Unit
    ) {
        val preRendered = takeReadyAudio(text)
        debug("speak \"${text.take(28)}\" pre-rendered=${preRendered != null}")

        val rendered = preRendered ?: runCatching { renderNow(text, rate) }.getOrNull()
        if (rendered == null) {
            onError()
            return
        }

        val playable = applyPitch(rendered, pitch) ?: rendered
        synchronized(lock) { isPlaying = true }
        val played = playAudio(playable)
        synchronized(lock) { isPlaying = false }
        if (!played) {
            onError()
            return
        }
        onDone()
    }

    private fun takeReadyAudio(text: String): File? = synchronized(lock) {
        val head = readyAudio.firstOrNull()
        if (head != null && head.first == text) {
            readyAudio.removeFirst()
            head.second
        } else {
            null
        }
    }

    /**
     * Synthesises one sentence immediately. Piper is kept alive between
     * sentences because starting it costs about three seconds (interpreter,
     * onnxruntime and model load) while a sentence only takes ~100 ms to
     * render once the model is warm.
     */
    private fun renderNow(text: String, rate: Float): File? = synchronized(streamLock) {
        val engine = piper ?: return null
        try {
            streamFile(engine, text, rate)
        } catch (_: Exception) {
            // Fall back to a one-shot process if the stream breaks.
            restartStreamLocked(engine, rate)
            streamFile(engine, text, rate)
        }
    }

    private fun streamFile(engine: String, text: String, rate: Float): File? {
        ensureStreamLocked(engine, rate)
        val process = streamProcess ?: return null
        val outDir = streamDir ?: return null
        if (text.isBlank()) return null

        val before = outDir.listFiles()?.toSet().orEmpty()
        streamWriter?.apply {
            write(text.replace('\n', ' '))
            newLine()
            flush()
        } ?: return null

        val deadline = System.currentTimeMillis() + STREAM_SYNTHESIS_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return null
            val fresh = outDir.listFiles()?.filter { it !in before && it.length() > 44 }
            val file = fresh?.maxByOrNull { it.lastModified() }
            if (file != null) {
                var previous = -1L
                while (System.currentTimeMillis() < deadline) {
                    val size = runCatching { file.length() }.getOrDefault(0)
                    if (size > 44 && size == previous) return file
                    previous = size
                    Thread.sleep(5)
                }
                return null
            }
            Thread.sleep(4)
        }
        return null
    }

    private fun ensureStreamLocked(engine: String, rate: Float) {
        if (streamProcess?.isAlive == true && streamRate == rate) return
        restartStreamLocked(engine, rate)
    }

    private fun restartStreamLocked(engine: String, rate: Float) {
        synchronized(lock) {
            runCatching { streamProcess?.destroy() }
            streamWriter?.let { runCatching { it.close() } }
            runCatching { streamDir?.deleteRecursively() }
            streamProcess = null
            streamWriter = null
            streamDir = null
            streamRate = rate
        }

        val outDir = File(tempDir, "piper-stream").apply { mkdirs() }
        runCatching { outDir.listFiles()?.forEach { it.delete() } }

        val process = runCatching {
            ProcessBuilder(
                engine,
                "--model", piperModel?.absolutePath.orEmpty(),
                "--output-dir", outDir.absolutePath,
                "--output-dir-naming", "timestamp",
                "--length-scale", (1f / rate.coerceIn(0.5f, 2.0f)).toString(),
                "--sentence-silence", "0.1"
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return

        synchronized(lock) {
            streamDir = outDir
            streamProcess = process
            streamWriter = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        }

        // Drain the log so the pipe can never fill up and stall Piper.
        Thread({
            runCatching { process.inputStream.readBytes() }
        }, "omnireader-piper-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun isCancelled(): Boolean = Thread.currentThread().isInterrupted

    private fun debug(message: String) {
        if (System.getenv("OMNIREADER_TTS_DEBUG").isNullOrBlank()) return
        val stamp = System.currentTimeMillis() % 100000
        System.err.println("[tts $stamp] $message")
    }

    /**
     * Renders the next sentence in the background so the pause the reader
     * hears is the configured gap rather than synthesis time.
     */
    override fun prefetch(text: String, rate: Float, pitch: Float) {
        if (piper == null || piperModel == null || text.isBlank()) return
        val shouldStart = synchronized(lock) {
            if (readyAudio.any { it.first == text }) return
            if (pendingRenders.contains(text)) return
            if (readyAudio.size + pendingRenders.size >= MAX_PRERENDERED) return
            pendingRenders.addLast(text)
            if (renderThread?.isAlive != true) {
                renderThread = Thread({ drainRenderQueue(rate) }, "omnireader-tts-prefetch").apply {
                    isDaemon = true
                    start()
                }
                true
            } else {
                false
            }
        }
        if (!shouldStart) return
    }

    private fun drainRenderQueue(rate: Float) {
        while (true) {
            val next = synchronized(lock) {
                if (pendingRenders.isEmpty()) return
                if (liveRenderWaiting) return
                if (readyAudio.size + pendingRenders.size - 1 > MAX_PRERENDERED) {
                    pendingRenders.clear()
                    return
                }
                pendingRenders.removeFirst()
            }

            val file = runCatching { renderNow(next, rate) }.getOrNull()
            if (file != null) {
                debug("pre-rendered \"${next.take(28)}\"")
                synchronized(lock) {
                    readyAudio.addLast(next to file)
                    pruneOldAudio()
                }
            } else {
                debug("pre-render failed \"${next.take(28)}\"")
            }
        }
    }

    private fun pruneOldAudio() {
        while (readyAudio.size > MAX_PRERENDERED) {
            val stale = readyAudio.removeFirst()
            runCatching { stale.second.delete() }
        }
    }

    /**
     * Sends one sentence to a long-lived PowerShell/SAPI host. This removed the
     * multi-second pause that a fresh PowerShell process per sentence caused.
     */
    private fun speakWithWindowsHost(
        text: String,
        rate: Float,
        pitch: Float,
        onDone: () -> Unit,
        onError: () -> Unit
    ) {
        val rateValue = ((rate - 1f) * 10f).roundToInt().coerceIn(-10, 10)
        val pitchValue = ((pitch - 1f) * 10f).roundToInt().coerceIn(-10, 10)

        val handled = runCatching {
            val writer = ensureHost() ?: return@runCatching false
            synchronized(lock) {
                writer.write("$rateValue,$pitchValue\u0001$text")
                writer.newLine()
                writer.flush()
            }
            val reader = synchronized(lock) { hostReader } ?: return@runCatching false
            reader.readLine() != null
        }.getOrDefault(false)

        if (handled) onDone() else speakWithProcesses(text, rate, pitch, onDone, onError)
    }

    private fun ensureHost(): BufferedWriter? {
        synchronized(lock) {
            if (hostProcess?.isAlive == true) return hostWriter
        }

        val powershell = player?.firstOrNull() ?: "powershell.exe"
        val script = File(tempDir, "speak-host.ps1")
        runCatching {
            script.writeText(
                buildString {
                    appendLine("Add-Type -AssemblyName System.Speech")
                    appendLine("\$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer")
                    appendLine("\$voice.SetOutputToDefaultAudioDevice()")
                    appendLine("while (\$true) {")
                    appendLine("    \$line = [Console]::In.ReadLine()")
                    appendLine("    if (\$null -eq \$line) { break }")
                    appendLine("    if (\$line.StartsWith('__QUIT__')) { break }")
                    appendLine("    \$parts = \$line.Split([char]1, 2)")
                    appendLine("    if (\$parts.Length -eq 2) {")
                    appendLine("        \$tuning = \$parts[0].Split(',')")
                    appendLine("        \$voice.Rate = [int]\$tuning[0]")
                    appendLine("        \$voice.Pitch = [int]\$tuning[1]")
                    appendLine("        \$voice.Speak(\$parts[1])")
                    appendLine("    }")
                    appendLine("    [Console]::Out.WriteLine('ok')")
                    appendLine("    [Console]::Out.Flush()")
                    appendLine("}")
                    appendLine("\$voice.Dispose()")
                }
            )
        }

        val process = runCatching {
            ProcessBuilder(
                powershell,
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath
            ).start()
        }.getOrNull() ?: return null

        return runCatching {
            synchronized(lock) {
                hostProcess = process
                hostWriter = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
                hostReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                hostWriter
            }
        }.getOrNull()
    }

    private fun speakWithProcesses(
        text: String,
        rate: Float,
        pitch: Float,
        onDone: () -> Unit,
        onError: () -> Unit
    ) {
        val commands = buildFallbackCommands(text, rate, pitch)
        if (commands.isEmpty()) {
            onError()
            return
        }

        for (command in commands) {
            if (command.isEmpty()) continue
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            synchronized(lock) { activeProcess = process }
            process.inputStream.readBytes()
            if (process.waitFor() != 0) {
                synchronized(lock) { activeProcess = null }
                onError()
                return
            }
        }
        synchronized(lock) { activeProcess = null }
        onDone()
    }

    private fun buildFallbackCommands(text: String, rate: Float, pitch: Float): List<List<String>> {
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

    private fun applyPitch(source: File, pitch: Float): File? {
        val ffmpegPath = ffmpeg ?: return null
        if (pitch == 1f) return null

        val factor = pitch.coerceIn(0.5f, 2.0f)
        if (kotlin.math.abs(factor - 1f) < 0.01f) return null

        val shifted = File(tempDir, "sentence-pitched.wav")
        val process = runCatching {
            ProcessBuilder(
                ffmpegPath,
                "-y",
                "-loglevel", "error",
                "-i", source.absolutePath,
                "-filter:a", "asetrate=22050*$factor,aresample=22050,atempo=${1f / factor}",
                shifted.absolutePath
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return null

        process.inputStream.readBytes()
        return if (process.waitFor() == 0 && shifted.isFile) shifted else null
    }

    /**
     * Plays through the JDK mixer so no external player is required, which is
     * what makes Piper work on a stock Windows install. Falls back to an
     * external player when the mixer is unavailable.
     */
    /**
     * Plays through the JDK mixer, which is the only audio path guaranteed to
     * exist on a stock Windows install. A SourceDataLine is used rather than a
     * Clip because the clip line can report itself finished immediately on
     * some mixers, which would rush the narration. Falls back to an external
     * player if the mixer refuses the format.
     */
    private fun playAudio(wav: File): Boolean {
        if (!wav.isFile) return false

        val playedByMixer = runCatching {
            AudioSystem.getAudioInputStream(wav).use { stream ->
                val format = stream.format
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                val data = buffer.toByteArray()
                if (data.isEmpty()) return@use false

                val line = AudioSystem.getSourceDataLine(format)
                line.open(format)
                line.start()
                line.write(data, 0, data.size)
                line.drain()
                line.stop()
                line.close()
                true
            }
        }.getOrDefault(false)

        debug("mixer played=$playedByMixer file=${wav.name}")
        if (playedByMixer) return true

        val external = player ?: return false
        val process = runCatching {
            ProcessBuilder(external + wav.absolutePath).redirectErrorStream(true).start()
        }.getOrNull() ?: return false
        synchronized(lock) { activeProcess = process }
        process.inputStream.readBytes()
        return process.waitFor() == 0
    }

    private fun stopPlayback() {
        synchronized(lock) {
            runCatching { activeProcess?.destroy() }
            activeProcess = null
        }
    }

    override fun stop() {
        stopPlayback()
        synchronized(lock) {
            renderThread?.interrupt()
            renderThread = null
            pendingRenders.clear()
            readyAudio.forEach { runCatching { it.second.delete() } }
            readyAudio.clear()
            isPlaying = false
            liveRenderWaiting = false
        }
        synchronized(streamLock) {
            runCatching { streamWriter?.close() }
            runCatching { streamProcess?.destroy() }
            runCatching { streamDir?.deleteRecursively() }
            streamProcess = null
            streamWriter = null
            streamDir = null
            streamRate = -1f
        }
        if (isWindows) {
            runCatching {
                hostWriter?.write("__QUIT__")
                hostWriter?.flush()
            }
            runCatching { hostProcess?.destroy() }
            synchronized(lock) {
                hostWriter = null
                hostReader = null
                hostProcess = null
            }
        }
    }

    override fun shutdown() {
        stop()
        runCatching {
            File(tempDir, "sentence.wav").delete()
            tempDir.listFiles()
                ?.filter { it.name.startsWith("prerender-") || it.name.startsWith("render-") || it.name.startsWith("live-") }
                ?.forEach { it.delete() }
            File(tempDir, "sentence-pitched.wav").delete()
            tempDir.listFiles()?.filter { it.name.startsWith("render-") }?.forEach { it.delete() }
        }
    }

    private fun findPlayer(): List<String>? {
        findCommand("paplay")?.let { return listOf(it) }
        findCommand("aplay")?.let { return listOf(it, "-q") }
        findCommand("ffplay")?.let { return listOf(it, "-nodisp", "-autoexit", "-loglevel", "quiet") }
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
