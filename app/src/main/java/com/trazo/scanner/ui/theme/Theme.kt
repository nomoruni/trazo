package com.trazo.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = PaperDeep,
    onPrimaryContainer = Ink,
    secondary = Gold,
    onSecondary = Paper,
    secondaryContainer = GoldSoft,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    outline = Color(0xFF9E9E9E),
    error = ErrorRed,
)

private val DarkScheme = darkColorScheme(
    primary = NightInk,
    onPrimary = Night,
    primaryContainer = NightSurface,
    onPrimaryContainer = NightInk,
    secondary = GoldSoft,
    onSecondary = Night,
    secondaryContainer = Color(0xFF3A3427),
    onSecondaryContainer = GoldSoft,
    background = Night,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF262E37),
    onSurfaceVariant = Color(0xFFB6C0C9),
    outline = Color(0xFF5A6672),
    error = Color(0xFFFFB4AB),
)

@Composable
fun TrazoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = TrazoTypography,
        content = content,
    )
}
