package io.github.danielphingston.metro.ui.tiles

import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.live.QuickOptions
import io.github.danielphingston.metro.live.QuickSettingLogic
import io.github.danielphingston.metro.live.QuickState
import io.github.danielphingston.metro.live.QuickSettings
import io.github.danielphingston.metro.live.Torch
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.tileSurface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** What tapping a quick-setting tile does. */
object QuickSettingTiles {
    /**
     * Does what a tap on [kind]'s tile does: flips a setting in place, or says which screens
     * could do it. Returns the activities to try in order for the caller to open (the first that
     * starts wins), or nothing when the tap is already handled. A locked tile only shows a toast.
     */
    fun activate(context: Context, kind: QuickSettingKind, unlocked: Boolean): List<Intent> {
        if (!unlocked) {
            Toast.makeText(context, "unlock to use", Toast.LENGTH_SHORT).show()
            return emptyList()
        }
        val state = QuickSettings.read(context, kind)
        return when (kind) {
            QuickSettingKind.FLASHLIGHT -> {
                if (!Torch.set(context, !state.on)) Toast.makeText(context, "flashlight isn't available", Toast.LENGTH_SHORT).show()
                emptyList()
            }
            QuickSettingKind.DO_NOT_DISTURB -> {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (!manager.isNotificationPolicyAccessGranted) return listOf(policyAccess())
                // Priority only keeps starred calls and alarms: the gentle kind of do not disturb.
                manager.setInterruptionFilter(
                    if (state.on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                )
                emptyList()
            }
            QuickSettingKind.RINGER -> {
                val audio = context.getSystemService(AudioManager::class.java)
                // Silent is a do-not-disturb state: without policy access, cycle ring and vibrate only.
                val policy = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
                val next = QuickSettingLogic.nextRinger(audio.ringerMode)
                try {
                    audio.ringerMode = if (next == AudioManager.RINGER_MODE_SILENT && !policy) AudioManager.RINGER_MODE_NORMAL else next
                    emptyList()
                } catch (_: SecurityException) {
                    // Leaving silent while do not disturb is on also needs policy access.
                    listOf(policyAccess())
                }
            }
            QuickSettingKind.AUTO_ROTATE -> writeSetting(context) {
                Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (state.on) 0 else 1)
            }
            QuickSettingKind.BRIGHTNESS -> writeSetting(context) {
                val next = QuickSettingLogic.Brightness.entries[state.level].next()
                val level = next.value
                val resolver = context.contentResolver
                if (level == null) {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                } else {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
                }
            }
            // Apps can't switch these any more; their panels and settings pages do it in one more tap.
            QuickSettingKind.WIFI -> listOf(Intent(Settings.Panel.ACTION_WIFI))
            QuickSettingKind.MOBILE_DATA -> listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            QuickSettingKind.BLUETOOTH -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            QuickSettingKind.AIRPLANE -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            QuickSettingKind.LOCATION -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            QuickSettingKind.HOTSPOT -> listOf(Intent(TETHER_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            QuickSettingKind.BATTERY -> batteryIntents()
        }
    }

    /**
     * The battery page, as directly as each phone allows. ACTION_POWER_USAGE_SUMMARY goes through
     * a trampoline inside Settings that can flash the main screen for a moment before the battery
     * page; that is Settings' own and can't be removed on every OEM. Samsung's Device Care page
     * can be opened directly, so it goes first there (a missing or unexported one just fails over).
     */
    private fun batteryIntents(): List<Intent> {
        val generic = listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return generic
        val deviceCare = listOf("com.samsung.android.lool", "com.samsung.android.sm_cn", "com.samsung.android.sm").map {
            Intent().setComponent(ComponentName(it, "com.samsung.android.sm.battery.ui.BatteryActivity"))
        }
        return deviceCare + generic
    }

    /**
     * Opens what [activate] returned for [kind], the way [QuickSettingLogic.launchStyle] says.
     * Only the turnstile style goes through [launcher]; the others never touch the turnstile, so
     * the start screen is not left "away" and nothing replays when the user comes back.
     */
    fun open(context: Context, kind: QuickSettingKind, intents: List<Intent>, bounds: ComposeRect?, launcher: AppLauncher) {
        when (QuickSettingLogic.launchStyle(kind)) {
            QuickSettingLogic.LaunchStyle.TURNSTILE ->
                launcher.open(bounds) { screenBounds, options -> start(context, intents, screenBounds, options) }
            QuickSettingLogic.LaunchStyle.OVER_START -> start(context, intents, null, null)
            QuickSettingLogic.LaunchStyle.NO_ANIMATION ->
                start(context, intents, null, ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle())
        }
    }

    /** Starts the first of [intents] the system accepts, like [io.github.danielphingston.metro.ui.AppLauncher.start]. */
    fun start(context: Context, intents: List<Intent>, bounds: Rect?, options: Bundle?): Boolean = intents.any { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).sourceBounds = bounds
        runCatching { context.startActivity(intent, options) }.isSuccess
    }

    // Hidden from the SDK, but the settings app has answered it for years; the wireless page is the fallback.
    private const val TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"

    private fun policyAccess() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** Runs [write] if the app may change system settings, else lists the screen where the user allows it. */
    private fun writeSetting(context: Context, write: () -> Unit): List<Intent> {
        if (!Settings.System.canWrite(context)) {
            return listOf(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        try {
            write()
        } catch (_: SecurityException) {
            // The permission was withdrawn between the check and the write.
        }
        return emptyList()
    }
}

/** A quick-setting tile: a glyph, the setting's state and its name, dimmed while the setting is off. */
@Composable
fun QuickSettingTile(tile: ResolvedTile, cellSize: Dp) {
    val kind = (tile.spec.content as? TileContent.QuickSetting)?.kind ?: return
    QuickSettingFace(kind, tile.spec.size, tile.label, cellSize)
}

/** The extras the user switched on in settings, as they change. */
private fun optionsFlow(context: Context): Flow<QuickOptions> =
    (context.applicationContext as? MetroApplication)?.settings?.settings
        ?.map { QuickOptions(it.showWifiName, it.showBluetoothDevices) }?.distinctUntilChanged()
        ?: emptyFlow()

/** The face of [kind], following the live state of the setting while composed. */
@Composable
fun QuickSettingFace(kind: QuickSettingKind, size: TileSize, label: String, cellSize: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The extras are the user's choice in settings; without the application (a preview) there are none.
    val options by remember { optionsFlow(context) }.collectAsStateWithLifecycle(QuickOptions())
    // The flow registers its receivers when collected (STARTED) and drops them when the tile leaves the screen.
    // A source the device can't provide must not take the tile (and the launcher) down: it falls back to a static face.
    val state by remember(kind, options) {
        QuickSettings.states(context, kind, options).catch { emit(QuickState(false, "unavailable")) }
    }.collectAsStateWithLifecycle(remember(kind, options) { QuickSettings.read(context, kind, options) })
    val m = rememberTileMetrics(cellSize)
    val white = Metro.colors.onAccent
    // The dimming is not enough for a screen reader: say what the tile is, what it does and its state.
    val stateText = state.text.ifEmpty { QuickSettingLogic.onOffText(state.on) }
    Box(
        modifier.fillMaxSize().tileSurface().semantics(mergeDescendants = true) {
            contentDescription = label
            stateDescription = stateText
            role = if (QuickSettingLogic.isToggle(kind)) Role.Switch else Role.Button
        },
    ) {
        // An off setting is the same tile under a dark veil.
        if (!state.on) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        if (size == TileSize.SMALL) {
            QuickSettingGlyph(kind, state, m.dp(38f), Modifier.align(Alignment.TopCenter).padding(top = m.dp(14f)))
            if (state.text.isNotEmpty()) {
                MetroText(
                    state.text,
                    MetroType.style(m.text(11f)),
                    color = white,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = m.dp(4f), vertical = m.dp(5f)),
                )
            }
            return@Box
        }
        val glyph = when (size) {
            TileSize.MEDIUM -> 52f
            TileSize.WIDE -> 60f
            else -> 96f
        }
        QuickSettingGlyph(kind, state, m.dp(glyph), Modifier.align(Alignment.Center))
        // As many lines as the tile has room for: the state alone on medium, the extras below it on bigger tiles.
        val maxLines = when (size) {
            TileSize.MEDIUM -> 1
            TileSize.WIDE -> 2
            else -> 3
        }
        Column(Modifier.padding(start = m.dp(8f), end = m.dp(8f), top = m.dp(6f))) {
            QuickSettingLogic.lines(state, maxLines).forEachIndexed { index, line ->
                MetroText(
                    line,
                    MetroType.style(m.text(if (size == TileSize.LARGE) 28f else 20f), FontWeight.Light),
                    color = if (index == 0) white else white.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
        }
        TileLabel(label, m, Modifier.align(Alignment.BottomStart))
    }
}
