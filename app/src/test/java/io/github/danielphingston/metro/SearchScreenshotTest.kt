package io.github.danielphingston.metro

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.ui.search.SearchContent
import io.github.danielphingston.metro.ui.search.ShortcutHit
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the search overlay with typed queries on the JVM. Output: app/build/outputs/roborazzi/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SearchScreenshotTest {
    private val now = System.currentTimeMillis()
    private val day = 24 * 60 * 60 * 1000L

    private val apps = listOf(
        app("Gmail"), app("Mail"), app("Mailbox Plus"), app("Calendar"), app("Camera"), app("Clock"),
        app("Maps"), app("Messages"), app("Music"), app("Phone"), app("Photos"), app("Store"),
        app("Fresh Notes", installTime = now - day),
    )

    private val shortcuts = listOf(
        ShortcutHit("com.example.gmail", "compose", 0, "Compose mail", "Write a new email"),
        ShortcutHit("com.example.mail", "inbox", 0, "Unread mail", null),
    )

    @Test
    fun searchDark() = capture("search_dark", ThemeMode.DARK, "mail")

    @Test
    fun searchLight() = capture("search_light", ThemeMode.LIGHT, "mail", accent = 0xFFE51400.toInt())

    @Test
    fun searchAddress() = capture("search_address", ThemeMode.DARK, "wikipedia.org/wiki/Zürich", shortcuts = emptyList())

    @Test
    fun searchEmpty() = capture("search_empty", ThemeMode.DARK, "", shortcuts = emptyList())

    private fun capture(
        name: String,
        theme: ThemeMode,
        query: String,
        accent: Int = LauncherSettings.DEFAULT_ACCENT,
        shortcuts: List<ShortcutHit> = this.shortcuts,
    ) {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        // Icons load on a background thread the capture doesn't wait for: render them up front.
        apps.forEach { icons.load(it, (28 * 3), true) }
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            MetroTheme(LauncherSettings(theme = theme, accent = accent)) {
                val rows = remember { searchRows(apps, query, shortcuts, SearchEngine.GOOGLE, now) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    BehindTiles()
                    Box(Modifier.fillMaxSize().background(Metro.colors.background.copy(alpha = 0.96f))) {
                        SearchContent(
                            query = query,
                            onQueryChange = {},
                            onSubmit = {},
                            rows = rows,
                            icons = icons,
                            monochrome = true,
                            focusRequester = focus,
                            onActivate = { _, _ -> },
                            onLongClick = { _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    /** A few tiles under the panel, to judge how translucent it is. */
    @Composable
    private fun BehindTiles() {
        val accent = Metro.colors.accent
        Box(Modifier.offset(8.dp, 52.dp).size(195.dp).background(accent))
        Box(Modifier.offset(207.dp, 52.dp).size(195.dp).background(accent))
        Box(Modifier.offset(8.dp, 255.dp).size(394.dp, 195.dp).background(accent))
    }

    private fun app(label: String, installTime: Long = 0L) = AppInfo(
        key = AppKey("com.example.${label.lowercase().replace(' ', '.')}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = true,
        installTime = installTime,
        loadIcon = { _, _ -> AdaptiveIconDrawable(ColorDrawable(0xFF444444.toInt()), Initial(label), Initial(label)) },
    )

    /** Stand-in for a themed icon: the app's initial in white, in the adaptive safe zone. */
    private class Initial(label: String) : Drawable() {
        private val letter = label.take(1)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }

        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.textSize = b.height() * 0.4f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() + paint.textSize * 0.35f, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Required override", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
