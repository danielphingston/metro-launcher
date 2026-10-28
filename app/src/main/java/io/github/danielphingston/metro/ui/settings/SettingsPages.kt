package io.github.danielphingston.metro.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import io.github.danielphingston.metro.ui.components.MetroSlider
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroAccents
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.tiles.PeopleSettingsSection
import io.github.danielphingston.metro.ui.tiles.PhotosSettingsSection
import io.github.danielphingston.metro.ui.tiles.WeatherSettingsSection
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// The pages of the settings (see SettingsScreen). Each takes the controls that used to share one long page.

@Composable
internal fun StartPage(settings: LauncherSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
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
    MetroText("Start screen padding", MetroType.caption, color = colors.subtle)
    PaddingSlider(
        "Top",
        settings.startTopPadding,
        LauncherSettings.MIN_START_TOP_PADDING,
        LauncherSettings.MAX_START_TOP_PADDING,
    ) { repository.setStartTopPadding(it) }
    PaddingSlider(
        "Bottom",
        settings.startBottomPadding,
        LauncherSettings.MIN_START_BOTTOM_PADDING,
        LauncherSettings.MAX_START_BOTTOM_PADDING,
    ) { repository.setStartBottomPadding(it) }
    PaddingSlider(
        "Left",
        settings.startLeftPadding,
        LauncherSettings.MIN_START_SIDE_PADDING,
        LauncherSettings.MAX_START_SIDE_PADDING,
    ) { repository.setStartLeftPadding(it) }
    PaddingSlider(
        "Right",
        settings.startRightPadding,
        LauncherSettings.MIN_START_SIDE_PADDING,
        LauncherSettings.MAX_START_SIDE_PADDING,
    ) { repository.setStartRightPadding(it) }
    Spacer(Modifier.height(10.dp))
    MetroToggle(
        "Accent colour arrows",
        settings.accentArrows,
        { scope.launch { repository.setAccentArrows(it) } },
        description = "Draw the buttons under the tiles in the accent colour",
    )

    BackgroundSettingsSection(settings, repository)
}

@Composable
internal fun AppListPage(settings: LauncherSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(36.dp))
    MetroToggle(
        "Plain icons in app list",
        settings.appListPlainIcons,
        { scope.launch { repository.setAppListPlainIcons(it) } },
        description = "Show app icons without the coloured square",
    )
    MetroToggle(
        "Use themed app icons",
        settings.monochromeIcons,
        { scope.launch { repository.setMonochromeIcons(it) } },
        description = "White icons on tiles for apps that provide them",
    )
    Spacer(Modifier.height(10.dp))
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
}

@Composable
internal fun AppearancePage(settings: LauncherSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
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
    AccentFromBackgroundToggle(settings, repository)

    IconPackSettingsSection(settings, repository)
}

@Composable
internal fun TilesPage(settings: LauncherSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
    val notificationAccess = rememberOnResume { NotificationAccess.isGranted(context) }
    var calendarAccess by remember { mutableStateOf(CalendarSource.hasPermission(context)) }
    val calendarRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        calendarAccess = it
    }

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
}

@Composable
internal fun AboutPage() {
    val context = LocalContext.current
    val colors = Metro.colors
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    val isDefaultHome = rememberOnResume { roleManager.isRoleHeld(RoleManager.ROLE_HOME) }
    val homeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

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
    MetroText(
        "Metro Launcher ${BuildConfig.VERSION_NAME}",
        MetroType.caption,
        color = colors.subtle,
    )
}
