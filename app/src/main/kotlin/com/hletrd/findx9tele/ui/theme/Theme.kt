package com.hletrd.findx9tele.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Accent used throughout the camera chrome (focus ring, active toggles, transfer label). */
private val AccentBlue = Color(0xFF4C9AFF)

private val TeleDarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF16324F),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = AccentBlue,
    onSecondary = Color.Black,
    tertiary = AccentBlue,
    onTertiary = Color.Black,
    background = Color(0xFF050505),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF5252),
    onError = Color.Black,
)

/**
 * App-wide dark theme for the camera UI. Deliberately deterministic (no dynamic color) so the
 * viewfinder chrome looks identical regardless of wallpaper or OS theme.
 */
@Composable
fun FindX9TeleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TeleDarkColorScheme,
        content = content,
    )
}
