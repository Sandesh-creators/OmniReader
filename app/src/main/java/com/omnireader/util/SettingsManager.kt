package com.omnireader.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("app_settings")

data class AppSettings(
    val nightMode: Boolean = false,
    val fontSize: Int = 16,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val autoAdvanceChapters: Boolean = true
)

class SettingsManager(private val context: Context) {

    companion object {
        private val NIGHT_MODE_KEY = booleanPreferencesKey("night_mode")
        private val FONT_SIZE_KEY = intPreferencesKey("font_size")
        private val TTS_SPEED_KEY = floatPreferencesKey("tts_speed")
        private val TTS_PITCH_KEY = floatPreferencesKey("tts_pitch")
        private val AUTO_ADVANCE_KEY = booleanPreferencesKey("auto_advance_chapters")
    }

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        try {
            val prefs = context.settingsDataStore.data.first()
            AppSettings(
                nightMode = prefs[NIGHT_MODE_KEY] ?: false,
                fontSize = prefs[FONT_SIZE_KEY] ?: 16,
                ttsSpeed = prefs[TTS_SPEED_KEY] ?: 1.0f,
                ttsPitch = prefs[TTS_PITCH_KEY] ?: 1.0f,
                autoAdvanceChapters = prefs[AUTO_ADVANCE_KEY] ?: true
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }

    suspend fun saveNightMode(enabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            context.settingsDataStore.edit { prefs -> prefs[NIGHT_MODE_KEY] = enabled }
        } catch (_: Exception) {}
    }

    suspend fun saveFontSize(size: Int) = withContext(Dispatchers.IO) {
        try {
            context.settingsDataStore.edit { prefs -> prefs[FONT_SIZE_KEY] = size }
        } catch (_: Exception) {}
    }

    suspend fun saveTtsSpeed(speed: Float) = withContext(Dispatchers.IO) {
        try {
            context.settingsDataStore.edit { prefs -> prefs[TTS_SPEED_KEY] = speed }
        } catch (_: Exception) {}
    }

    suspend fun saveTtsPitch(pitch: Float) = withContext(Dispatchers.IO) {
        try {
            context.settingsDataStore.edit { prefs -> prefs[TTS_PITCH_KEY] = pitch }
        } catch (_: Exception) {}
    }

    suspend fun saveAutoAdvanceChapters(enabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            context.settingsDataStore.edit { prefs -> prefs[AUTO_ADVANCE_KEY] = enabled }
        } catch (_: Exception) {}
    }
}