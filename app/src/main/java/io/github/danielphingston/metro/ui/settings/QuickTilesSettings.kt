package io.github.danielphingston.metro.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/** The one-time "Quick settings tiles" purchase: unlocked, a buy button with Play's price, or not on sale yet. */
@Composable
fun QuickTilesSettingsSection(settings: LauncherSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val purchases = (context.applicationContext as MetroApplication).purchases
    val unlocked by purchases.quickTilesUnlocked.collectAsState()
    val price by purchases.quickTilesPrice.collectAsState()

    Section("quick settings tiles")
    MetroText(
        "Tiles that toggle Wi-Fi, Bluetooth, flashlight and other quick settings from the start screen. " +
            "A one-time purchase, no subscription.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )
    Spacer(Modifier.height(12.dp))
    val activity = context.findActivity()
    when {
        unlocked -> MetroText("Unlocked", MetroType.body, color = Metro.colors.accent)
        price != null && activity != null ->
            MetroButton("buy for $price", onClick = { purchases.buyQuickTiles(activity) })
        else -> MetroText("Not available yet", MetroType.body, color = Metro.colors.subtle)
    }

    Spacer(Modifier.height(8.dp))
    PermissionToggle(
        "Show Wi-Fi network name",
        Manifest.permission.ACCESS_FINE_LOCATION,
        settings.showWifiName,
        { repository.setShowWifiName(it) },
        "android only shares the network name with apps allowed precise location",
    )
    PermissionToggle(
        "Show Bluetooth devices",
        Manifest.permission.BLUETOOTH_CONNECT,
        settings.showBluetoothDevices,
        { repository.setShowBluetoothDevices(it) },
        "android only shares device names with apps allowed nearby devices",
    )
}

/**
 * A toggle that needs the runtime [permission] to work. Turning it on asks for it here, the only
 * place it is asked for; if refused it stays off and [why] says what the permission is for.
 */
@Composable
private fun PermissionToggle(
    label: String,
    permission: String,
    enabled: Boolean,
    save: suspend (Boolean) -> Unit,
    why: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Granted in system settings too, so look again whenever the screen resumes.
    val granted = rememberOnResume { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) scope.launch { save(true) }
    }
    MetroToggle(
        label,
        enabled && granted,
        { on ->
            when {
                !on -> {
                    denied = false
                    scope.launch { save(false) }
                }
                granted -> scope.launch { save(true) }
                else -> request.launch(permission)
            }
        },
    )
    if (denied && !granted) MetroText(why, MetroType.caption, color = Metro.colors.subtle)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
