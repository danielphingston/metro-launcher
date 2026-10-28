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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.danielphingston.metro.R
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.ui.components.LocalMotion
import io.github.danielphingston.metro.ui.components.Motion

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
    /**
     * Tile text is always white, as on Windows Phone. Tiles are the accent at the configured
     * transparency, often over a photo or the dark page, so white is the right ink there whatever
     * the accent is. For surfaces that are the solid accent colour use [onSolidAccent].
     */
    val onAccent: Color get() = Color.White

    /**
     * White or black, whichever reads better on the solid [accent]: white on lime, yellow, amber,
     * orange, pink and cyan is only 1.5:1 to 3:1. For buttons, toggles and other opaque accent fills.
     */
    val onSolidAccent: Color get() = readableOn(accent)

    /** Buttons, inactive toggle tracks and other raised controls on the page. */
    val control: Color get() = if (isDark) Color(0xFF333333) else Color(0xFFCCCCCC)

    /** Dims the page behind flyouts. */
    val scrim: Color get() = Color.Black.copy(alpha = if (isDark) 0.6f else 0.4f)

    companion object {
        fun of(dark: Boolean, accent: Color) = if (dark) {
            MetroColors(Color.Black, Color.White, Color(0xFF8E8E8E), Color(0xFF1F1F1F), accent, true)
        } else {
            MetroColors(Color.White, Color.Black, Color(0xFF6E6E6E), Color(0xFFDDDDDD), accent, false)
        }
    }
}

/** WCAG 2.x contrast ratio between two opaque colours, 1..21. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * White or black text on [background]: whichever has the higher contrast, except that white is
 * kept while it still passes WCAG AA for normal text (4.5:1), which is the Metro look.
 */
fun readableOn(background: Color): Color {
    val white = contrastRatio(Color.White, background)
    return if (white >= 4.5f || white >= contrastRatio(Color.Black, background)) Color.White else Color.Black
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
 * Segoe WP is proprietary. Open Sans (SIL OFL, see assets/licenses) is a humanist sans with the
 * same open, friendly shapes; only the three weights Metro uses are bundled.
 */
val MetroFont: FontFamily = FontFamily(
    Font(R.font.opensans_light, FontWeight.Light),
    Font(R.font.opensans_regular, FontWeight.Normal),
    Font(R.font.opensans_semibold, FontWeight.SemiBold),
)

/**
 * The type scale: light for big titles, regular for reading, semibold only for small labels.
 * Tile text is sized from the tile instead (see TileMetrics) and uses [style] directly.
 */
object MetroType {
    fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified) =
        TextStyle(fontFamily = MetroFont, fontSize = size, fontWeight = weight, color = color)

    /** Big page titles: "settings", "widgets". */
    val pageTitle = style(46.sp, FontWeight.Light).copy(letterSpacing = (-0.5).sp)

    /** Small caps line above a page title. */
    val appName = style(13.sp, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp)

    /** Section headers in settings, group headers in pickers. */
    val sectionTitle = style(24.sp, FontWeight.Light)

    /** Settings rows and body copy. */
    val body = style(18.sp)

    /** App names in the app list. */
    val listItem = style(18.sp)

    /** Context menu entries. */
    val menuItem = style(20.sp)

    /** Button labels. */
    val button = style(16.sp)

    /** Captions under settings, "recently added", search hint. */
    val caption = style(14.sp)
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
    val colors = remember(dark, settings.effectiveAccent) { MetroColors.of(dark, Color(settings.effectiveAccent)) }
    val selection = remember(colors) { TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.4f)) }
    val motion = remember(settings) { Motion.of(settings) }
    CompositionLocalProvider(
        LocalMetroColors provides colors,
        LocalTextSelectionColors provides selection,
        LocalMotion provides motion,
        content = content,
    )
}
