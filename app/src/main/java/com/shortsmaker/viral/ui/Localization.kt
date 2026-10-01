package com.shortsmaker.viral.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.shortsmaker.viral.domain.Language
import java.util.Locale

/**
 * Cambia el idioma de TODA la interfaz en caliente: todas las pantallas leen sus textos con `stringResource`,
 * que usa los recursos de `LocalContext`. Al sustituirlos por unos con otro `Locale`, la UI se recompone al
 * instante, sin recrear la actividad ni reiniciar la app.
 */
@Composable
fun ProvideAppLanguage(language: Language, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(language, base) { LocalizedContext(base, Locale.forLanguageTag(language.code)) }
    val configuration = remember(localized) { localized.resources.configuration }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides configuration,
        content = content,
    )
}

/** Mantiene la cadena de ContextWrapper (para poder encontrar la Activity) pero con recursos del idioma elegido. */
private class LocalizedContext(base: Context, locale: Locale) : ContextWrapper(base) {
    private val localizedResources: Resources = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        },
    ).resources

    override fun getResources(): Resources = localizedResources
}
