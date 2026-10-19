package app.hoyotray.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Good = Color(0xFF4CC38A)
val Warn = Color(0xFFFF9F43)
val Blue = Color(0xFF5AA9FF)

private val Dark = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    background = Color(0xFF15161A),
    surface = Color(0xFF15161A),
    surfaceContainer = Color(0xFF212228),
    surfaceContainerHigh = Color(0xFF2A2B32),
    onSurface = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFFA6A6AD),
    outline = Color(0xFF3A3B42),
    secondaryContainer = Color(0xFF2B3C55),
    onSecondaryContainer = Color(0xFFDCE8FF),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2F7DE1),
    onPrimary = Color.White,
    background = Color(0xFFF6F6F8),
    surface = Color(0xFFF6F6F8),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEDF1),
    onSurfaceVariant = Color(0xFF66666E),
)

@Composable
fun HoyoTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
