package com.shortsmaker.viral.data

import android.content.Context
import androidx.core.content.edit
import com.shortsmaker.viral.domain.ExportResolution
import com.shortsmaker.viral.domain.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val defaultLanguage: Language = Language.ES,
    val resolution: ExportResolution = ExportResolution.P1080,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        AppSettings(
            defaultLanguage = Language.fromCode(prefs.getString(KEY_LANGUAGE, null)),
            resolution = ExportResolution.fromName(prefs.getString(KEY_RESOLUTION, null)),
        ),
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setDefaultLanguage(language: Language) {
        prefs.edit { putString(KEY_LANGUAGE, language.code) }
        _settings.value = _settings.value.copy(defaultLanguage = language)
    }

    fun setResolution(resolution: ExportResolution) {
        prefs.edit { putString(KEY_RESOLUTION, resolution.name) }
        _settings.value = _settings.value.copy(resolution = resolution)
    }

    private companion object {
        const val KEY_LANGUAGE = "default_language"
        const val KEY_RESOLUTION = "export_resolution"
    }
}
