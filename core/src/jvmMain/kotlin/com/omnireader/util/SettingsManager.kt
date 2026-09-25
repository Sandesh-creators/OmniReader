package com.omnireader.util

data class AppSettings(
    val nightMode: Boolean = false,
    val fontSize: Int = 16,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val autoAdvanceChapters: Boolean = true
)

class SettingsManager(private val store: PreferencesStore) {

    companion object {
        const val NIGHT_MODE_KEY = "night_mode"
        const val FONT_SIZE_KEY = "font_size"
        const val TTS_SPEED_KEY = "tts_speed"
        const val TTS_PITCH_KEY = "tts_pitch"
        const val AUTO_ADVANCE_KEY = "auto_advance_chapters"
    }

    suspend fun load(): AppSettings {
        return AppSettings(
            nightMode = store.get(NIGHT_MODE_KEY)?.toBooleanStrictOrNull() ?: false,
            fontSize = store.get(FONT_SIZE_KEY)?.toIntOrNull() ?: 16,
            ttsSpeed = store.get(TTS_SPEED_KEY)?.toFloatOrNull() ?: 1.0f,
            ttsPitch = store.get(TTS_PITCH_KEY)?.toFloatOrNull() ?: 1.0f,
            autoAdvanceChapters = store.get(AUTO_ADVANCE_KEY)?.toBooleanStrictOrNull() ?: true
        )
    }

    suspend fun saveNightMode(enabled: Boolean) = store.put(NIGHT_MODE_KEY, enabled.toString())

    suspend fun saveFontSize(size: Int) = store.put(FONT_SIZE_KEY, size.toString())

    suspend fun saveTtsSpeed(speed: Float) = store.put(TTS_SPEED_KEY, speed.toString())

    suspend fun saveTtsPitch(pitch: Float) = store.put(TTS_PITCH_KEY, pitch.toString())

    suspend fun saveAutoAdvanceChapters(enabled: Boolean) =
        store.put(AUTO_ADVANCE_KEY, enabled.toString())
}
