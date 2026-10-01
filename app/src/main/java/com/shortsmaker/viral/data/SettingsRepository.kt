package com.shortsmaker.viral.data

import android.content.Context
import androidx.core.content.edit
import com.shortsmaker.viral.domain.ExportResolution
import com.shortsmaker.viral.domain.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class AppSettings(
    /** Idioma de toda la interfaz (se aplica al instante, sin reiniciar). */
    val uiLanguage: Language,
    /** Último idioma de video elegido: se propone por defecto al importar. */
    val videoLanguage: Language,
    val resolution: ExportResolution = ExportResolution.P1080,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        // Primer uso: la interfaz y el video usan el idioma del dispositivo si está soportado (si no, inglés).
        val device = Language.fromDevice(Locale.getDefault().language)
        val ui = Language.fromCode(prefs.getString(KEY_UI_LANGUAGE, null)) ?: device
        val video = Language.fromCode(prefs.getString(KEY_VIDEO_LANGUAGE, null)) ?: ui
        return AppSettings(ui, video, ExportResolution.fromName(prefs.getString(KEY_RESOLUTION, null)))
    }

    fun setUiLanguage(language: Language) {
        prefs.edit { putString(KEY_UI_LANGUAGE, language.code) }
        _settings.value = _settings.value.copy(uiLanguage = language)
    }

    fun setVideoLanguage(language: Language) {
        prefs.edit { putString(KEY_VIDEO_LANGUAGE, language.code) }
        _settings.value = _settings.value.copy(videoLanguage = language)
    }

    fun setResolution(resolution: ExportResolution) {
        prefs.edit { putString(KEY_RESOLUTION, resolution.name) }
        _settings.value = _settings.value.copy(resolution = resolution)
    }

    private companion object {
        const val KEY_UI_LANGUAGE = "ui_language"
        const val KEY_VIDEO_LANGUAGE = "video_language"
        const val KEY_RESOLUTION = "export_resolution"
    }
}
