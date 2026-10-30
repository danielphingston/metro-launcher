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
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SearchInOrder
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.ui.search.AskAssistantProvider
import io.github.danielphingston.metro.ui.search.CalculatorProvider
import io.github.danielphingston.metro.ui.search.ContactDetails
import io.github.danielphingston.metro.ui.search.ContactStub
import io.github.danielphingston.metro.ui.search.ContactsProvider
import io.github.danielphingston.metro.ui.search.ContactsSource
import io.github.danielphingston.metro.ui.search.OtherSearchApp
import io.github.danielphingston.metro.ui.search.ProvidedItems
import io.github.danielphingston.metro.ui.search.SearchContent
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchInPhone
import io.github.danielphingston.metro.ui.search.SearchInAppsProvider
import io.github.danielphingston.metro.ui.search.SearchProvider
import io.github.danielphingston.metro.ui.search.WhatsAppRow
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.settings.SearchInAppsPage
import io.github.danielphingston.metro.ui.settings.SearchKeywordsPage
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
import java.util.Locale

/** The search results (answer, search-in strip, WhatsApp button) and the two search list pages, on the JVM. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class SearchUxScreenshotTest {
    private val apps = listOf(
        app("Chrome", "com.example.chrome"), app("Camera", "com.example.camera"),
        app("YouTube", "com.google.android.youtube"), app("Play Store", "com.android.vending"),
        app("Google Maps", "com.google.android.apps.maps"), app("Spotify", "com.spotify.music"),
        app("Amazon Shopping", "in.amazon.mShop.android.shopping"), app("Claude", "com.anthropic.claude"),
        app("WhatsApp", "com.whatsapp"), app("Keep Notes", "com.example.keep"),
    )

    private object Phone : SearchInPhone {
        override fun resolves(intent: android.content.Intent) = true
        override fun amazonDomain(locale: Locale) = "amazon.in"
    }

    private val all = LauncherSettings(
        theme = ThemeMode.DARK,
        searchInApps = SearchInApp.entries.toSet(),
        assistant = "com.anthropic.claude",
    )

    private fun ctx(settings: LauncherSettings = all, uses: Map<String, Int> = emptyMap()) =
        SearchContext(RuntimeEnvironment.getApplication(), apps, settings, Locale.ENGLISH, searchInUses = uses)

    private fun provided(query: String, vararg providers: SearchProvider, uses: Map<String, Int> = emptyMap()) = runBlocking {
        providers.map { ProvidedItems(it, it.results(query, ctx(uses = uses))) }.filter { it.items.isNotEmpty() }
    }

    private fun icons(): IconCache {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        apps.forEach { icons.load(it, 28 * 3, true); icons.load(it, 44 * 3, false); icons.load(it, 28 * 3, false); icons.load(it, 20 * 3, false) }
        return icons
    }

    private fun results(name: String, query: String, provided: List<ProvidedItems>, plain: Boolean = false) {
        val icons = icons()
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(all) {
                val rows = remember { searchRows(apps, query, emptyList(), SearchEngine.GOOGLE, 0L, provided) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SearchContent(
                        query = query, onQueryChange = {}, onSubmit = {}, rows = rows, icons = icons,
                        monochrome = true, plainIcons = plain, focusRequester = focus,
                        onActivate = { _, _ -> }, onLongClick = { _, _ -> }, onDismiss = {},
                    )
                }
            }
        }
    }

    private val searchIn get() = SearchInAppsProvider({ Phone }, { _, _ -> emptyList() })

    @Test
    fun answerStripAndAssistant() = results(
        "search_ux_answer", "2+2",
        provided("2+2", CalculatorProvider, searchIn, AskAssistantProvider, uses = mapOf("spotify" to 4, "youtube" to 2)),
    )

    @Test
    fun answerStripPlainIcons() = results(
        "search_ux_answer_plain", "2+2",
        provided("2+2", CalculatorProvider, searchIn, AskAssistantProvider), plain = true,
    )

    private class Book(val people: Map<ContactStub, ContactDetails>) : ContactsSource {
        override suspend fun all(context: android.content.Context) = people.keys.toList()
        override suspend fun byNumber(context: android.content.Context, query: String) = emptyList<ContactStub>()
        override suspend fun details(context: android.content.Context, contact: ContactStub) = people.getValue(contact)
    }

    @Test
    fun contactWithWhatsAppButton() {
        val book = Book(
            mapOf(
                ContactStub(1, "k1", "Anna Brandt", null) to
                    ContactDetails(listOf("+49 170 1234567"), WhatsAppRow(1, "vnd.android.cursor.item/vnd.com.whatsapp.profile")),
                ContactStub(2, "k2", "Annabel Cole", null) to ContactDetails(listOf("+44 7700 900123"), null),
            ),
        )
        val provider = ContactsProvider(book, hasPermission = { true }, photo = { _, _ -> null })
        results("search_ux_contacts", "ann", provided("ann", provider))
    }

    // --- settings ---------------------------------------------------------------------------------

    private val others = listOf(OtherSearchApp("com.example.keep", "Keep Notes", false), OtherSearchApp("com.example.browser", "Browser", true))

    private fun page(name: String, page: SettingsPage, settings: LauncherSettings, content: @androidx.compose.runtime.Composable (SettingsRepository, IconCache) -> Unit) {
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        val icons = icons()
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SettingsPageFrame(page, rememberScrollState()) { content(repository, icons) }
                }
            }
        }
    }

    private val inAppsSettings = all.copy(
        searchInApps = setOf(SearchInApp.YOUTUBE, SearchInApp.PLAY_STORE, SearchInApp.AMAZON),
        searchInOtherApps = setOf("com.example.keep"),
    )

    @Test
    fun searchInAppsPage() = page("search_ux_page_in_apps", SettingsPage.SEARCH_IN_APPS, inAppsSettings) { repo, icons ->
        SearchInAppsPage(inAppsSettings, repo, true, others, apps, icons, onUnlock = {})
    }

    @Test
    fun searchInAppsPageCustomOrderFiltered() {
        val s = inAppsSettings.copy(searchInOrder = SearchInOrder.CUSTOM, searchInCustomOrder = listOf("spotify", "youtube"))
        page("search_ux_page_in_apps_custom", SettingsPage.SEARCH_IN_APPS, s) { repo, icons ->
            SearchInAppsPage(s, repo, true, others, apps, icons, onUnlock = {})
        }
    }

    @Test
    fun searchInAppsPageFiltered() = page("search_ux_page_in_apps_filter", SettingsPage.SEARCH_IN_APPS, inAppsSettings) { repo, icons ->
        SearchInAppsPage(inAppsSettings, repo, true, others, apps, icons, onUnlock = {}, initialFilter = "ma")
    }

    @Test
    fun searchInAppsPageNoMatch() = page("search_ux_page_in_apps_empty", SettingsPage.SEARCH_IN_APPS, inAppsSettings) { repo, icons ->
        SearchInAppsPage(inAppsSettings, repo, true, others, apps, icons, onUnlock = {}, initialFilter = "zzz")
    }

    @Test
    fun searchInAppsPageLocked() = page("search_ux_page_in_apps_locked", SettingsPage.SEARCH_IN_APPS, inAppsSettings) { repo, icons ->
        SearchInAppsPage(inAppsSettings, repo, false, others, apps, icons, onUnlock = {})
    }

    @Test
    fun keywordsPage() = page("search_ux_page_keywords", SettingsPage.SEARCH_KEYWORDS, all) { repo, icons ->
        SearchKeywordsPage(all, repo, true, others, apps, icons, onUnlock = {})
    }

    @Test
    fun keywordsPageFiltered() = page("search_ux_page_keywords_filter", SettingsPage.SEARCH_KEYWORDS, all) { repo, icons ->
        SearchKeywordsPage(all, repo, true, others, apps, icons, onUnlock = {}, initialFilter = "amazon")
    }

    @Test
    fun keywordsPageNoMatch() = page("search_ux_page_keywords_empty", SettingsPage.SEARCH_KEYWORDS, all) { repo, icons ->
        SearchKeywordsPage(all, repo, true, others, apps, icons, onUnlock = {}, initialFilter = "zzz")
    }

    @Test
    fun keywordsPageLocked() = page("search_ux_page_keywords_locked", SettingsPage.SEARCH_KEYWORDS, all) { repo, icons ->
        SearchKeywordsPage(all, repo, false, others, apps, icons, onUnlock = {})
    }

    @Test
    fun searchSettingsWithSummaryRows() {
        val s = inAppsSettings.copy(smartFeatures = SmartFeature.defaults)
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        captureRoboImage("src/test/screenshots/search_ux_settings_summary.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(s) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SettingsPageFrame(SettingsPage.SEARCH, rememberScrollState(SCROLL_TO_SUMMARY)) {
                        SmartSearchSection(s, repository, true, onUnlock = {})
                    }
                }
            }
        }
    }

    private fun app(label: String, pkg: String) = AppInfo(
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
        const val SCROLL_TO_SUMMARY = 1300
    }
}
