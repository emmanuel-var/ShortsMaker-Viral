package com.shortsmaker.viral.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Violet = Color(0xFF7C4DFF)
val Pink = Color(0xFFFF2D95)
val Orange = Color(0xFFFF8A00)
val BrandGradient = Brush.linearGradient(listOf(Violet, Pink, Orange))

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC3B3FF),
    onPrimary = Color(0xFF2B0F78),
    primaryContainer = Color(0xFF4326A8),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFFFB1D0),
    onSecondary = Color(0xFF5E1138),
    tertiary = Color(0xFFFFB870),
    background = Color(0xFF0F0C17),
    onBackground = Color(0xFFE8E1F0),
    surface = Color(0xFF16121F),
    onSurface = Color(0xFFE8E1F0),
    surfaceVariant = Color(0xFF2B2438),
    onSurfaceVariant = Color(0xFFCBC3D8),
    surfaceContainer = Color(0xFF1D1829),
    surfaceContainerHigh = Color(0xFF272133),
    outline = Color(0xFF948DA3),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B34D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1B0063),
    secondary = Color(0xFFB4005F),
    tertiary = Color(0xFF9A5100),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF),
    surfaceVariant = Color(0xFFE8E0F0),
    surfaceContainer = Color(0xFFF1EBF8),
    surfaceContainerHigh = Color(0xFFEBE4F2),
)

@Composable
fun ShortsMakerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
