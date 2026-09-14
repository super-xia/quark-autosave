package com.quarksave.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与之前设计的浅色清爽风格一致
private val QuarkColorScheme = lightColorScheme(
    primary = Color(0xFF4A7DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE9FF),
    onPrimaryContainer = Color(0xFF0A2340),
    secondary = Color(0xFF3949AB),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF1F4FB),
    onSurfaceVariant = Color(0xFF8A93A0),
    outline = Color(0xFFC9D6F0)
)

@Composable
fun QuarkSaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QuarkColorScheme,
        content = content
    )
}