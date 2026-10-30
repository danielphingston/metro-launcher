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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.ui.search.CalendarHit
import io.github.danielphingston.metro.ui.search.CalendarProvider
import io.github.danielphingston.metro.ui.search.ChatHit
import io.github.danielphingston.metro.ui.search.ContactDetails
import io.github.danielphingston.metro.ui.search.ContactStub
import io.github.danielphingston.metro.ui.search.ContactsProvider
import io.github.danielphingston.metro.ui.search.ContactsSource
import io.github.danielphingston.metro.ui.search.OtherSearchApp
import io.github.danielphingston.metro.ui.search.SearchInAppsProvider
import io.github.danielphingston.metro.ui.search.SearchInPhone
import io.github.danielphingston.metro.ui.search.WhatsAppProvider
import io.github.danielphingston.metro.ui.search.WhatsAppRow
import java.util.Locale
import java.util.TimeZone
import io.github.danielphingston.metro.ui.search.ProvidedItems
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchContent
import io.github.danielphingston.metro.ui.search.SearchProvider
import io.github.danielphingston.metro.ui.search.Suggestions
import io.github.danielphingston.metro.ui.search.searchRows
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

/** Renders the phase 2 smart search screens: contacts, WhatsApp, calendar, search in apps and their settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class SmartSearchPhase2ScreenshotTest {
    private val apps = listOf(
        app("Chrome"), app("Camera"), app("Calendar"), app("Clock"), app("Messages"), app("Photos"),
        app("YouTube", "com.google.android.youtube"), app("Play Store", "com.android.vending"),
        app("Google Maps", "com.google.android.apps.maps"), app("Spotify", "com.spotify.music"),
        app("Amazon Shopping", "com.amazon.mShop.android.shopping"), app("WhatsApp", "com.whatsapp"),
        app("Keep Notes", "com.example.keep"),
    )

    private val now = 1_793_354_400_000L // 2026-10-30 10:00 UTC

    private fun ctx(settings: LauncherSettings = LauncherSettings()) =
        SearchContext(RuntimeEnvironment.getApplication(), apps, settings, Locale.ENGLISH) { now }

    private fun provided(query: String, provider: SearchProvider, settings: LauncherSettings = LauncherSettings()) =
        runBlocking { listOf(ProvidedItems(provider, provider.results(query, ctx(settings)))) }

    /** A square picture with a letter, standing in for a photo. */
    private fun photo(letter: String, color: Int): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = 48f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(letter, 48f, 66f, paint)
        return bitmap
    }

    private class Book(val people: Map<ContactStub, ContactDetails>) : ContactsSource {
        override suspend fun all(context: android.content.Context) = people.keys.toList()
        override suspend fun byNumber(context: android.content.Context, query: String) = emptyList<ContactStub>()
        override suspend fun details(context: android.content.Context, contact: ContactStub) = people.getValue(contact)
    }

    @Test
    fun contacts() {
        val book = Book(
            mapOf(
                ContactStub(1, "k1", "Anna Brandt", "photo:a") to
                    ContactDetails(listOf("+49 170 1234567"), WhatsAppRow(1, "vnd.android.cursor.item/vnd.com.whatsapp.profile")),
                ContactStub(2, "k2", "Annabel Cole", null) to ContactDetails(listOf("+44 7700 900123"), null),
                ContactStub(3, "k3", "Annette Roy", null) to ContactDetails(emptyList(), null),
            ),
        )
        val provider = ContactsProvider(book, hasPermission = { true }, photo = { _, _ -> photo("A", 0xFF3B7DD8.toInt()).asImageBitmap() })
        capture("smart_p2_contacts", "ann", provided("ann", provider))
    }

    @Test
    fun whatsAppGroup() {
        val chats = listOf(
            ChatHit("com.whatsapp", "g1", 0, "Family group", "Family group"),
            ChatHit("com.whatsapp", "c1", 0, "Fam Dentist", null),
        )
        val provider = WhatsAppProvider(
            chats = { _ -> { _, _ -> chats } },
            iconOf = { _, hit -> photo(hit.label.take(1), if (hit.id == "g1") 0xFF2E9E6A.toInt() else 0xFFB5651D.toInt()) },
        )
        capture("smart_p2_whatsapp", "fam", provided("fam", provider))
    }

    @Test
    fun calendarEvent() {
        val tz = TimeZone.getTimeZone("UTC")
        fun hit(id: Long, title: String, begin: Long, place: String?, allDay: Boolean = false) =
            CalendarHit(id, title, begin, begin + 3_600_000, begin, begin + 3_600_000, allDay, place)
        val events = listOf(
            hit(1, "Dentist appointment", now + 28 * 3_600_000, "Main Street 4"),
            hit(2, "Dentist check-up reminder", now + 15 * 86_400_000L, null),
            hit(3, "Team lunch", now + 2 * 86_400_000L, "Dentist & Co cafe"),
        )
        val provider = CalendarProvider({ _, _ -> events }, hasPermission = { true }, is24Hour = { true }, zone = { tz })
        capture("smart_p2_calendar", "dentist", provided("dentist", provider))
    }

    private class Phone(val domain: String) : SearchInPhone {
        override fun resolves(intent: android.content.Intent) = true
        override fun amazonDomain(locale: Locale) = domain
    }

    @Test
    fun searchInApps() {
        val settings = LauncherSettings(
            searchInApps = SearchInApp.entries.toSet(),
            searchInOtherApps = setOf("com.example.keep"),
        )
        val provider = SearchInAppsProvider({ Phone("amazon.in") }, { _, _ -> listOf(OtherSearchApp("com.example.keep", "Keep Notes", web = false)) })
        capture("smart_p2_search_in_apps", "usb c cable", provided("usb c cable", provider, settings))
    }

    @Test
    fun searchSettingsWithSearchInApps() {
        val settings = LauncherSettings(
            smartFeatures = SmartFeature.defaults + SmartFeature.CONTACTS,
            searchInApps = setOf(SearchInApp.YOUTUBE, SearchInApp.PLAY_STORE, SearchInApp.AMAZON),
            searchInOtherApps = setOf("com.example.keep"),
        )
        val others = listOf(OtherSearchApp("com.example.keep", "Keep Notes", false), OtherSearchApp("com.example.browser", "Browser", true))
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        captureRoboImage("src/test/screenshots/settings_search_in_apps.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SettingsPageFrame(SettingsPage.SEARCH, rememberScrollState(SCROLL_TO_SEARCH_IN_APPS)) {
                        SmartSearchSection(settings, repository, true, onUnlock = {}, otherSearchApps = others)
                    }
                }
            }
        }
    }

    private fun capture(name: String, query: String, provided: List<ProvidedItems>) {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        apps.forEach { icons.load(it, 28 * 3, true); icons.load(it, 44 * 3, false) }
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings()) {
                val rows = remember { searchRows(apps, query, emptyList(), SearchEngine.GOOGLE, 0L, provided, null) }
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
        /** Scrolls the settings page down to the "search in apps" switches. */
        const val SCROLL_TO_SEARCH_IN_APPS = 1500
    }
}
