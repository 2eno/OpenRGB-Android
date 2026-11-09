package io.github.openrgbremote.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette for Android < 12 (no Material You): violet/cyan accents of RGB lighting.
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF6A3FD0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF006A6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF1F0),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Color(0xFFA43A6B),
    tertiaryContainer = Color(0xFFFFD9E4),
    onTertiaryContainer = Color(0xFF3E0021),
    background = Color(0xFFFDF7FF),
    surface = Color(0xFFFDF7FF),
    surfaceContainer = Color(0xFFF2ECF6),
    surfaceContainerHigh = Color(0xFFECE6F0),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF3A0093),
    primaryContainer = Color(0xFF5221B7),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFF80D4D4),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F4F),
    onSecondaryContainer = Color(0xFF9CF1F0),
    tertiary = Color(0xFFFFB0CC),
    tertiaryContainer = Color(0xFF852253),
    onTertiaryContainer = Color(0xFFFFD9E4),
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * App theme: Material You dynamic colors on Android 12+, otherwise the fallback palette.
 * Follows the system dark mode unless [darkTheme] is given.
 */
@Composable
fun OpenRgbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Semantic status colors that stay readable in light and dark mode. */
object StatusColors {
    @Composable
    fun connected(): Color = if (isSystemInDarkTheme()) Color(0xFF7DDC8B) else Color(0xFF1B7A33)
}
