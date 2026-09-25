package com.omnireader.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.omnireader.data.model.TtsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var sentences: List<String> = emptyList()

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _onSentenceHighlight = MutableStateFlow(-1)
    val onSentenceHighlight: StateFlow<Int> = _onSentenceHighlight.asStateFlow()

    private var onComplete: (() -> Unit)? = null
    private var currentSentenceIndex = 0
    private var interrupted = false

    fun initialize(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    engine.language = Locale.US
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            if (interrupted) return
                            currentSentenceIndex++
                            if (currentSentenceIndex < sentences.size) {
                                _onSentenceHighlight.value = currentSentenceIndex
                                speakSentence(currentSentenceIndex)
                            } else {
                                _state.value = _state.value.copy(isPlaying = false, isPaused = false)
                                _onSentenceHighlight.value = -1
                                onComplete?.invoke()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _state.value = _state.value.copy(isPlaying = false, isPaused = false)
                        }
                    })
                }
                isInitialized = true
                onReady()
            }
        }
    }

    fun speak(text: String, chapterIndex: Int = 0) {
        if (!isInitialized) return

        interrupted = false
        sentences = EpubParser(context).splitIntoSentences(text)
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
        tts?.setSpeechRate(_state.value.speed)
        tts?.setPitch(_state.value.pitch)
        tts?.speak(sentences[index], TextToSpeech.QUEUE_FLUSH, null, "sentence_$index")
    }

    fun pause() {
        interrupted = true
        tts?.stop()
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
        tts?.stop()
        currentSentenceIndex = 0
        sentences = emptyList()
        _state.value = TtsState()
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
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
