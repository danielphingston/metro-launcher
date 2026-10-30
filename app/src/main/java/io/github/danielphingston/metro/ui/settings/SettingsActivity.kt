package io.github.danielphingston.metro.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.danielphingston.metro.BuildConfig
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ScrubberHighlight
import io.github.danielphingston.metro.data.ScrubberHighlightPosition
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.live.CalendarSource
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.ui.LARGE_SCREEN_DP
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.components.applySystemBars
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroAccents
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.tiles.PeopleSettingsSection
import io.github.danielphingston.metro.ui.tiles.PhotosSettingsSection
import io.github.danielphingston.metro.ui.tiles.WeatherSettingsSection

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as MetroApplication).settings
        val initialPage = SettingsPage.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_PAGE) }
        setContent {
            val settings by repository.settings.collectAsState(initial = null)
            val current = settings ?: return@setContent
            MetroTheme(current) {
                val colors = Metro.colors
                LaunchedEffect(colors) {
                    applySystemBars(darkIcons = !colors.isDark)
                    window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
                }
                SettingsScreen(current, repository, initialPage)
            }
        }
    }

    internal companion object {
        private const val EXTRA_PAGE = "page"

        /** Opens settings on [page] (the menu when null), e.g. so a tile that needs a choice can take the user to it. */
        fun intent(context: android.content.Context, page: SettingsPage? = null): android.content.Intent =
            android.content.Intent(context, SettingsActivity::class.java).apply { page?.let { putExtra(EXTRA_PAGE, it.name) } }
    }
}

/** Re-evaluates [read] whenever the screen resumes, e.g. after returning from system settings. */
@Composable
internal fun <T> rememberOnResume(read: () -> T): T {
    var value by remember { mutableStateOf(read()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { value = read() }
    }
    return value
}

/** One page of the settings: a menu entry with a one-line summary of what is inside. */
internal enum class SettingsPage(val title: String, val summary: String, val parent: SettingsPage? = null) {
    START("start", "tiles per row, padding, background, transparency"),
    APP_LIST("app list", "icons, scrubber highlight and position"),
    SEARCH("search", "search engine, pull distance, smart search"),
    APPEARANCE("appearance", "theme, accent colour, icon pack"),
    ANIMATIONS("animations", "launch animation, speed, tilt, live tile flips"),
    TILES("tiles", "weather, photos, people, calendar, notifications"),
    METRO_PLUS("metro plus", "quick settings tiles, assistant, smart search"),
    ABOUT("about", "default home app, app info, crash log, version"),

    // Long lists of the search settings, each on a page of its own (reached from the search page, not listed in the menu).
    SEARCH_IN_APPS("search in apps", "which apps the results can search, and their order", SEARCH),
    SEARCH_KEYWORDS("search keywords", "short words that search one app or site", SEARCH),
}

@Composable
internal fun SettingsScreen(
    settings: LauncherSettings,
    repository: SettingsRepository,
    initialPage: SettingsPage? = null,
) {
    val colors = Metro.colors
    var page by rememberSaveable { mutableStateOf(initialPage?.name) }
    val menuScroll = rememberScrollState()
    val pageScrolls = SettingsPage.entries.associateWith { rememberScrollState() }
    // A sub page goes back to its parent page, any other page to the menu.
    BackHandler(enabled = page != null) { page = SettingsPage.entries.firstOrNull { it.name == page }?.parent?.name }

    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize().background(colors.background),
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
        label = "settingsPage",
    ) { name ->
        val current = SettingsPage.entries.firstOrNull { it.name == name }
        if (current == null) {
            SettingsMenu(menuScroll) { page = it.name }
        } else {
            SettingsPageFrame(current, pageScrolls.getValue(current)) {
                when (current) {
                    SettingsPage.START -> StartPage(settings, repository)
                    SettingsPage.APP_LIST -> AppListPage(settings, repository)
                    SettingsPage.SEARCH -> SearchSettingsSection(
                        settings, repository,
                        onOpenMetroPlus = { page = SettingsPage.METRO_PLUS.name },
                        onOpenPage = { page = it.name },
                    )
                    SettingsPage.SEARCH_IN_APPS -> SearchInAppsPageHost(settings, repository, onOpenMetroPlus = { page = SettingsPage.METRO_PLUS.name })
                    SettingsPage.SEARCH_KEYWORDS -> SearchKeywordsPageHost(settings, repository, onOpenMetroPlus = { page = SettingsPage.METRO_PLUS.name })
                    SettingsPage.APPEARANCE -> AppearancePage(settings, repository)
                    SettingsPage.ANIMATIONS -> AnimationsPage(settings, repository)
                    SettingsPage.TILES -> TilesPage(settings, repository)
                    SettingsPage.METRO_PLUS -> MetroPlusSettingsSection(settings, repository, onOpenSearchSettings = { page = SettingsPage.SEARCH.name })
                    SettingsPage.ABOUT -> AboutPage()
                }
            }
        }
    }
}

@Composable
private fun SettingsMenu(scroll: ScrollState, onOpen: (SettingsPage) -> Unit) {
    val colors = Metro.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        MetroText("METRO LAUNCHER", MetroType.appName, color = colors.subtle)
        MetroText("settings", MetroType.pageTitle, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        SettingsPage.entries.filter { it.parent == null }.forEach { entry ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .metroTilt()
                    .clickable(role = Role.Button) { onOpen(entry) }
                    .padding(vertical = 10.dp),
            ) {
                MetroText(entry.title, MetroType.sectionTitle.copy(fontSize = 30.sp), maxLines = 1)
                MetroText(entry.summary, MetroType.caption, color = colors.subtle, maxLines = 3)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun SettingsPageFrame(page: SettingsPage, scroll: ScrollState, content: @Composable ColumnScope.() -> Unit) {
    val colors = Metro.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        MetroText("METRO SETTINGS", MetroType.appName, color = colors.subtle)
        MetroText(page.title, MetroType.pageTitle.copy(fontSize = if (page.title.length > 12) 34.sp else 46.sp), maxLines = 1)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** A settings section heading; feature sections reuse it. */
@Composable
internal fun Section(title: String) {
    Spacer(Modifier.height(36.dp))
    MetroText(title, MetroType.sectionTitle, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

/** The twenty accent colours, five to a row; the chosen one gets a ring. */
@Composable
internal fun AccentGrid(selectedAccent: Int, onPick: (Int) -> Unit) {
    val colors = Metro.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroAccents.chunked(5).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { (name, color) ->
                    val selected = color.toArgb() == selectedAccent
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .metroTilt()
                            .then(if (selected) Modifier.border(2.dp, colors.foreground) else Modifier)
                            .padding(if (selected) 4.dp else 0.dp)
                            .background(color)
                            .clickable(role = Role.RadioButton) { onPick(color.toArgb()) }
                            .semantics {
                                contentDescription = name
                                this.selected = selected
                            },
                    )
                }
            }
        }
    }
}
