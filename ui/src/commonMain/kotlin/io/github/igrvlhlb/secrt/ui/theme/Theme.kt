package io.github.igrvlhlb.secrt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF4A5C92),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE1FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF7A5735),
    secondaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFFBF8F3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF1A2D60),
    primaryContainer = Color(0xFF324478),
    onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = Color(0xFFEDBD92),
    secondaryContainer = Color(0xFF5F4020),
)

/** Platform-provided colors (e.g. Android 12+ dynamic color), or `null` to use the app's own. */
@Composable
expect fun platformColorScheme(dark: Boolean): ColorScheme?

@Composable
fun SecrtTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = platformColorScheme(dark) ?: if (dark) Dark else Light,
        content = content,
    )
}
