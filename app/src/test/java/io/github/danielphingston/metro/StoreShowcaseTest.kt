package io.github.danielphingston.metro

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.ScrubberHighlight
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileStyle
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.CalendarEvent
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.live.NotificationPreview
import io.github.danielphingston.metro.live.NowPlaying
import io.github.danielphingston.metro.live.QuickInfo
import io.github.danielphingston.metro.live.QuickState
import io.github.danielphingston.metro.live.weather.DayForecast
import io.github.danielphingston.metro.live.weather.Forecast
import io.github.danielphingston.metro.live.weather.WeatherCondition
import io.github.danielphingston.metro.live.weather.WeatherPlace
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.applist.AlphabetScrubber
import io.github.danielphingston.metro.ui.applist.AppListContent
import io.github.danielphingston.metro.ui.applist.AppListRow
import io.github.danielphingston.metro.ui.applist.appListRows
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroSlider
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.search.CalendarHit
import io.github.danielphingston.metro.ui.search.CalendarProvider
import io.github.danielphingston.metro.ui.search.CalculatorProvider
import io.github.danielphingston.metro.ui.search.ContactDetails
import io.github.danielphingston.metro.ui.search.ContactStub
import io.github.danielphingston.metro.ui.search.ContactsProvider
import io.github.danielphingston.metro.ui.search.ContactsSource
import io.github.danielphingston.metro.ui.search.ProvidedItems
import io.github.danielphingston.metro.ui.search.SearchContent
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchProvider
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.settings.AccentGrid
import io.github.danielphingston.metro.ui.settings.Section
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.settings.SettingsScreen
import io.github.danielphingston.metro.ui.start.EditBadges
import io.github.danielphingston.metro.ui.start.FlipCard
import io.github.danielphingston.metro.ui.start.FolderLayout
import io.github.danielphingston.metro.ui.start.FolderRegion
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.TileFace
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.start.TilePlacement
import io.github.danielphingston.metro.ui.start.TurnstilePhase
import io.github.danielphingston.metro.ui.start.TurnstileState
import io.github.danielphingston.metro.ui.start.turnstile
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.startBackdrop
import io.github.danielphingston.metro.ui.tiles.FolderTile
import io.github.danielphingston.metro.ui.tiles.Mosaic
import io.github.danielphingston.metro.ui.tiles.QuickSettingFaceContent
import io.github.danielphingston.metro.ui.tiles.WeatherBack
import io.github.danielphingston.metro.ui.tiles.WeatherFront
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders the raw phone screens behind the Play Store listing and the website: rich, made-up content
 * (generic app names, procedural icons, wallpapers and avatars; no photos, brands or real people).
 *
 * Skipped unless run with `-PstoreShots=true`. Output: app/build/store/raw/NN_name.png, which
 * tools/store/compose.py turns into the framed marketing images.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi", application = MetroApplication::class)
class StoreShowcaseTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun only() = Assume.assumeTrue("store shots are opt-in: -PstoreShots=true", System.getProperty("storeShots") != null)

    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 24, 9, 41) }.timeInMillis
    private val hour = 3_600_000L

    // ------------------------------------------------------------------ screens

    @Test
    fun wallpapers() {
        rule.setContent {}
        save("_wallpaper_neon", wallpaper(0).asAndroidBitmap())
        save("_wallpaper_sunset", wallpaper(1).asAndroidBitmap())
        save("_wallpaper_aurora", wallpaper(2).asAndroidBitmap())
    }

    /** 1: the hero, the neon wallpaper showing through the tiles. */
    @Test
    fun hero() {
        val bg = LauncherSettings(background = StartBackground.PHOTO_TILES, tileTransparency = 72)
        shoot("01_hero", LauncherSettings(accent = 0xFF1BA1E2.toInt(), columns = 4)) {
            val backdrop = remember { StartBackdrop.of(bg, wallpaper(0)) { 0f } }
            StartGrid(
                backdrop,
                listOf(
                    item(TileSize.MEDIUM) { weather(TileSize.MEDIUM, it) },
                    item(TileSize.MEDIUM) { app("Clock", TileSize.MEDIUM, LiveContent.Clock(ClockState(now, now + 21 * hour), true), cell = it) },
                    item(TileSize.MEDIUM) { app("Music", TileSize.MEDIUM, LiveContent.NowPlaying(NowPlaying("Midnight Drive", "The Neon Hours", albumArt(), true)), cell = it) },
                    item(TileSize.MEDIUM) { app("Messages", TileSize.MEDIUM, LiveContent.Notifications(unread(3), true), cell = it) },
                    item(TileSize.WIDE) { app("Calendar", TileSize.WIDE, LiveContent.Calendar(now, events(), true), cell = it) },
                    item(TileSize.MEDIUM) { people(TileSize.MEDIUM, it) },
                    item(TileSize.SMALL) { app("Phone", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Camera", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { battery(TileSize.SMALL, it) },
                    item(TileSize.SMALL) { app("Settings", TileSize.SMALL, cell = it) },
                    item(TileSize.WIDE) { photos(TileSize.WIDE, it) },
                ),
            )
        }
    }

    /** 2: customisation: the light theme, another accent and the sunset wallpaper. */
    @Test
    fun customised() {
        val bg = LauncherSettings(background = StartBackground.PHOTO_TILES, tileTransparency = 70)
        shoot("02_custom", LauncherSettings(theme = ThemeMode.LIGHT, accent = 0xFFD80073.toInt(), columns = 4)) {
            val backdrop = remember { StartBackdrop.of(bg, wallpaper(1)) { 0f } }
            StartGrid(
                backdrop,
                listOf(
                    item(TileSize.WIDE) { weather(TileSize.WIDE, it) },
                    item(TileSize.WIDE) { app("Mail", TileSize.WIDE, LiveContent.Notifications(AppNotifications(12, listOf(NotificationPreview("Sol Travel", "Your train to Lisbon is confirmed for Friday", now))), true), flipped = true, cell = it) },
                    item(TileSize.MEDIUM) { photos(TileSize.MEDIUM, it) },
                    item(TileSize.MEDIUM) { folder("play", TileSize.MEDIUM, listOf("Music", "Videos", "Podcasts", "Games", "Photos", "News", "Store", "Fitness"), it) },
                    item(TileSize.SMALL) { app("Phone", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Maps", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Camera", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Store", TileSize.SMALL, LiveContent.Notifications(AppNotifications(2, emptyList()), true), cell = it) },
                    item(TileSize.MEDIUM) { app("Clock", TileSize.MEDIUM, LiveContent.Clock(ClockState(now, now + 21 * hour), true), cell = it) },
                    item(TileSize.WIDE) { app("Calendar", TileSize.WIDE, LiveContent.Calendar(now, events(), true), cell = it) },
                    item(TileSize.MEDIUM) { app("Music", TileSize.MEDIUM, LiveContent.NowPlaying(NowPlaying("Golden Hour", "Alba Reyes", albumArt(1), true)), cell = it) },
                    item(TileSize.MEDIUM) { people(TileSize.MEDIUM, it) },
                ),
            )
        }
    }

    /** 2b: full photo background with parallax, dark. */
    @Test
    fun fullPhoto() {
        val bg = LauncherSettings(background = StartBackground.PHOTO_FULL, tileTransparency = 40)
        shoot("02b_full_photo", LauncherSettings(accent = 0xFF6A00FF.toInt(), columns = 4)) {
            val backdrop = remember { StartBackdrop.of(bg, wallpaper(2)) { 60f } }
            StartGrid(
                backdrop,
                listOf(
                    item(TileSize.MEDIUM) { weather(TileSize.MEDIUM, it) },
                    item(TileSize.MEDIUM) { app("Messages", TileSize.MEDIUM, LiveContent.Notifications(unread(3), true), cell = it) },
                    item(TileSize.WIDE) { app("Calendar", TileSize.WIDE, LiveContent.Calendar(now, events(), true), cell = it) },
                    item(TileSize.SMALL) { app("Phone", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Camera", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Notes", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Maps", TileSize.SMALL, cell = it) },
                    item(TileSize.MEDIUM) { app("Music", TileSize.MEDIUM, LiveContent.NowPlaying(NowPlaying("Aurora", "Kite & Ember", albumArt(2), true)), cell = it) },
                    item(TileSize.MEDIUM) { people(TileSize.MEDIUM, it) },
                ),
            )
        }
    }

    /** 3: a folder opened in place, next to a folder showing only its photo. */
    @Test
    fun folderOpen() {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        val uri = writePhoto("folder_photo", scenery(0, 600, 600))
        val photoSettings = LauncherSettings(background = StartBackground.PHOTO_TILES, tileTransparency = 72)
        shoot("03_folder", LauncherSettings(accent = 0xFF1BA1E2.toInt(), columns = 4)) {
            val backdrop = remember { StartBackdrop.of(photoSettings, wallpaper(0)) { 0f } }
            FolderScreen(icons, backdrop, uri)
        }
    }

    /** 4: smart search with contacts, apps and events. */
    @Test
    fun search() {
        val book = Book(
            mapOf(
                ContactStub(1, "k1", "Maya Okafor", "photo:0") to ContactDetails(listOf("mobile"), null),
                ContactStub(2, "k2", "Marco Silva", "photo:1") to ContactDetails(listOf("mobile"), null),
                ContactStub(3, "k3", "Mara Lindqvist", "photo:2") to ContactDetails(listOf("mobile"), null),
                ContactStub(4, "k4", "Mateo Ruiz", "photo:3") to ContactDetails(listOf("mobile"), null),
            ),
        )
        val tz = TimeZone.getTimeZone("UTC")
        val events = listOf(
            CalendarHit(1, "Market day with Maya", now + 28 * hour, now + 29 * hour, now + 28 * hour, now + 29 * hour, false, "Old Town square"),
            CalendarHit(2, "Mapmaking workshop", now + 3 * 24 * hour, now + 4 * 24 * hour, now + 3 * 24 * hour, now + 4 * 24 * hour, false, null),
        )
        val providers = listOf<SearchProvider>(
            ContactsProvider(book, hasPermission = { true }, photo = { _, key -> avatar(key.substringAfter(':').toInt(), 160).asImageBitmap() }),
            CalendarProvider({ _, _ -> events }, hasPermission = { true }, is24Hour = { true }, zone = { tz }),
        )
        searchShot("04_search", "ma", providers)
    }

    /** 4b: the calculator answer. */
    @Test
    fun searchCalculator() {
        searchShot("04b_search_calc", "15% of 2400", listOf(CalculatorProvider))
    }

    /** 5: the app list with the alphabet scrubber under a finger. */
    @Test
    fun appList() {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        val names = listOf(
            "Alarms", "Calculator", "Calendar", "Camera", "Chat", "Clock", "Contacts", "Files", "Fitness", "Games", "Mail", "Maps",
            "Messages", "Music", "News", "Notes", "Phone", "Photos", "Podcasts", "Settings", "Store", "Torch", "Videos", "Weather",
        )
        val recent = mapOf("Fitness" to 0.5, "Chat" to 2.0, "Notes" to 4.0)
        val apps = names.map { name ->
            val installed = recent[name]?.let { now - (it * 24 * hour).toLong() } ?: (now - 60 * 24 * hour)
            app(name, installTime = installed, uninstallable = name in recent)
        }
        val rows = appListRows(apps, "", now)
        val letters = rows.mapNotNull { (it as? AppListRow.Header)?.group }
        shoot("05_app_list", LauncherSettings(accent = 0xFF1BA1E2.toInt()), touch = "scrubber") {
            Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                AppListContent(
                    rows = rows, listState = rememberLazyListState(), icons = icons, monochrome = true, query = "",
                    onQueryChange = {}, onSubmit = {}, onLetterClick = {}, onClick = { _, _ -> }, onLongClick = { _, _ -> },
                    scrubberInset = 28.dp,
                )
                AlphabetScrubber(
                    letters, onScrub = {},
                    modifier = Modifier.align(Alignment.BottomEnd).padding(top = 60.dp).fillMaxHeight().testTag("scrubber"),
                    highlight = ScrubberHighlight.CIRCLE,
                )
            }
        }
    }

    /** 6: settings, start background and accent colours. */
    @Test
    fun settings() {
        shoot("06_settings", LauncherSettings(accent = 0xFFD80073.toInt())) { SettingsPreview() }
    }

    @Test
    fun animations() {
        val settings = LauncherSettings(accent = 0xFF6A00FF.toInt())
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        shoot("06b_animations", settings) { SettingsScreen(settings, repository, SettingsPage.ANIMATIONS) }
    }

    /** 7: the turnstile frozen mid launch. */
    @Test
    fun turnstile() {
        val bg = LauncherSettings(background = StartBackground.PHOTO_TILES, tileTransparency = 72)
        val frozen = TurnstileState().apply { seek(TurnstilePhase.OUT, 110f) }
        shoot("07_turnstile", LauncherSettings(accent = 0xFF1BA1E2.toInt(), columns = 4)) {
            val backdrop = remember { StartBackdrop.of(bg, wallpaper(0)) { 0f } }
            StartGrid(heroItems(), backdrop, turnstile = frozen)
        }
    }

    /** 8: edit mode with a tile selected. */
    @Test
    fun editing() {
        val bg = LauncherSettings(background = StartBackground.PHOTO_TILES, tileTransparency = 72)
        shoot("08_edit", LauncherSettings(accent = 0xFF1BA1E2.toInt(), columns = 4)) {
            val backdrop = remember { StartBackdrop.of(bg, wallpaper(0)) { 0f } }
            StartGrid(heroItems(), backdrop, editing = 1)
        }
    }

    /** 9: a tablet-free battery tile showcase: quick settings. */
    @Test
    fun quick() {
        shoot("09_quick", LauncherSettings(accent = 0xFF00A300.toInt(), columns = 4)) {
            StartGrid(
                StartBackdrop.Solid,
                listOf(
                    item(TileSize.WIDE) { battery(TileSize.WIDE, it) },
                    item(TileSize.MEDIUM) { quickTile(QuickSettingKind.FLASHLIGHT, QuickState(true, "on"), TileSize.MEDIUM, "flashlight", it) },
                    item(TileSize.MEDIUM) { quickTile(QuickSettingKind.DO_NOT_DISTURB, QuickState(false, "off"), TileSize.MEDIUM, "do not disturb", it) },
                    item(TileSize.MEDIUM) { quickTile(QuickSettingKind.RINGER, QuickState(true, "ring", 2), TileSize.MEDIUM, "ringer", it) },
                    item(TileSize.MEDIUM) { quickTile(QuickSettingKind.BRIGHTNESS, QuickState(true, "auto", 0), TileSize.MEDIUM, "brightness", it) },
                    item(TileSize.SMALL) { quickTile(QuickSettingKind.AUTO_ROTATE, QuickState(true, "on"), TileSize.SMALL, "auto-rotate", it) },
                    item(TileSize.SMALL) { app("Phone", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Camera", TileSize.SMALL, cell = it) },
                    item(TileSize.SMALL) { app("Settings", TileSize.SMALL, cell = it) },
                    item(TileSize.MEDIUM) { app("Clock", TileSize.MEDIUM, LiveContent.Clock(ClockState(now, null), true), cell = it) },
                    item(TileSize.MEDIUM) { weather(TileSize.MEDIUM, it) },
                ),
            )
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun heroItems(): List<Item> = listOf(
        item(TileSize.MEDIUM) { weather(TileSize.MEDIUM, it) },
        item(TileSize.MEDIUM) { app("Clock", TileSize.MEDIUM, LiveContent.Clock(ClockState(now, now + 21 * hour), true), cell = it) },
        item(TileSize.MEDIUM) { app("Music", TileSize.MEDIUM, LiveContent.NowPlaying(NowPlaying("Midnight Drive", "The Neon Hours", albumArt(), true)), cell = it) },
        item(TileSize.MEDIUM) { app("Messages", TileSize.MEDIUM, LiveContent.Notifications(unread(3), true), cell = it) },
        item(TileSize.WIDE) { app("Calendar", TileSize.WIDE, LiveContent.Calendar(now, events(), true), cell = it) },
        item(TileSize.MEDIUM) { people(TileSize.MEDIUM, it) },
        item(TileSize.SMALL) { app("Phone", TileSize.SMALL, cell = it) },
        item(TileSize.SMALL) { app("Camera", TileSize.SMALL, cell = it) },
        item(TileSize.SMALL) { battery(TileSize.SMALL, it) },
        item(TileSize.SMALL) { app("Settings", TileSize.SMALL, cell = it) },
        item(TileSize.WIDE) { photos(TileSize.WIDE, it) },
    )

    private fun unread(count: Int) = AppNotifications(
        count, listOf(NotificationPreview("Maya", "Dinner at 8? See you there!", now)),
    )

    private fun events() = listOf(
        CalendarEvent("Pottery class", now + hour, now + 2 * hour, false, "Studio 3"),
        CalendarEvent("Lunch with Noor", now + 4 * hour, now + 5 * hour, false, null),
    )

    private class Book(val people: Map<ContactStub, ContactDetails>) : ContactsSource {
        override suspend fun all(context: android.content.Context) = people.keys.toList()
        override suspend fun byNumber(context: android.content.Context, query: String) = emptyList<ContactStub>()
        override suspend fun details(context: android.content.Context, contact: ContactStub) = people.getValue(contact)
    }

    private fun searchShot(name: String, query: String, providers: List<SearchProvider>) {
        val apps = listOf("Calculator", "Calendar", "Camera", "Mail", "Maps", "Messages", "Music", "Notes", "Phone", "Photos", "Settings", "Store").map { app(it) }
        val icons = IconCache(RuntimeEnvironment.getApplication())
        val ctx = SearchContext(RuntimeEnvironment.getApplication(), apps, LauncherSettings(), Locale.ENGLISH) { now }
        val provided = runBlocking { providers.map { ProvidedItems(it, it.results(query, ctx)) }.filter { it.items.isNotEmpty() } }
        shoot(name, LauncherSettings(accent = 0xFF1BA1E2.toInt())) {
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

    // ------------------------------------------------------------------ harness

    private fun shoot(name: String, settings: LauncherSettings, touch: String? = null, content: @Composable () -> Unit) {
        rule.setContent { MetroTheme(settings) { content() } }
        if (touch != null) {
            rule.onNodeWithTag(touch).performTouchInput { down(center.copy(y = height * 0.755f)) }
            rule.mainClock.advanceTimeBy(1_000)
        }
        // Icons and photos load on background threads; give them time to arrive and settle.
        repeat(6) {
            Thread.sleep(250)
            rule.waitForIdle()
        }
        rule.onRoot().captureRoboImage("build/store/raw/$name.png")
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dir = File("build/store/raw").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun writePhoto(name: String, image: ImageBitmap): String {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "$name.png")
        FileOutputStream(file).use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file).toString()
    }

    // ------------------------------------------------------------------ start grid

    private class Item(val size: TileSize, val content: @Composable (Dp) -> Unit)

    private fun item(size: TileSize, content: @Composable (Dp) -> Unit) = Item(size, content)

    private val iconCache by lazy { IconCache(RuntimeEnvironment.getApplication()) }

    @Composable
    private fun app(
        label: String, size: TileSize, live: LiveContent? = null, flipped: Boolean = false, cell: Dp,
    ) = TileFace(app(label), size, live, flipped, true, iconCache, cell)

    @Composable
    private fun weather(size: TileSize, cell: Dp) {
        val m = remember(cell) { TileMetrics(cell, androidx.compose.ui.unit.Density(3f)) }
        val place = WeatherPlace("Lisbon", "", 0.0, 0.0)
        val d = LocalDate.of(2026, 10, 24)
        val forecast = Forecast(
            22, WeatherCondition.PARTLY_CLOUDY, true,
            listOf(
                DayForecast(d, WeatherCondition.PARTLY_CLOUDY, 24, 16),
                DayForecast(d.plusDays(1), WeatherCondition.CLEAR, 25, 16),
                DayForecast(d.plusDays(2), WeatherCondition.SHOWERS, 21, 15),
                DayForecast(d.plusDays(3), WeatherCondition.CLOUDY, 20, 14),
            ),
        )
        FlipCard(showBack = false, front = { WeatherFront(size, place, forecast, "Weather", m) }, back = { WeatherBack(size, forecast, "Weather", m) })
    }

    @Composable
    private fun battery(size: TileSize, cell: Dp) {
        val info = QuickInfo.Battery(84, true, 25 * 60_000L, 31f)
        quickTile(QuickSettingKind.BATTERY, QuickState(true, "84% charging", 84, info), size, "battery", cell)
    }

    @Composable
    private fun quickTile(kind: QuickSettingKind, state: QuickState, size: TileSize, label: String, cell: Dp) =
        QuickSettingFaceContent(kind, state, size, label, cell)

    @Composable
    private fun photos(size: TileSize, cell: Dp) {
        val m = remember(cell) { TileMetrics(cell, androidx.compose.ui.unit.Density(3f)) }
        val image = remember { scenery(1, 900, 450) }
        Box(Modifier.fillMaxSize().background(Metro.colors.accent)) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(m.dp(44f))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
            )
            MetroText(
                "Photos", MetroType.style(m.text(12f)), color = Color.White, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = m.dp(8f), bottom = m.dp(6f)),
            )
        }
    }

    @Composable
    private fun people(size: TileSize, cell: Dp) {
        val m = remember(cell) { TileMetrics(cell, androidx.compose.ui.unit.Density(3f)) }
        val grid = Mosaic.grid(size)
        val faces = remember { List(grid.cells) { avatar(it, 200).asImageBitmap() } }
        Box(Modifier.fillMaxSize().background(Metro.colors.accent)) {
            Column(Modifier.fillMaxSize()) {
                repeat(grid.rows) { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        repeat(grid.columns) { column ->
                            Box(Modifier.weight(1f).fillMaxHeight().padding(m.dp(1f))) {
                                Image(faces[row * grid.columns + column], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(m.dp(34f))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
            )
            MetroText(
                "People", MetroType.style(m.text(12f)), color = Color.White, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = m.dp(8f), bottom = m.dp(6f)),
            )
        }
    }

    @Composable
    private fun folder(name: String, size: TileSize, children: List<String>, cell: Dp) {
        val resolved = folderTile(1, name, size, children.mapIndexed { i, l -> appTile(10L + i, l, TileSize.MEDIUM) })
        FolderTile(resolved, cell, iconCache, true, open = false)
    }

    private fun appTile(id: Long, label: String, size: TileSize): ResolvedTile {
        val app = app(label)
        return ResolvedTile(TileSpec(id, size, TileContent.App(app.key)), app, label)
    }

    private fun folderTile(
        id: Long, name: String, size: TileSize, children: List<ResolvedTile>,
        style: TileStyle = TileStyle.ICON, image: String? = null,
    ): ResolvedTile =
        ResolvedTile(
            TileSpec(id, size, TileContent.Folder(name, children.map { it.spec }), style = style, image = image),
            null, name, children,
        )

    @Composable
    private fun StatusBar() {
        val color = Metro.colors.foreground
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetroText("9:41", MetroType.style(14.sp, FontWeight.SemiBold), color = color)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp, 10.dp).drawBehind {
                    for (i in 0 until 4) {
                        val h = size.height * (0.35f + 0.22f * i)
                        drawRect(color, Offset(i * size.width / 4f, size.height - h), androidx.compose.ui.geometry.Size(size.width / 6f, h))
                    }
                })
                Box(Modifier.size(24.dp, 11.dp).drawBehind {
                    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
                    drawRoundRect(
                        color, Offset(2.5.dp.toPx(), 2.5.dp.toPx()),
                        androidx.compose.ui.geometry.Size((size.width - 5.dp.toPx()) * 0.84f, size.height - 5.dp.toPx()),
                        androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                    )
                })
            }
        }
    }

    @Composable
    private fun StartGrid(
        backdrop: StartBackdrop, items: List<Item>, columns: Int = 4, turnstile: TurnstileState? = null, editing: Int? = null,
    ) {
        val density = LocalDensity.current
        val bounds = remember { mutableStateMapOf<Int, Rect>() }
        CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                StatusBar()
                BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 40.dp)) {
                    val gap = 4.dp
                    val cell = (maxWidth + gap) / columns
                    val placements = TilePlacement.layout(
                        items.mapIndexed { i, it -> TileSpec(i + 1L, it.size, TileContent.Weather) }, columns,
                    )
                    items.zip(placements).forEachIndexed { index, (item, p) ->
                        val dim = editing != null && index != editing
                        Box(
                            Modifier
                                .offset(cell * p.x, cell * p.y)
                                .size(cell * p.width - gap, cell * p.height - gap)
                                .graphicsLayer {
                                    scaleX = if (dim) 0.94f else 1f
                                    scaleY = scaleX
                                    alpha = if (dim) 0.6f else 1f
                                }
                                .onGloballyPositioned { bounds[index] = it.boundsInRoot() }
                                .then(if (turnstile != null) Modifier.turnstile(turnstile, index) { bounds[index] } else Modifier),
                        ) { item.content(cell) }
                    }
                    if (editing != null) {
                        EditBadges(placements[editing], with(density) { cell.toPx() }, with(density) { gap.toPx() }, {}, {}, onGroup = {})
                    }
                }
            }
        }
    }

    @Composable
    private fun StartGrid(items: List<Item>, backdrop: StartBackdrop, turnstile: TurnstileState? = null, editing: Int? = null) =
        StartGrid(backdrop, items, 4, turnstile, editing)

    @Composable
    private fun FolderScreen(icons: IconCache, backdrop: StartBackdrop, photoUri: String) {
        val photoImage = remember { scenery(0, 600, 600) }
        val columns = 4
        val play = folderTile(
            100, "play", TileSize.MEDIUM,
            listOf(
                appTile(11, "Music", TileSize.MEDIUM), appTile(12, "Videos", TileSize.MEDIUM), appTile(13, "Podcasts", TileSize.MEDIUM),
                appTile(14, "Games", TileSize.SMALL), appTile(15, "Photos", TileSize.SMALL), appTile(16, "News", TileSize.SMALL),
                appTile(17, "Fitness", TileSize.SMALL),
            ),
        )
        val trips = folderTile(
            101, "trips", TileSize.MEDIUM, listOf(appTile(21, "Maps", TileSize.SMALL), appTile(22, "Weather", TileSize.SMALL)),
            style = TileStyle.FULL_IMAGE, image = photoUri,
        )
        val tiles = listOf(
            trips, play, appTile(1, "Clock", TileSize.MEDIUM), appTile(2, "Messages", TileSize.MEDIUM),
            appTile(3, "Calendar", TileSize.WIDE), appTile(4, "Mail", TileSize.WIDE),
        )
        val open = play
        val layout = FolderLayout.compute(
            TilePlacement.layout(tiles.map { it.spec }, columns), tiles.indexOfFirst { it === open },
            open.children.map { it.spec.size }, columns,
        )
        val live = mapOf(
            1L to LiveContent.Clock(ClockState(now, now + 21 * hour), true),
            2L to LiveContent.Notifications(unread(3), true),
            3L to LiveContent.Calendar(now, events(), true),
            4L to LiveContent.Notifications(AppNotifications(12, emptyList()), true),
            12L to LiveContent.Notifications(AppNotifications(2, emptyList()), true),
        )
        CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                StatusBar()
                BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 40.dp)) {
                    val gap = 4.dp
                    val cell = (maxWidth + gap) / columns
                    @Composable
                    fun Tile(tile: ResolvedTile, p: io.github.danielphingston.metro.ui.start.Placement) {
                        Box(Modifier.offset(cell * p.x, cell * p.y).size(cell * p.width - gap, cell * p.height - gap)) {
                            if (tile === trips) {
                                // ImageDecoder is not available on the JVM, so draw what "full tile image" looks like: the photo and the chevron.
                                val m = TileMetrics(cell, androidx.compose.ui.unit.Density(3f))
                                val chevron = Metro.colors.onAccent
                                Box(Modifier.fillMaxSize()) {
                                    Image(photoImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).padding(end = m.dp(9f), bottom = m.dp(10f)).size(m.dp(12f), m.dp(7f)).drawBehind {
                                            val stroke = 1.5.dp.toPx()
                                            drawLine(chevron, Offset(0f, 0f), Offset(size.width / 2, size.height), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                                            drawLine(chevron, Offset(size.width / 2, size.height), Offset(size.width, 0f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                                        },
                                    )
                                }
                            } else if (tile.spec.content is TileContent.Folder) {
                                FolderTile(tile, cell, icons, true, open = tile === open)
                            } else {
                                TileFace(tile.app!!, tile.spec.size, live[tile.spec.id], false, true, icons, cell)
                            }
                        }
                    }
                    tiles.zip(layout.tiles).forEach { (tile, p) -> Tile(tile, p) }
                    val region = layout.region
                    if (region != null) {
                        FolderRegion(region, layout.tiles[tiles.indexOf(open)], columns, cell, gap, 8.dp, 8.dp)
                        open.children.zip(layout.children).forEach { (tile, p) -> Tile(tile, p) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsPreview() {
        Column(Modifier.fillMaxSize().background(Metro.colors.background).padding(start = 20.dp, end = 20.dp, top = 40.dp)) {
            MetroText("METRO SETTINGS", MetroType.appName, color = Metro.colors.subtle)
            MetroText("start+theme", MetroType.pageTitle, maxLines = 1)
            Section("start background")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroChoice("solid", false, {}, Modifier.weight(1f))
                MetroChoice("photo tiles", true, {}, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroChoice("full photo", false, {}, Modifier.weight(1f))
                MetroChoice("wallpaper", false, {}, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            MetroText("Each tile shows the part of your photo behind it, like a window onto one picture.", MetroType.caption, color = Metro.colors.subtle)
            Spacer(Modifier.height(12.dp))
            MetroButton("change photo", {})
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                MetroText("Tile transparency", MetroType.body, modifier = Modifier.weight(1f))
                MetroText("55%", MetroType.body, color = Metro.colors.accent)
            }
            MetroSlider(0.55f, {})
            MetroToggle("Accent colour from my background", true, {}, description = "Using a colour from your photo")
            Section("accent colour")
            AccentGrid(0xFFD80073.toInt()) {}
        }
    }

    // ------------------------------------------------------------------ apps and icons

    private fun app(label: String, installTime: Long = 0L, uninstallable: Boolean = true) = AppInfo(
        key = AppKey("com.example.${label.lowercase()}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = uninstallable,
        installTime = installTime,
        loadIcon = { _, _ ->
            val glyph = GlyphDrawable(label)
            AdaptiveIconDrawable(ColorDrawable(0xFF444444.toInt()), glyph, glyph)
        },
    )

    /** A clean white line glyph for a generic app name, drawn in the middle of the adaptive layer. */
    private class GlyphDrawable(private val label: String) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val box = b.width() * 0.5f
            canvas.save()
            canvas.translate(b.exactCenterX() - box / 2, b.exactCenterY() - box / 2)
            val k = box / 24f
            canvas.scale(k, k)
            paint.strokeWidth = 1.9f
            Glyphs.draw(label, canvas, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Line glyphs on a 24 x 24 grid. */
    private object Glyphs {
        fun draw(label: String, c: Canvas, p: Paint) {
            fun stroke(path: Path) { p.style = Paint.Style.STROKE; c.drawPath(path, p) }
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) { p.style = Paint.Style.STROKE; c.drawLine(x1, y1, x2, y2, p) }
            fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) {
                p.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
                c.drawCircle(x, y, r, p)
            }
            fun rr(l: Float, t: Float, w: Float, h: Float, r: Float) {
                p.style = Paint.Style.STROKE
                c.drawRoundRect(RectF(l, t, l + w, t + h), r, r, p)
            }
            fun path(build: Path.() -> Unit) = stroke(Path().apply(build))
            when (label) {
                "Phone" -> path {
                    moveTo(6f, 4.5f); lineTo(9f, 4.5f); lineTo(10.2f, 8.5f); lineTo(8.3f, 10f)
                    cubicTo(9.3f, 12.2f, 11.8f, 14.7f, 14f, 15.7f); lineTo(15.5f, 13.8f); lineTo(19.5f, 15f); lineTo(19.5f, 18f)
                    cubicTo(19.5f, 19f, 18.7f, 19.5f, 18f, 19.5f); cubicTo(10.5f, 19.5f, 4.5f, 13.5f, 4.5f, 6f)
                    cubicTo(4.5f, 5.2f, 5.2f, 4.5f, 6f, 4.5f); close()
                }
                "Messages" -> {
                    path {
                        moveTo(7f, 4.5f); lineTo(17f, 4.5f); cubicTo(18.9f, 4.5f, 20f, 5.6f, 20f, 7.5f); lineTo(20f, 13f)
                        cubicTo(20f, 14.9f, 18.9f, 16f, 17f, 16f); lineTo(11f, 16f); lineTo(7f, 19.5f); lineTo(7f, 16f)
                        cubicTo(5.1f, 16f, 4f, 14.9f, 4f, 13f); lineTo(4f, 7.5f); cubicTo(4f, 5.6f, 5.1f, 4.5f, 7f, 4.5f); close()
                    }
                    line(8f, 8.5f, 16f, 8.5f); line(8f, 12f, 13f, 12f)
                }
                "Chat" -> {
                    path {
                        moveTo(12f, 4f); cubicTo(16.9f, 4f, 20.5f, 7.3f, 20.5f, 11.5f); cubicTo(20.5f, 15.7f, 16.9f, 19f, 12f, 19f)
                        cubicTo(10.9f, 19f, 9.9f, 18.8f, 9f, 18.5f); lineTo(4.5f, 20.5f); lineTo(5.8f, 16.6f)
                        cubicTo(4.5f, 15.4f, 3.5f, 13.6f, 3.5f, 11.5f); cubicTo(3.5f, 7.3f, 7.1f, 4f, 12f, 4f); close()
                    }
                    circle(8.5f, 11.5f, 1f, true); circle(12f, 11.5f, 1f, true); circle(15.5f, 11.5f, 1f, true)
                }
                "Mail" -> { rr(3f, 5.5f, 18f, 13f, 2.2f); path { moveTo(3.5f, 7.5f); lineTo(12f, 13.3f); lineTo(20.5f, 7.5f) } }
                "Music" -> {
                    path { moveTo(9f, 17f); lineTo(9f, 6.5f); lineTo(19f, 4.5f); lineTo(19f, 15f) }
                    line(9f, 10f, 19f, 8f)
                    circle(6.8f, 17.3f, 2.4f); circle(16.8f, 15.3f, 2.4f)
                }
                "Photos" -> {
                    rr(3f, 4.5f, 18f, 15f, 2.2f); circle(8.5f, 9.5f, 1.7f)
                    path { moveTo(3.5f, 17f); lineTo(9f, 12.3f); lineTo(13f, 15.8f); lineTo(16f, 13f); lineTo(20.5f, 17f) }
                }
                "Camera" -> {
                    rr(3f, 7f, 18f, 12.5f, 2.5f); circle(12f, 13.2f, 3.6f)
                    path { moveTo(8.5f, 7f); lineTo(10f, 4.5f); lineTo(14f, 4.5f); lineTo(15.5f, 7f) }
                }
                "Maps" -> {
                    path {
                        moveTo(5.5f, 10f); cubicTo(5.5f, 6.4f, 8.4f, 3.5f, 12f, 3.5f); cubicTo(15.6f, 3.5f, 18.5f, 6.4f, 18.5f, 10f)
                        cubicTo(18.5f, 14.5f, 12f, 21f, 12f, 21f); cubicTo(12f, 21f, 5.5f, 14.5f, 5.5f, 10f); close()
                    }
                    circle(12f, 10f, 2.4f)
                }
                "Clock" -> { circle(12f, 12f, 8.5f); path { moveTo(12f, 6.8f); lineTo(12f, 12f); lineTo(15.8f, 14.2f) } }
                "Alarms" -> {
                    circle(12f, 13f, 7.3f); path { moveTo(12f, 8.8f); lineTo(12f, 13f); lineTo(15f, 14.8f) }
                    line(3.5f, 6.2f, 6.5f, 3.6f); line(20.5f, 6.2f, 17.5f, 3.6f)
                }
                "Calendar" -> {
                    rr(3.5f, 5f, 17f, 15.5f, 2.5f); line(3.5f, 10f, 20.5f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f)
                    circle(8f, 14f, 0.9f, true); circle(12f, 14f, 0.9f, true); circle(16f, 14f, 0.9f, true); circle(8f, 17.3f, 0.9f, true); circle(12f, 17.3f, 0.9f, true)
                }
                "Settings" -> {
                    circle(12f, 12f, 3f); circle(12f, 12f, 6.8f)
                    for (i in 0 until 8) {
                        val a = i * PI / 4
                        line(12f + 6.8f * cos(a).toFloat(), 12f + 6.8f * sin(a).toFloat(), 12f + 9.4f * cos(a).toFloat(), 12f + 9.4f * sin(a).toFloat())
                    }
                }
                "Store" -> {
                    rr(4.5f, 8.5f, 15f, 11.5f, 2.2f)
                    path { moveTo(8.5f, 11f); lineTo(8.5f, 7.5f); cubicTo(8.5f, 5.5f, 10f, 4f, 12f, 4f); cubicTo(14f, 4f, 15.5f, 5.5f, 15.5f, 7.5f); lineTo(15.5f, 11f) }
                }
                "Notes" -> { rr(5f, 3.5f, 14f, 17f, 2.2f); line(8.5f, 9f, 15.5f, 9f); line(8.5f, 12.5f, 15.5f, 12.5f); line(8.5f, 16f, 12.5f, 16f) }
                "Files" -> path {
                    moveTo(3.5f, 7f); cubicTo(3.5f, 6f, 4f, 5.5f, 5f, 5.5f); lineTo(9.5f, 5.5f); lineTo(11.5f, 8.2f); lineTo(19f, 8.2f)
                    cubicTo(20f, 8.2f, 20.5f, 8.7f, 20.5f, 9.5f); lineTo(20.5f, 18f); cubicTo(20.5f, 19f, 20f, 19.5f, 19f, 19.5f)
                    lineTo(5f, 19.5f); cubicTo(4f, 19.5f, 3.5f, 19f, 3.5f, 18f); close()
                }
                "Games" -> {
                    rr(2.5f, 7.5f, 19f, 10f, 5f); line(6f, 12.5f, 10f, 12.5f); line(8f, 10.5f, 8f, 14.5f)
                    circle(15.3f, 11.5f, 1f, true); circle(18f, 13.8f, 1f, true)
                }
                "Fitness" -> path {
                    moveTo(12f, 20f); cubicTo(12f, 20f, 4f, 14.5f, 4f, 9f); cubicTo(4f, 6.5f, 6f, 4.5f, 8.5f, 4.5f)
                    cubicTo(10f, 4.5f, 11.3f, 5.3f, 12f, 6.5f); cubicTo(12.7f, 5.3f, 14f, 4.5f, 15.5f, 4.5f)
                    cubicTo(18f, 4.5f, 20f, 6.5f, 20f, 9f); cubicTo(20f, 14.5f, 12f, 20f, 12f, 20f); close()
                }
                "News" -> {
                    rr(4f, 3.5f, 16f, 17f, 2.2f); line(7.5f, 7.5f, 16.5f, 7.5f); rr(7.5f, 10.5f, 4.5f, 4.5f, 0.6f)
                    line(14f, 11f, 16.5f, 11f); line(14f, 14.5f, 16.5f, 14.5f); line(7.5f, 17.8f, 16.5f, 17.8f)
                }
                "Podcasts" -> {
                    rr(9f, 3.5f, 6f, 10f, 3f)
                    path { moveTo(6f, 11f); cubicTo(6f, 14.5f, 8.7f, 17f, 12f, 17f); cubicTo(15.3f, 17f, 18f, 14.5f, 18f, 11f) }
                    line(12f, 17f, 12f, 20.5f); line(9f, 20.5f, 15f, 20.5f)
                }
                "Weather" -> path {
                    moveTo(7f, 18.5f); lineTo(17f, 18.5f); cubicTo(19.2f, 18.5f, 21f, 17f, 21f, 14.8f); cubicTo(21f, 12.8f, 19.5f, 11.3f, 17.6f, 11.1f)
                    cubicTo(17.2f, 8.3f, 14.9f, 6.5f, 12.3f, 6.5f); cubicTo(9.8f, 6.5f, 7.7f, 8.2f, 7.3f, 10.6f)
                    cubicTo(5.2f, 10.9f, 3.5f, 12.5f, 3.5f, 14.6f); cubicTo(3.5f, 16.8f, 5.1f, 18.5f, 7f, 18.5f); close()
                }
                "Calculator" -> {
                    rr(5f, 3.5f, 14f, 17f, 2.5f); rr(8f, 6.5f, 8f, 3f, 0.8f)
                    for (x in listOf(9f, 12f, 15f)) for (y in listOf(13.3f, 17f)) circle(x, y, 0.9f, true)
                }
                "Contacts" -> { circle(12f, 8.3f, 3.6f); path { moveTo(5f, 20f); cubicTo(5f, 16f, 8f, 13.8f, 12f, 13.8f); cubicTo(16f, 13.8f, 19f, 16f, 19f, 20f) } }
                "Torch" -> path { moveTo(13f, 3f); lineTo(5.5f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 21f); lineTo(18.5f, 10f); lineTo(12.5f, 10f); close() }
                "Videos" -> { rr(3f, 5.5f, 18f, 13f, 3f); path { moveTo(10.5f, 9.3f); lineTo(15.2f, 12f); lineTo(10.5f, 14.7f); close() } }
                else -> circle(12f, 12f, 8f)
            }
        }
    }

    // ------------------------------------------------------------------ procedural art

    private val palette = intArrayOf(
        0xFF1BA1E2.toInt(), 0xFFD80073.toInt(), 0xFF6A00FF.toInt(), 0xFFFA6800.toInt(), 0xFF00A300.toInt(), 0xFFE3A21A.toInt(),
        0xFF0050EF.toInt(), 0xFFAA00FF.toInt(), 0xFF00ABA9.toInt(), 0xFFE51400.toInt(), 0xFF647687.toInt(), 0xFF76608A.toInt(),
    )

    /** An abstract person: a gradient with a head and shoulders in a lighter tone. Never a real face. */
    private fun avatar(seed: Int, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val a = palette[(seed * 5 + 1) % palette.size]
        val b = palette[(seed * 5 + 4) % palette.size]
        p.shader = LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), a, b, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, px.toFloat(), px.toFloat(), p)
        p.shader = null
        p.color = android.graphics.Color.argb(120, 255, 255, 255)
        val s = px.toFloat()
        val shift = (seed % 3 - 1) * s * 0.04f
        c.drawCircle(s * 0.5f + shift, s * 0.38f, s * 0.19f, p)
        val body = Path().apply {
            moveTo(s * 0.12f + shift, s * 1.02f)
            cubicTo(s * 0.12f + shift, s * 0.66f, s * 0.3f, s * 0.6f, s * 0.5f + shift, s * 0.6f)
            cubicTo(s * 0.7f, s * 0.6f, s * 0.88f + shift, s * 0.66f, s * 0.88f + shift, s * 1.02f)
            close()
        }
        c.drawPath(body, p)
        return bmp
    }

    /** Retro album cover: a sun over stripes. */
    private fun albumArt(variant: Int = 0): ImageBitmap {
        val s = 512
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tops = listOf(0xFF1B1464, 0xFF3A0CA3, 0xFF03045E)
        val mids = listOf(0xFFC2185B, 0xFFF72585, 0xFF7209B7)
        val suns = listOf(0xFFFFB347, 0xFFFFE066, 0xFF4CC9F0)
        p.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), tops[variant].toInt(), mids[variant].toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.shader = LinearGradient(0f, s * 0.2f, 0f, s * 0.75f, suns[variant].toInt(), mids[variant].toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(s / 2f, s * 0.48f, s * 0.27f, p)
        p.shader = null
        p.color = tops[variant].toInt()
        for (i in 0 until 6) {
            val y = s * 0.5f + i * s * 0.06f
            c.drawRect(0f, y, s.toFloat(), y + (i + 1) * s * 0.008f + 2f, p)
        }
        p.color = tops[variant].toInt()
        c.drawRect(0f, s * 0.82f, s.toFloat(), s.toFloat(), p)
        return bmp.asImageBitmap()
    }

    /** A made-up landscape: [kind] 0 is dusk hills, 1 a mountain lake at dawn, 2 a dune sunset. */
    private fun scenery(kind: Int, w: Int, h: Int): ImageBitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val skies = listOf(
            intArrayOf(0xFF1B2A6B.toInt(), 0xFF7B4FA3.toInt(), 0xFFF28C6B.toInt(), 0xFFFFD98E.toInt()),
            intArrayOf(0xFF0E3B5C.toInt(), 0xFF2E8BA6.toInt(), 0xFFF2B5A0.toInt(), 0xFFFFE8C8.toInt()),
            intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFFFFAF7B.toInt(), 0xFFFFE1A8.toInt()),
        )[kind]
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), skies, floatArrayOf(0f, 0.4f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFFFFF3CC.toInt()
        c.drawCircle(w * (0.3f + 0.4f * kind / 2f), h * 0.52f, h * 0.12f, p)
        val layers = listOf(0xFF4A3A7A, 0xFF2D2557, 0xFF171236)
        layers.forEachIndexed { i, col ->
            p.color = col.toInt()
            val base = h * (0.62f + 0.12f * i)
            val hill = Path().apply {
                moveTo(0f, h.toFloat()); lineTo(0f, base)
                var x = 0f
                val rnd = Random(kind * 10 + i)
                while (x < w) {
                    val peak = base - h * (0.05f + 0.1f * rnd.nextFloat()) * (if (kind == 1) 1.8f else 1f)
                    quadTo(x + w * 0.2f, peak, x + w * 0.4f, base)
                    x += w * 0.4f
                }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            c.drawPath(hill, p)
        }
        return bmp.asImageBitmap()
    }

    /**
     * An abstract neon wallpaper, taller than the screen like a real photo background: deep gradients
     * crossed by soft glowing ribbons. [kind]: 0 neon (navy, violet, cyan, magenta), 1 sunset, 2 aurora.
     */
    private fun wallpaper(kind: Int): ImageBitmap {
        val w = 1233
        val h = (2742 * (1 + BackgroundPhoto.HEADROOM)).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bg = when (kind) {
            0 -> intArrayOf(0xFF070B2A.toInt(), 0xFF1A1055.toInt(), 0xFF3B0F6B.toInt(), 0xFF0A1A44.toInt())
            1 -> intArrayOf(0xFF1D1040.toInt(), 0xFF6A1B6E.toInt(), 0xFFC2335A.toInt(), 0xFFF28C3A.toInt())
            else -> intArrayOf(0xFF04122B.toInt(), 0xFF0A3B54.toInt(), 0xFF0F6B6B.toInt(), 0xFF2B1B5E.toInt())
        }
        p.shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), bg, floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val glows = when (kind) {
            0 -> listOf(Triple(0.85f, 0.12f, 0xFF00E5FF), Triple(0.1f, 0.4f, 0xFFFF2BD6), Triple(0.9f, 0.7f, 0xFF7C4DFF), Triple(0.2f, 0.92f, 0xFF00B8FF))
            1 -> listOf(Triple(0.8f, 0.78f, 0xFFFFC857), Triple(0.15f, 0.3f, 0xFFFF4D8D), Triple(0.9f, 0.2f, 0xFF9B5DE5))
            else -> listOf(Triple(0.2f, 0.2f, 0xFF2EF2B1), Triple(0.9f, 0.5f, 0xFF8A5CFF), Triple(0.3f, 0.85f, 0xFF19D3F5))
        }
        for ((fx, fy, col) in glows) {
            val r = w * 0.9f
            p.shader = RadialGradient(
                fx * w, fy * h, r, intArrayOf((0x88000000.toInt() or (col.toInt() and 0xFFFFFF)), 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        p.shader = null
        val rnd = Random(7 + kind)
        val ribbonColors = when (kind) {
            0 -> listOf(0xFF00E5FF, 0xFF7C4DFF, 0xFFFF2BD6, 0xFF2979FF)
            1 -> listOf(0xFFFFC857, 0xFFFF4D8D, 0xFFFF8A3D, 0xFFB86BFF)
            else -> listOf(0xFF2EF2B1, 0xFF19D3F5, 0xFF8A5CFF, 0xFF6BFFB8)
        }
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 12) {
            // A fluid curve: a walk whose heading keeps turning, so it swirls instead of waving.
            val path = Path()
            var x = rnd.nextFloat() * w
            var y = rnd.nextFloat() * h
            var angle = rnd.nextFloat() * 2 * PI.toFloat()
            val turn = (0.10f + rnd.nextFloat() * 0.14f) * (if (rnd.nextBoolean()) 1 else -1)
            val phase = rnd.nextFloat() * 6f
            val len = 120f + rnd.nextFloat() * 80f
            val y0 = y
            path.moveTo(x, y)
            for (step in 0 until 30) {
                angle += turn * (0.85f + 0.3f * sin(phase + step * 0.3f))
                val nx = x + len * cos(angle)
                val ny = y + len * sin(angle)
                path.quadTo(x + len * 0.5f * cos(angle - turn), y + len * 0.5f * sin(angle - turn), nx, ny)
                x = nx; y = ny
            }
            val col1 = ribbonColors[i % ribbonColors.size].toInt()
            val col2 = ribbonColors[(i + 1 + i / 4) % ribbonColors.size].toInt()
            fun tint(col: Int, a: Int) = (a shl 24) or (col and 0xFFFFFF)
            // A wide soft body, then a bundle of fine parallel lines: reads as a glowing silk ribbon.
            p.shader = LinearGradient(0f, y0, w.toFloat(), y0, tint(col1, 85), tint(col2, 85), Shader.TileMode.CLAMP)
            p.strokeWidth = 140f + rnd.nextFloat() * 160f
            p.maskFilter = BlurMaskFilter(70f, BlurMaskFilter.Blur.NORMAL)
            c.drawPath(path, p)
            p.maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
            for (k in -6..6) {
                val fade = 1f - kotlin.math.abs(k) / 7f
                p.shader = LinearGradient(0f, y0, w.toFloat(), y0, tint(col1, (30 + 170 * fade).toInt()), tint(col2, (30 + 170 * fade).toInt()), Shader.TileMode.CLAMP)
                p.strokeWidth = 2.5f + 2f * fade
                c.save()
                c.translate(k * 16f, k * 9f)
                c.drawPath(path, p)
                c.restore()
            }
        }
        p.maskFilter = null
        p.shader = null
        return bmp.asImageBitmap()
    }
}
