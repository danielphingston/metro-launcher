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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.KeywordTarget
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SearchKeyword
import io.github.danielphingston.metro.data.SearchKeywords
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.data.WebsiteTemplate
import io.github.danielphingston.metro.ui.search.KeywordChipState
import io.github.danielphingston.metro.ui.search.KeywordRows
import io.github.danielphingston.metro.ui.search.OtherSearchApp
import io.github.danielphingston.metro.ui.search.SearchContent
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchInPhone
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.settings.SearchKeywordEditor
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.settings.SettingsPageFrame
import io.github.danielphingston.metro.ui.settings.SmartSearchSection
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Renders search keywords: the chip and highlighted row in the search panel, the settings list and the editor. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class SearchKeywordsScreenshotTest {
    private val apps = listOf(
        app("Chrome"), app("Camera"), app("Calendar"), app("Clock"), app("Messages"), app("Photos"), app("Flipkart Shop"),
        app("YouTube", "com.google.android.youtube"), app("Play Store", "com.android.vending"),
        app("Google Maps", "com.google.android.apps.maps"), app("Spotify", "com.spotify.music"),
        app("Amazon Shopping", "com.amazon.mShop.android.shopping"), app("Claude", "com.anthropic.claude"),
        app("Keep Notes", "com.example.keep"),
    )

    private object Phone : SearchInPhone {
        override fun resolves(intent: android.content.Intent) = true
        override fun amazonDomain(locale: Locale) = "amazon.in"
    }

    private val keywords = SearchKeywords.defaults + listOf(
        SearchKeyword("fk", KeywordTarget.Website("Flipkart", WebsiteTemplate.presets[0].template)),
        SearchKeyword("kp", KeywordTarget.InstalledApp("com.example.keep", "Keep Notes"), enabled = false),
    )

    private fun searchPanel(name: String, typed: String) {
        val settings = LauncherSettings(searchKeywords = keywords)
        val ctx = SearchContext(RuntimeEnvironment.getApplication(), apps, settings, Locale.ENGLISH)
        val match = SearchKeywords.detect(typed, keywords, active = true)!!
        val keywordRow = KeywordRows.build(match, ctx, Phone)
        val icons = IconCache(RuntimeEnvironment.getApplication())
        apps.forEach { icons.load(it, 28 * 3, true); icons.load(it, 44 * 3, false); icons.load(it, 20 * 3, false) }
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) {
                val rows = remember { searchRows(apps, match.rest, emptyList(), SearchEngine.GOOGLE, 0L, keyword = keywordRow) }
                val chip = KeywordChipState(
                    KeywordRows.chipLabel(match.entry.target, apps, settings.assistant),
                    KeywordRows.icon(match.entry.target, apps, settings.assistant),
                )
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SearchContent(
                        query = match.rest, onQueryChange = {}, onSubmit = {}, rows = rows, icons = icons,
                        monochrome = true, plainIcons = false, focusRequester = focus,
                        onActivate = { _, _ -> }, onLongClick = { _, _ -> }, onDismiss = {},
                        chip = chip,
                    )
                }
            }
        }
    }

    @Test
    fun amazon() = searchPanel("keywords_amazon", "a amazon basics")

    @Test
    fun youtube() = searchPanel("keywords_youtube", "yt lofi beats")

    @Test
    fun customWebsite() = searchPanel("keywords_website", "fk running shoes")

    private fun settingsPage(name: String, unlocked: Boolean, scroll: Int) {
        val settings = LauncherSettings(smartFeatures = SmartFeature.defaults, searchKeywords = keywords)
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        val icons = IconCache(RuntimeEnvironment.getApplication())
        apps.forEach { icons.load(it, 28 * 3, false) }
        val others = listOf(OtherSearchApp("com.example.keep", "Keep Notes", false))
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SettingsPageFrame(SettingsPage.SEARCH, rememberScrollState(scroll)) {
                        SmartSearchSection(settings, repository, unlocked, onUnlock = {}, otherSearchApps = others, apps = apps, icons = icons)
                    }
                }
            }
        }
    }

    @Test
    fun settingsUnlocked() = settingsPage("keywords_settings_unlocked", true, SCROLL_UNLOCKED)

    @Test
    fun settingsLocked() = settingsPage("keywords_settings_locked", false, SCROLL_LOCKED)

    private fun editor(name: String, original: SearchKeyword?, typed: String? = null) {
        val settings = LauncherSettings(searchKeywords = keywords)
        val others = listOf(OtherSearchApp("com.example.keep", "Keep Notes", false), OtherSearchApp("com.example.browser", "Browser", true))
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SettingsPageFrame(SettingsPage.SEARCH, rememberScrollState(if (original == null) 0 else 400)) {
                        SearchKeywordEditor(original, keywords, others, onSave = {}, onDelete = {}, onCancel = {}, initialText = typed)
                    }
                }
            }
        }
    }

    @Test
    fun editorAddWithAClash() = editor("keywords_editor_add", null, typed = "a")

    @Test
    fun editorEditWebsite() = editor("keywords_editor_edit", keywords.first { it.keyword == "fk" })

    private fun app(label: String, pkg: String = "com.example.${label.lowercase().replace(' ', '.')}") = AppInfo(
        key = AppKey(pkg, "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName(pkg, "Main"),
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

    private companion object {
        const val SCROLL_UNLOCKED = 3350
        const val SCROLL_LOCKED = 1500
    }
}
