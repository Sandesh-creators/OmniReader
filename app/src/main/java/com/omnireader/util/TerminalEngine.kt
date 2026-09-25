package com.omnireader.util

import com.omnireader.data.model.LineType
import com.omnireader.data.model.TerminalLine
import com.omnireader.data.model.TerminalSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

class TerminalEngine {

    private val _session = MutableStateFlow(TerminalSession(command = ""))
    val session: StateFlow<TerminalSession> = _session.asStateFlow()

    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cap stored output so long-running commands (logcat, tail -f, etc.) can't
    // balloon the transcript into an O(n^2) copy-per-line + unbounded memory.
    // Only the last MAX_OUTPUT_LINES are kept and every publish is a bounded copy.
    private var wasCancelled = false
    private var wasCleared = false

    companion object {
        private const val MAX_OUTPUT_LINES = 1000
    }

    private fun truncatingOutput(): ArrayDeque<TerminalLine> = ArrayDeque()

    private fun appendLine(output: ArrayDeque<TerminalLine>, line: TerminalLine) {
        output.addLast(line)
        while (output.size > MAX_OUTPUT_LINES) output.removeFirst()
        _session.value = _session.value.copy(output = output.toList())
    }

    private fun findShell(): String {
        val candidates = listOf("/system/bin/sh", "/bin/sh", "/data/data/com.termux/files/usr/bin/sh")
        for (path in candidates) {
            if (java.io.File(path).exists()) return path
        }
        return "sh"
    }

    // Android does not ship bash (Xiaomi MIUI/HyperOS included) - only sh via
    // toybox/mksh. Prefer bash when the user has it (e.g. Termux), otherwise
    // fall back to the system shell so .sh scripts actually execute.
    private fun findScriptShell(): String {
        val bashCandidates = listOf(
            "/system/bin/bash",
            "/bin/bash",
            "/usr/bin/bash",
            "/data/data/com.termux/files/usr/bin/bash"
        )
        for (path in bashCandidates) {
            if (java.io.File(path).exists()) return path
        }
        return findShell()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun executeCommand(command: String) {
        wasCancelled = false
        wasCleared = false
        _session.value = TerminalSession(command = command, isRunning = true)

        scope.launch {
            try {
                val shell = findShell()
                val workDir = java.io.File("/sdcard").let { if (it.exists()) it else java.io.File("/") }

                val processBuilder = ProcessBuilder(shell, "-c", command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["HOME"] = "/sdcard"
                        environment()["PATH"] = "/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin"
                    }

                val proc = processBuilder.start()
                process = proc

                val outputLines = truncatingOutput()
                appendLine(outputLines, TerminalLine(content = "$ $command", type = LineType.INPUT))

                val reader = BufferedReader(InputStreamReader(proc.inputStream), 8192)

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { appendLine(outputLines, TerminalLine(content = it, type = LineType.OUTPUT)) }
                }

                if (wasCleared) return@launch
                if (wasCancelled) {
                    appendLine(outputLines, TerminalLine(content = "Process cancelled by user", type = LineType.SYSTEM))
                    _session.value = _session.value.copy(isRunning = false, exitCode = -1)
                    return@launch
                }

                val exitCode = try { proc.waitFor() } catch (_: Exception) { -1 }
                appendLine(outputLines, TerminalLine(
                    content = if (exitCode == 0) "Command completed successfully" else "Process exited with code $exitCode",
                    type = if (exitCode == 0) LineType.SYSTEM else LineType.ERROR
                ))

                _session.value = _session.value.copy(
                    isRunning = false,
                    exitCode = exitCode
                )
            } catch (e: SecurityException) {
                if (wasCleared) return@launch
                if (wasCancelled) {
                    _session.value = _session.value.copy(isRunning = false, exitCode = -1)
                    return@launch
                }
                val outputLines = truncatingOutput()
                appendLine(outputLines, TerminalLine(
                    content = "Permission denied: ${e.message}",
                    type = LineType.ERROR
                ))
                appendLine(outputLines, TerminalLine(
                    content = "Grant storage permissions in Android Settings > Apps > OmniReader > Permissions",
                    type = LineType.SYSTEM
                ))
                _session.value = _session.value.copy(output = outputLines.toList(), isRunning = false, exitCode = -1)
            } catch (e: Exception) {
                if (wasCleared) return@launch
                if (wasCancelled) {
                    _session.value = _session.value.copy(isRunning = false, exitCode = -1)
                    return@launch
                }
                val outputLines = truncatingOutput()
                appendLine(outputLines, TerminalLine(
                    content = "Error: ${e.javaClass.simpleName}: ${e.message}",
                    type = LineType.ERROR
                ))
                val msg = e.message.orEmpty()
                if (msg.contains("denied", ignoreCase = true) || msg.contains("read-only", ignoreCase = true)) {
                    appendLine(outputLines, TerminalLine(
                        content = "Shared-storage files can't be read without \"All files access\" (Android 11+; grant it in Settings > Apps > OmniReader > All files access).",
                        type = LineType.SYSTEM
                    ))
                }
                _session.value = _session.value.copy(output = outputLines.toList(), isRunning = false, exitCode = -1)
            }
        }
    }

