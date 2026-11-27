package io.github.danielphingston.metro.previews

import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileOptions
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.CalendarEvent
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.live.NotificationPreview
import io.github.danielphingston.metro.ui.applist.AppListContent
import io.github.danielphingston.metro.ui.applist.appListRows
import io.github.danielphingston.metro.ui.onboarding.OnboardingScreen
import io.github.danielphingston.metro.ui.onboarding.OnboardingStep
import io.github.danielphingston.metro.ui.search.SearchContent
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.settings.SettingsScreen
import io.github.danielphingston.metro.ui.start.CoachMark
import io.github.danielphingston.metro.ui.start.CoachMarkBar
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.LiveTileEntry
import io.github.danielphingston.metro.ui.start.MetroTilesPanel
import io.github.danielphingston.metro.ui.start.TileCustomiseSheet
import io.github.danielphingston.metro.ui.start.TileFace
import io.github.danielphingston.metro.ui.start.TilePlacement
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.startBackdrop
import io.github.danielphingston.metro.ui.tiles.FeedEditor

/*
 * Compose previews of every main screen, for Android Studio and for Google's Android CLI
 * (`tools/preview-check.sh` renders them all and checks their layout trees; CI runs it).
 * Debug source set only: none of this is in the release APK. The Roborazzi screenshot tests in
 * src/test stay the visual gate; these show a screen without a device or a test run.
 */

private const val NOW = 1_793_000_000_000L // a fixed time, so live tiles render the same on every run
private const val HOUR = 3_600_000L
private const val NIGHT = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
private const val DAY = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL

/** A phone the size the screenshot tests use. */
@Preview(name = "dark", widthDp = 411, heightDp = 891, uiMode = NIGHT)
@Preview(name = "light", widthDp = 411, heightDp = 891, uiMode = DAY)
private annotation class PhonePreviews

/** The same phone with the largest font size: text must wrap, never overflow or overlap. */
@Preview(name = "dark", widthDp = 411, heightDp = 891, uiMode = NIGHT)
@Preview(name = "font 2x", widthDp = 411, heightDp = 891, uiMode = NIGHT, fontScale = 2f)
private annotation class FontPreviews

@Composable
private fun Screen(theme: ThemeMode = ThemeMode.SYSTEM, columns: Int = 4, content: @Composable BoxScope.() -> Unit) {
    MetroTheme(LauncherSettings(theme = theme, columns = columns)) {
        Box(Modifier.fillMaxSize().background(Metro.colors.background)) { content() }
    }
}

private fun app(label: String, installTime: Long = NOW - 60 * 24 * HOUR) = AppInfo(
    key = AppKey("com.example.${label.lowercase()}", "Main", 0),
    label = label,
    user = Process.myUserHandle(),
    component = ComponentName("com.example.${label.lowercase()}", "Main"),
    isUninstallable = true,
    installTime = installTime,
    loadIcon = { _, _ -> ColorDrawable(0xFF444444.toInt()) },
)

private val appNames = listOf(
    "Alarms", "Calculator", "Calendar", "Camera", "Clock", "Contacts", "Drive", "Files", "Gmail", "Maps",
    "Messages", "Music", "Notes", "Phone", "Photos", "Podcasts", "Settings", "Spotify", "Store", "Weather", "WhatsApp",
)

private fun apps() = appNames.map { name -> app(name, if (name == "Spotify") NOW - 12 * HOUR else NOW - 60 * 24 * HOUR) }

private class Sample(val app: AppInfo, val size: TileSize, val live: LiveContent? = null, val flipped: Boolean = false)

private fun samples(): List<Sample> {
    val messages = AppNotifications(3, listOf(NotificationPreview("Mom", "Are you coming for dinner tonight?", NOW)))
    val mail = AppNotifications(12, listOf(NotificationPreview("Contoso Travel", "Your flight is confirmed for Friday", NOW)))
    val events = listOf(CalendarEvent("Design review", NOW + HOUR, NOW + 2 * HOUR, false, "Room 4"))
    return listOf(
        Sample(app("Phone"), TileSize.MEDIUM),
        Sample(app("Messages"), TileSize.MEDIUM, LiveContent.Notifications(messages, true)),
        Sample(app("Outlook"), TileSize.WIDE, LiveContent.Notifications(mail, true), flipped = true),
        Sample(app("Calendar"), TileSize.WIDE, LiveContent.Calendar(NOW, events, false)),
        Sample(app("Clock"), TileSize.MEDIUM, LiveContent.Clock(ClockState(NOW, NOW + 20 * HOUR), false)),
        Sample(app("Camera"), TileSize.SMALL),
        Sample(app("Store"), TileSize.SMALL, LiveContent.Notifications(AppNotifications(2, emptyList()), true)),
        Sample(app("Settings"), TileSize.SMALL),
        Sample(app("Maps"), TileSize.SMALL),
        Sample(app("WhatsApp"), TileSize.WIDE, LiveContent.Notifications(messages, true)),
        Sample(app("Photos"), TileSize.MEDIUM),
        Sample(app("Music"), TileSize.MEDIUM),
    )
}

