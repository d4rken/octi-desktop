package eu.darken.octi.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import eu.darken.octi.desktop.storage.ThemeMode

/**
 * Material 3 theme wrapper. Uses static M3 default color schemes — dynamic color isn't available
 * on desktop (no system accent extraction API), and the Octi visual identity uses fixed brand
 * colors anyway.
 */
@Composable
fun OctiTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (useDark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()
