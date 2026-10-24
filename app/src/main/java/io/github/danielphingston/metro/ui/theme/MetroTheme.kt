package io.github.danielphingston.metro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode

@Immutable
data class MetroColors(
    val background: Color,
    val foreground: Color,
    /** Secondary text, disabled letters in the jump list. */
    val subtle: Color,
    /** Context menus and dialogs: the inverse "chrome" of Windows Phone. */
    val chrome: Color,
    val accent: Color,
    val isDark: Boolean,
) {
    /** Tile text is always white, as on Windows Phone. */
    val onAccent: Color get() = Color.White

    companion object {
        fun of(dark: Boolean, accent: Color) = if (dark) {
            MetroColors(Color.Black, Color.White, Color(0xFF8E8E8E), Color(0xFF1F1F1F), accent, true)
        } else {
            MetroColors(Color.White, Color.Black, Color(0xFF6E6E6E), Color(0xFFDDDDDD), accent, false)
        }
    }
}

/** The twenty Windows Phone 8 accent colours. */
val MetroAccents: List<Pair<String, Color>> = listOf(
    "lime" to Color(0xFFA4C400), "green" to Color(0xFF60A917), "emerald" to Color(0xFF008A00),
    "teal" to Color(0xFF00ABA9), "cyan" to Color(0xFF1BA1E2), "cobalt" to Color(0xFF0050EF),
    "indigo" to Color(0xFF6A00FF), "violet" to Color(0xFFAA00FF), "pink" to Color(0xFFF472D0),
    "magenta" to Color(0xFFD80073), "crimson" to Color(0xFFA20025), "red" to Color(0xFFE51400),
    "orange" to Color(0xFFFA6800), "amber" to Color(0xFFF0A30A), "yellow" to Color(0xFFE3C800),
    "brown" to Color(0xFF825A2C), "olive" to Color(0xFF6D8764), "steel" to Color(0xFF647687),
    "mauve" to Color(0xFF76608A), "taupe" to Color(0xFF87794E),
)

/**
 * Segoe WP is proprietary. The system sans-serif in light weights gets close; drop Selawik
 * (Microsoft's OFL Segoe UI substitute) into res/font and point this at it for a closer match.
 */
val MetroFont: FontFamily = FontFamily.SansSerif

object MetroType {
    fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified) =
        TextStyle(fontFamily = MetroFont, fontSize = size, fontWeight = weight, color = color)

    /** Big page titles: "settings", "start+theme". */
    val pageTitle = style(56.sp, FontWeight.Light).copy(letterSpacing = (-1).sp)
    val appName = style(14.sp, FontWeight.SemiBold)
    val sectionTitle = style(26.sp, FontWeight.Light)
    val body = style(20.sp, FontWeight.Normal)
    val caption = style(15.sp)
}

val LocalMetroColors = staticCompositionLocalOf { MetroColors.of(true, Color(LauncherSettings.DEFAULT_ACCENT)) }

object Metro {
    val colors: MetroColors
        @Composable get() = LocalMetroColors.current
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun MetroTheme(settings: LauncherSettings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings.theme)
    val colors = remember(dark, settings.accent) { MetroColors.of(dark, Color(settings.accent)) }
    val selection = remember(colors) { TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.4f)) }
    CompositionLocalProvider(
        LocalMetroColors provides colors,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}
