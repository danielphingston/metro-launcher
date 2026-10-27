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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlin.math.roundToInt
import io.github.danielphingston.metro.ui.components.MetroSlider
import androidx.compose.ui.Alignment
import io.github.danielphingston.metro.data.ScrubberHighlight
import io.github.danielphingston.metro.data.ScrubberHighlightPosition
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.TapCounter
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
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as MetroApplication).settings
        setContent {
            val settings by repository.settings.collectAsState(initial = null)
            val current = settings ?: return@setContent
            MetroTheme(current) {
                val colors = Metro.colors
                LaunchedEffect(colors) {
                    applySystemBars(darkIcons = !colors.isDark)
                    window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
                }
                SettingsScreen(current, repository)
            }
        }
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

@Composable
private fun SettingsScreen(settings: LauncherSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }

    val isDefaultHome = rememberOnResume { roleManager.isRoleHeld(RoleManager.ROLE_HOME) }
    val notificationAccess = rememberOnResume { NotificationAccess.isGranted(context) }
    var calendarAccess by remember { mutableStateOf(CalendarSource.hasPermission(context)) }
    val calendarRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        calendarAccess = it
    }
    val homeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        MetroText("METRO SETTINGS", MetroType.appName, color = colors.subtle)
        MetroText("start+theme", MetroType.pageTitle, maxLines = 1)

        if (!isDefaultHome) {
            Section("default home app")
            MetroText(
                "Make Metro your home screen so the home button opens your start screen.",
                MetroType.caption,
                color = colors.subtle,
            )
            Spacer(Modifier.height(10.dp))
            MetroButton("set as default", onClick = {
                homeRequest.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
            })
        }

        Section("theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                MetroChoice(
                    mode.name.lowercase(),
                    settings.theme == mode,
                    { scope.launch { repository.setTheme(mode) } },
                    Modifier.weight(1f),
                )
            }
        }

        Section("accent colour")
        val accentName = MetroAccents.firstOrNull { it.second.toArgb() == settings.accent }?.first ?: "custom"
        MetroText(accentName, MetroType.caption, color = colors.accent)
        Spacer(Modifier.height(10.dp))
        AccentGrid(settings.accent) { scope.launch { repository.setAccent(it) } }

        Section("start screen")
        if (LocalConfiguration.current.smallestScreenWidthDp >= LARGE_SCREEN_DP) {
            // Tiles keep about a phone's size on a tablet, so more columns fill the wider screen.
            MetroText("tiles per row", MetroType.caption, color = colors.subtle)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6, 8).forEach { count ->
                    MetroChoice("$count", settings.columns == count, { scope.launch { repository.setColumns(count) } })
                }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            MetroToggle(
                "Show more tiles",
                settings.columns > 4,
                { scope.launch { repository.setColumns(if (it) 6 else 4) } },
            )
        }
        MetroToggle(
            "Use themed app icons",
            settings.monochromeIcons,
            { scope.launch { repository.setMonochromeIcons(it) } },
            description = "White icons on tiles for apps that provide them",
        )
        MetroToggle(
            "Plain icons in app list",
            settings.appListPlainIcons,
            { scope.launch { repository.setAppListPlainIcons(it) } },
            description = "Show app icons without the coloured square",
        )
        MetroText("Scrubber highlight", MetroType.body)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScrubberHighlight.entries.forEach { highlight ->
                MetroChoice(
                    highlight.label,
                    settings.scrubberHighlight == highlight,
                    { scope.launch { repository.setScrubberHighlight(highlight) } },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MetroText("Highlight position", MetroType.body)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScrubberHighlightPosition.entries.forEach { position ->
                MetroChoice(
                    position.label,
                    settings.scrubberHighlightPosition == position,
                    { scope.launch { repository.setScrubberHighlightPosition(position) } },
                )
            }
        }
        // The slider follows the finger; the setting is saved when it lets go.
        val positionRange = LauncherSettings.MAX_SCRUBBER_POSITION - LauncherSettings.MIN_SCRUBBER_POSITION
        var positionDragged by remember { mutableStateOf<Float?>(null) }
        val positionFraction = positionDragged
            ?: ((settings.scrubberPosition - LauncherSettings.MIN_SCRUBBER_POSITION).toFloat() / positionRange)
        val positionPercent = LauncherSettings.MIN_SCRUBBER_POSITION + (positionFraction * positionRange).roundToInt()
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetroText("Scrubber position", MetroType.body, modifier = Modifier.weight(1f))
            MetroText("$positionPercent%", MetroType.body, color = Metro.colors.accent)
        }
        MetroSlider(
            value = positionFraction,
            onValueChange = { positionDragged = it },
            modifier = Modifier.padding(top = 2.dp),
            onValueChangeFinished = {
                positionDragged?.let {
                    scope.launch {
                        repository.setScrubberPosition(positionPercent)
                        positionDragged = null
                    }
                }
            },
        )
        Spacer(Modifier.height(10.dp))

        IconPackSettingsSection(settings, repository)

        SearchSettingsSection(settings, repository)

        BackgroundSettingsSection(settings, repository)

        Section("live tiles")
        MetroText(
            "Live tiles read your notifications on this phone to show counts and the latest " +
                "message on app tiles. Nothing leaves your device.",
            MetroType.caption,
            color = colors.subtle,
        )
        Spacer(Modifier.height(12.dp))
        if (notificationAccess) {
            MetroText("Notification access is on", MetroType.body)
        } else {
            MetroButton("turn on notification access", onClick = {
                context.startActivity(NotificationAccess.settingsIntent(context))
            })
        }
        MetroToggle(
            "Show message text on tiles",
            settings.showNotificationContent,
            { scope.launch { repository.setShowNotificationContent(it) } },
            description = if (settings.showNotificationContent) "Tiles flip to show the latest message" else "Counts only",
        )
        Spacer(Modifier.height(8.dp))
        if (calendarAccess) {
            MetroText("Calendar tile shows your next appointment", MetroType.body)
        } else {
            MetroButton("show appointments on calendar tile", onClick = {
                calendarRequest.launch(Manifest.permission.READ_CALENDAR)
            })
        }

        WeatherSettingsSection()
        PhotosSettingsSection()
        PeopleSettingsSection()

        QuickTilesSettingsSection(settings, repository)

        Section("battery")
        MetroText(
            "Metro does no background work: live tiles update from notifications as they arrive " +
                "and animate only while you look at them. If tiles stop updating, some phones have " +
                "put the app to sleep: set battery usage to \"Unrestricted\" in app info.",
            MetroType.caption,
            color = colors.subtle,
        )
        Spacer(Modifier.height(10.dp))
        MetroButton("open app info", onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        })

        CrashLogSettingsSection()

        Section("about")
        // Seven quick taps toggle the test unlock (see Purchases.setTestUnlock); inert unless
        // the build allows it.
        val purchases = (context.applicationContext as MetroApplication).purchases
        val versionTaps = remember { TapCounter() }
        MetroText(
            "Metro Launcher ${BuildConfig.VERSION_NAME}",
            MetroType.caption,
            modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) {
                if (BuildConfig.TEST_UNLOCK && versionTaps.tap(System.currentTimeMillis())) {
                    val on = !purchases.testUnlock.value
                    purchases.setTestUnlock(on)
                    Toast.makeText(
                        context,
                        if (on) "Quick settings tiles unlocked for testing" else "Quick settings tiles locked again",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            color = colors.subtle,
        )
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
