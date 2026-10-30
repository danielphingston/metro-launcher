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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.search.CalculatorProvider
import io.github.danielphingston.metro.ui.search.ItemAction
import io.github.danielphingston.metro.ui.search.ItemButton
import io.github.danielphingston.metro.ui.search.LauncherActionsProvider
import io.github.danielphingston.metro.ui.search.Placement
import io.github.danielphingston.metro.ui.search.ProvidedItems
import io.github.danielphingston.metro.ui.search.QuickActionsProvider
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchContent
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.search.SearchIcon
import io.github.danielphingston.metro.ui.search.SearchItem
import io.github.danielphingston.metro.ui.search.SearchProvider
import io.github.danielphingston.metro.ui.search.SettingsShortcutsProvider
import io.github.danielphingston.metro.ui.search.SearchHelp
import io.github.danielphingston.metro.ui.search.Suggestions
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.settings.SearchHelpPage
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.settings.SettingsPageFrame
import io.github.danielphingston.metro.ui.settings.SmartSearchSection
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the smart search result screens and settings sections on the JVM. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class SmartSearchScreenshotTest {
    private val apps = listOf(
        app("Chrome"), app("Camera"), app("Calendar"), app("Clock"), app("Editor"), app("Maps"), app("Messages"), app("Photos"),
        app("YouTube"), app("Google Maps"),
    )

    private fun ctx() = SearchContext(RuntimeEnvironment.getApplication(), apps, LauncherSettings())

    private fun provided(query: String, vararg providers: SearchProvider) = runBlocking {
        providers.map { ProvidedItems(it, it.results(query, ctx())) }.filter { it.items.isNotEmpty() }
    }

    @Test
    fun calculator() = capture("smart_calculator", "15% of 2400", provided("15% of 2400", CalculatorProvider))

    @Test
    fun units() = capture("smart_units", "5 km in miles", provided("5 km in miles", CalculatorProvider))

    @Test
    fun settingsShortcut() = capture(
        "smart_settings", "wifi",
        provided("wifi", SettingsShortcutsProvider(resolves = { _, _ -> true })),
    )

    @Test
    fun quickAction() = capture(
        "smart_quick_action", "timer 5 min",
        provided("timer 5 min", QuickActionsProvider(resolves = { _, _ -> true })),
    )

    @Test
    fun launcherAction() = capture(
        "smart_launcher", "edit",
        provided("edit", LauncherActionsProvider(resolves = { _, _ -> true })),
    )

    @Test
    fun matchingByInitials() = capture("smart_initials", "gm", emptyList())

    @Test
    fun suggestions() = capture(
        "smart_suggestions", "", emptyList(),
        Suggestions(listOf(apps[0], apps[8], apps[1], apps[6]), listOf("pizza near me", "5 km in miles", "wifi", "timer 10 min")),
    )

    /** The phrases to try lead the empty search, above the suggestions. */
    @Test
    fun suggestionsWithExamples() = capture(
        "smart_suggestions_examples", "", emptyList(),
        Suggestions(listOf(apps[0], apps[8], apps[1]), listOf("pizza near me", "wifi")),
        SearchHelp.examples({ true }, day = 0),
    )

    @Test
    fun helpPageLocked() = captureHelp("settings_search_help_locked", unlocked = false)

    @Test
    fun helpPageUnlocked() = captureHelp("settings_search_help_unlocked", unlocked = true)

    private fun captureHelp(name: String, unlocked: Boolean) {
        val settings = LauncherSettings()
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SettingsPageFrame(SettingsPage.SEARCH_HELP, rememberScrollState()) {
                        SearchHelpPage(settings, unlocked, onUnlock = {})
                    }
                }
            }
        }
    }

    /** A row with round action buttons, the shape the contacts feature will use. */
    @Test
    fun rowWithButtons() {
        val provider = object : SearchProvider {
            override val id = "demo"
            override val feature = null
            override val placement = Placement.MIDDLE
            override val section = "contacts"
            override suspend fun results(query: String, ctx: SearchContext) = emptyList<SearchItem>()
        }
        val noop = ItemAction.Run { }
        val item = SearchItem(
            "anna", "Anna Brandt", "+49 170 1234567", SearchIcon.Glyph("person", SearchGlyphs.info.draw), noop,
            buttons = listOf(
                ItemButton("call", SearchGlyphs.phone, noop),
                ItemButton("message", SearchGlyphs.message, noop),
            ),
        )
        capture("smart_buttons", "anna", listOf(ProvidedItems(provider, listOf(item))))
    }

    @Test
    fun searchSettingsLocked() = captureSettings("settings_search_locked", unlocked = false)

    @Test
    fun searchSettingsUnlocked() = captureSettings("settings_search_unlocked", unlocked = true)

    private fun captureSettings(name: String, unlocked: Boolean) {
        val settings = LauncherSettings()
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SettingsPageFrame(SettingsPage.SEARCH, rememberScrollState()) {
                        SmartSearchSection(settings, repository, unlocked, onUnlock = {})
                    }
                }
            }
        }
    }

    private fun capture(
        name: String,
        query: String,
        provided: List<ProvidedItems>,
        suggestions: Suggestions? = null,
        examples: List<SearchHelp.Example> = emptyList(),
    ) {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        apps.forEach { icons.load(it, 28 * 3, true); icons.load(it, 44 * 3, false) }
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings()) {
                val rows = remember { searchRows(apps, query, emptyList(), SearchEngine.GOOGLE, 0L, provided, suggestions, examples = examples) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SearchContent(
                        query = query, onQueryChange = {}, onSubmit = {}, rows = rows, icons = icons,
                        monochrome = true, plainIcons = false, focusRequester = focus,
                        onActivate = { _, _ -> }, onLongClick = { _, _ -> }, onDismiss = {},
                    )
                }
            }
        }
    }

    private fun app(label: String) = AppInfo(
        key = AppKey("com.example.${label.lowercase().replace(' ', '.')}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = true,
        loadIcon = { _, _ -> AdaptiveIconDrawable(ColorDrawable(0xFF444444.toInt()), Initial(label), Initial(label)) },
    )

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
