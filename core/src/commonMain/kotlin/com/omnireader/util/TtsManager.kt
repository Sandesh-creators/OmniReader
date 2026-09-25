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
            onReady()
        }
    }

    fun speak(text: String, chapterIndex: Int = 0) {
        if (!isInitialized) return

        interrupted = false
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
        backend.speak(
            text = sentences[index],
            rate = _state.value.speed,
            pitch = _state.value.pitch,
            onDone = {
                if (interrupted) return@speak
                currentSentenceIndex++
                if (currentSentenceIndex < sentences.size) {
                    _onSentenceHighlight.value = currentSentenceIndex
                    speakSentence(currentSentenceIndex)
                } else {
                    _state.value = _state.value.copy(isPlaying = false, isPaused = false)
                    _onSentenceHighlight.value = -1
                    onComplete?.invoke()
                }
            },
            onError = {
                _state.value = _state.value.copy(isPlaying = false, isPaused = false)
            }
        )
    }

    fun pause() {
        interrupted = true
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
        backend.stop()
        currentSentenceIndex = 0
        sentences = emptyList()
        _state.value = TtsState(speed = _state.value.speed, pitch = _state.value.pitch)
        _onSentenceHighlight.value = -1
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed.coerceIn(0.5f, 2.0f))
    }

    fun setPitch(pitch: Float) {
        _state.value = _state.value.copy(pitch = pitch.coerceIn(0.5f, 2.0f))
    }

    fun setOnComplete(action: () -> Unit) {
        onComplete = action
    }

    fun shutdown() {
        interrupted = true
        backend.stop()
        backend.shutdown()
        isInitialized = false
    }
}
