package com.ai.companion.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 粉色系主题 - 温柔可爱
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFD481B2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9F2),
    secondary = Color(0xFF9C6B9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEED0F8),
    tertiary = Color(0xFF7C5DB0),
    background = Color(0xFFFDF8FC),
    surface = Color(0xFFFFF8FC),
    surfaceVariant = Color(0xFFF0E0EC),
    onBackground = Color(0xFF1D1B1E),
    onSurface = Color(0xFF1D1B1E),
    outline = Color(0xFF7F747A),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE8A0CC),
    onPrimary = Color(0xFF3D1A35),
    primaryContainer = Color(0xFF5B3052),
    secondary = Color(0xFFCCB0D0),
    onSecondary = Color(0xFF33213A),
    secondaryContainer = Color(0xFF4A3751),
    tertiary = Color(0xFFD0B0FF),
    background = Color(0xFF1D1B1E),
    surface = Color(0xFF1D1B1E),
    surfaceVariant = Color(0xFF2E2A2D),
    onBackground = Color(0xFFE8E0E6),
    onSurface = Color(0xFFE8E0E6),
    outline = Color(0xFF9A8E94),
)

@Composable
fun AiCompanionTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}