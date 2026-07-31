package com.gamewishingwell.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4BFF),
    onPrimary = Color.White,
    secondary = Color(0xFFFF6B6B),
    tertiary = Color(0xFF00B8A0),
    background = Color(0xFFF7F6FF),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9D8FFF),
    onPrimary = Color(0xFF231D66),
    secondary = Color(0xFFFF8A8A),
    tertiary = Color(0xFF4FDAC6),
    background = Color(0xFF14141E),
    surface = Color(0xFF1E1E2C)
)

@Composable
fun WishwellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