    fun executeShellScript(scriptPath: String) {
        val file = java.io.File(scriptPath)
        val scriptShell = findScriptShell()

        if (!file.exists()) {
            val sharedStorageHint = if (scriptPath.startsWith("/sdcard") || scriptPath.startsWith("/storage/")) {
                " Enable \"All files access\" for OmniReader (Android 11+, e.g. MIUI/HyperOS) if the script is in shared storage."
            } else ""
            val outputLines = mutableListOf(
                TerminalLine(content = "$ ${shellQuote(scriptShell)} ${shellQuote(scriptPath)}", type = LineType.INPUT),
                TerminalLine(content = "File not found: $scriptPath$sharedStorageHint", type = LineType.ERROR)
            )
            _session.value = TerminalSession(command = "${shellQuote(scriptShell)} ${shellQuote(scriptPath)}", output = outputLines, exitCode = -1)
            return
        }

        executeCommand("chmod +x ${shellQuote(scriptPath)} 2>/dev/null; ${shellQuote(scriptShell)} ${shellQuote(scriptPath)}")
    }

    fun executeExeViaWine(exePath: String) {
        val warningLines = mutableListOf(
            TerminalLine(content = "Compatibility Warning", type = LineType.SYSTEM),
            TerminalLine(content = "Running Windows executables requires Wine/Proot.", type = LineType.SYSTEM),
            TerminalLine(content = "Architecture: ARM (most Android devices are not x86)", type = LineType.SYSTEM),
            TerminalLine(content = "Attempting: wine '$exePath'", type = LineType.OUTPUT),
            TerminalLine(content = "", type = LineType.OUTPUT)
        )

        _session.value = TerminalSession(
            command = "wine '$exePath'",
            output = warningLines,
            isRunning = true
        )

        scope.launch {
            try {
                val processBuilder = ProcessBuilder("wine", exePath)
                    .redirectErrorStream(true)

                val wineProcess = processBuilder.start()
                process = wineProcess
                val reader = BufferedReader(InputStreamReader(wineProcess.inputStream), 8192)

                val outputLines = truncatingOutput()
                warningLines.forEach { outputLines.addLast(it) }
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    line?.let { appendLine(outputLines, TerminalLine(content = it, type = LineType.OUTPUT)) }
                }

                if (wasCleared) return@launch
                if (wasCancelled) {
                    appendLine(outputLines, TerminalLine(content = "Process cancelled by user", type = LineType.SYSTEM))
                    _session.value = _session.value.copy(isRunning = false, exitCode = -1)
                    return@launch
                }

                val exitCode = try { wineProcess.waitFor() } catch (_: Exception) { -1 }
                appendLine(outputLines, TerminalLine(
                    content = if (exitCode == 0) "Process completed" else "Process failed with code $exitCode",
                    type = if (exitCode == 0) LineType.SYSTEM else LineType.ERROR
                ))

                _session.value = _session.value.copy(isRunning = false, exitCode = exitCode)
            } catch (e: Exception) {
                if (wasCleared) return@launch
                if (wasCancelled) {
                    _session.value = _session.value.copy(isRunning = false, exitCode = -1)
                    return@launch
                }
                val errorLines = _session.value.output.toMutableList()
                errorLines.add(TerminalLine(
                    content = "Wine not available: ${e.message}",
                    type = LineType.ERROR
                ))
                errorLines.add(TerminalLine(
                    content = "Install Wine via: pkg install wine (Termux) or apt install wine",
                    type = LineType.SYSTEM
                ))
                _session.value = _session.value.copy(output = errorLines, isRunning = false, exitCode = -1)
            }
        }
    }

    fun cancel() {
        wasCancelled = true
        try { process?.destroy() } catch (_: Exception) {}
    }

    fun clear() {
        wasCleared = true
        try { process?.destroy() } catch (_: Exception) {}
        _session.value = TerminalSession(command = "")
    }

    fun destroy() {
        scope.cancel()
        try { process?.destroy() } catch (_: Exception) {}
    }
}