/** The start screen's tiles, laid out the way pinning them one after another does. */
@Composable
private fun StartTiles(columns: Int) {
    val context = LocalContext.current
    val icons = remember { IconCache(context) }
    val samples = remember { samples() }
    val backdrop = StartBackdrop.Solid
    CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                val gap = 4.dp
                val cell = (maxWidth + gap) / columns
                val placements = TilePlacement.layout(
                    samples.mapIndexed { i, sample -> TileSpec(i + 1L, sample.size, TileContent.Weather) }, columns,
                )
                samples.zip(placements).forEach { (sample, p) ->
                    Box(Modifier.offset(cell * p.x, cell * p.y).size(cell * p.width - gap, cell * p.height - gap)) {
                        TileFace(sample.app, sample.size, sample.live, sample.flipped, true, icons, cell)
                    }
                }
            }
        }
    }
}

@PhonePreviews
@Composable
private fun StartScreenPreview() = Screen { StartTiles(columns = 4) }

@Preview(name = "6 per row", widthDp = 411, heightDp = 891, uiMode = NIGHT)
@Composable
private fun StartMoreTilesPreview() = Screen(columns = 6) { StartTiles(columns = 6) }

@PhonePreviews
@Composable
private fun AppListPreview() = Screen {
    val context = LocalContext.current
    val icons = remember { IconCache(context) }
    val rows = remember { appListRows(apps(), "", NOW) }
    AppListContent(
        rows = rows, listState = rememberLazyListState(), icons = icons, monochrome = true, query = "",
        onQueryChange = {}, onSubmit = {}, onLetterClick = {}, onClick = { _, _ -> }, onLongClick = { _, _ -> },
    )
}

@PhonePreviews
@Composable
private fun SearchPreview() = Screen {
    val context = LocalContext.current
    val icons = remember { IconCache(context) }
    val rows = remember { searchRows(apps(), "m", emptyList(), SearchEngine.GOOGLE, NOW) }
    SearchContent(
        query = "m", onQueryChange = {}, onSubmit = {}, rows = rows, icons = icons, monochrome = true, plainIcons = false,
        focusRequester = remember { FocusRequester() }, onActivate = { _, _ -> }, onLongClick = { _, _ -> }, onDismiss = {},
    )
}

/**
 * The settings menu (null) and the pages that draw from [LauncherSettings] alone. The others read the
 * app object, the role or accessibility managers or installed apps while they draw, which a preview
 * has none of: search, appearance, metro plus, gestures, about, search in apps, search keywords and
 * "what can I type?". Layouts draws nothing without the app object. The Roborazzi tests
 * (SettingsScreenshotTest and others) cover all of them.
 */
private class SettingsPages : PreviewParameterProvider<SettingsPage?> {
    override val values = sequenceOf(
        null, SettingsPage.START, SettingsPage.APP_LIST, SettingsPage.ANIMATIONS, SettingsPage.TILES,
        SettingsPage.BACKUP,
    )
}

@FontPreviews
@Composable
private fun SettingsPreview(@PreviewParameter(SettingsPages::class) page: SettingsPage?) {
    val context = LocalContext.current
    val settings = LauncherSettings()
    MetroTheme(settings) { SettingsScreen(settings, remember { SettingsRepository(context) }, page) }
}

private class SetupSteps : PreviewParameterProvider<OnboardingStep> {
    override val values = OnboardingStep.entries.asSequence()
}

@FontPreviews
@Composable
private fun SetupPreview(@PreviewParameter(SetupSteps::class) step: OnboardingStep) {
    val settings = LauncherSettings(columns = 6)
    MetroTheme(settings) { OnboardingScreen(settings, {}, step) }
}

@FontPreviews
@Composable
private fun HintsPreview() = Screen {
    Box(Modifier.fillMaxSize()) {
        CoachMarkBar(CoachMark.HOLD_TILE) {}
        CoachMarkBar(CoachMark.EDITING) {}
    }
}

/** The customise sheet of an app tile and of a weather tile. */
private class CustomisedTiles : PreviewParameterProvider<TileSpec> {
    override val values = sequenceOf(
        TileSpec(1, TileSize.MEDIUM, TileContent.App(AppKey("com.example.phone", "Main", 0)), 0, 0),
        TileSpec(2, TileSize.WIDE, TileContent.Weather, 0, 0),
    )
}

@Preview(name = "dark", widthDp = 411, heightDp = 1300, uiMode = NIGHT)
@Composable
private fun TileCustomisePreview(@PreviewParameter(CustomisedTiles::class) spec: TileSpec) = Screen {
    TileCustomiseSheet(
        spec = spec, options = TileOptions.available(spec), startTransparency = 0,
        onShowName = {}, onShowIcon = {}, onIconSource = {}, onPhoto = {}, onBackground = {},
        onColour = {}, onTransparency = {}, onWidgetSettings = null, onDismiss = {},
    )
}

@Preview(name = "dark", widthDp = 411, heightDp = 891, uiMode = NIGHT)
@Composable
private fun MetroTilesMenuPreview() = Screen {
    val context = LocalContext.current
    val icons = remember { IconCache(context) }
    val live = listOf("weather" to TileContent.Weather, "photos" to TileContent.Photos, "people" to TileContent.People, "me" to TileContent.Me)
        .map { (label, content) -> LiveTileEntry(label, content, TileSize.WIDE) }
    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
        MetroTilesPanel(live, icons, onAddLive = {}, onAddTool = {}, onDismiss = {})
    }
}

@Preview(name = "dark", widthDp = 411, heightDp = 891, uiMode = NIGHT)
@Composable
private fun FeedEditorPreview() = Screen {
    FeedEditor(null, lemmyApp = null, isTaken = { false }, onDone = {}, onCancel = {})
}
