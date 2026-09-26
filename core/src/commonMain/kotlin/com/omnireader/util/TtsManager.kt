package com.omnireader.util

import com.omnireader.data.model.TtsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TtsManager(private val backend: SpeechBackend) {

    private var isInitialized = false
    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex = 0
    private var interrupted = false
    private var onComplete: (() -> Unit)? = null
    private var scheduled: Thread? = null
    private var generation = 0

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _onSentenceHighlight = MutableStateFlow(-1)
    val onSentenceHighlight: StateFlow<Int> = _onSentenceHighlight.asStateFlow()

    fun initialize(onReady: () -> Unit = {}) {
        if (isInitialized) {
            onReady()
            return
        }
        backend.initialize {
            isInitialized = true
            val name = runCatching { backend.engineName() }.getOrDefault("")
            if (name.isNotEmpty()) _state.value = _state.value.copy(engineName = name)
            onReady()
        }
    }

    fun speak(text: String, chapterIndex: Int = 0) {
        if (!isInitialized) return

        interrupted = false
        generation++
        sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
            .map { it.trim() }
        currentSentenceIndex = 0
        _state.value = _state.value.copy(
            isPlaying = true,
            isPaused = false,
            currentChapterIndex = chapterIndex
        )
        _onSentenceHighlight.value = 0
        speakSentence(0)
    }

    private fun speakSentence(index: Int) {
        if (index >= sentences.size) return
        val rate = _state.value.speed
        val pitch = _state.value.pitch
        val token = generation

        backend.speak(
            text = sentences[index],
            rate = rate,
            pitch = pitch,
            onDone = {
                if (interrupted || token != generation) return@speak
                currentSentenceIndex++
                if (currentSentenceIndex < sentences.size) {
                    _onSentenceHighlight.value = currentSentenceIndex
                    scheduleNextSentence(token)
                } else {
                    _state.value = _state.value.copy(isPlaying = false, isPaused = false)
                    _onSentenceHighlight.value = -1
                    onComplete?.invoke()
                }
            },
            onError = {
                if (token != generation) return@speak
                _state.value = _state.value.copy(isPlaying = false, isPaused = false)
            }
        )

        prefetchFollowing(index, token, rate, pitch)
    }

    /**
     * Renders the next sentence while the current one is still playing so the
     * gap between sentences is only the configured pause, not engine start-up.
     */
    private fun prefetchFollowing(index: Int, token: Int, rate: Float, pitch: Float) {
        if (!_state.value.prefetchEnabled) return
        val next = sentences.getOrNull(index + 1) ?: return
        runCatching { backend.prefetch(next, rate, pitch) }
    }

    private fun scheduleNextSentence(token: Int) {
        val gapMs = (_state.value.sentenceGap.coerceIn(0f, 5f) * 1000f).toLong()
        if (gapMs <= 0L) {
            speakSentence(currentSentenceIndex)
            return
        }
        scheduled?.interrupt()
        val worker = Thread {
            try {
                Thread.sleep(gapMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!interrupted && token == generation) speakSentence(currentSentenceIndex)
        }
        worker.isDaemon = true
        worker.name = "omnireader-tts-gap"
        worker.start()
        scheduled = worker
    }

    fun pause() {
        interrupted = true
        generation++
        scheduled?.interrupt()
        backend.stop()
        _state.value = _state.value.copy(isPlaying = false, isPaused = true)
    }

    fun resume() {
        if (_state.value.isPlaying) return
        interrupted = false
        if (currentSentenceIndex < sentences.size) {
            _state.value = _state.value.copy(isPlaying = true, isPaused = false)
            speakSentence(currentSentenceIndex)
        }
    }

    fun stop() {
        interrupted = true
        generation++
        scheduled?.interrupt()
        backend.stop()
        currentSentenceIndex = 0
        sentences = emptyList()
        _state.value = TtsState(
            speed = _state.value.speed,
            pitch = _state.value.pitch,
            sentenceGap = _state.value.sentenceGap,
            prefetchEnabled = _state.value.prefetchEnabled,
            engineName = _state.value.engineName
        )
        _onSentenceHighlight.value = -1
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed.coerceIn(0.5f, 2.0f))
    }

    fun setPitch(pitch: Float) {
        _state.value = _state.value.copy(pitch = pitch.coerceIn(0.5f, 2.0f))
    }

    fun setSentenceGap(gap: Float) {
        _state.value = _state.value.copy(sentenceGap = gap.coerceIn(0f, 5f))
    }

    fun setPrefetchEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(prefetchEnabled = enabled)
    }

    fun setOnComplete(action: () -> Unit) {
        onComplete = action
    }

    fun shutdown() {
        interrupted = true
        generation++
        scheduled?.interrupt()
        backend.stop()
        backend.shutdown()
        isInitialized = false
    }
}
