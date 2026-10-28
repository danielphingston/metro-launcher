package io.github.danielphingston.metro.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import io.github.danielphingston.metro.ui.components.findActivity
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/** The one-time "Quick settings tiles" purchase: unlocked, a buy button with Play's price, or not on sale yet. */
@Composable
fun QuickTilesSettingsSection(settings: LauncherSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val purchases = (context.applicationContext as MetroApplication).purchases
    val unlocked by purchases.quickTilesUnlocked.collectAsState()
    val price by purchases.quickTilesPrice.collectAsState()

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
        // Android 12 and up ignore a request for precise location alone: it has to come with coarse.
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
        Manifest.permission.ACCESS_FINE_LOCATION,
        settings.showWifiName,
        { repository.setShowWifiName(it) },
        "android only shares the network name with apps allowed precise location",
    )
    PermissionToggle(
        "Show Bluetooth devices",
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
        Manifest.permission.BLUETOOTH_CONNECT,
        settings.showBluetoothDevices,
        { repository.setShowBluetoothDevices(it) },
        "android only shares device names with apps allowed nearby devices",
    )
}

/**
 * A toggle that needs runtime permissions to work. Turning it on asks for [request] here, the only
 * place it is asked for, and counts as granted only when [required] is among them; if refused it
 * stays off and [why] says what the permission is for. After a refusal the system stops showing
 * its dialog, so the toggle then offers the app info page, where the user can allow it.
 */
@Composable
private fun PermissionToggle(
    label: String,
    request: Array<String>,
    required: String,
    enabled: Boolean,
    save: suspend (Boolean) -> Unit,
    why: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Granted in system settings too, so look again whenever the screen resumes.
    val granted = rememberOnResume { context.checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED }
    var denied by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val ok = PermissionLogic.satisfied(results, required)
        denied = !ok
        permanentlyDenied = PermissionLogic.permanentlyDenied(
            denied = !ok,
            shouldShowRationale = context.findActivity()?.shouldShowRequestPermissionRationale(required) ?: true,
        )
        if (ok) scope.launch { save(true) }
    }
    val openAppInfo = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    MetroToggle(
        label,
        enabled && granted,
        { on ->
            if (!on) {
                denied = false
                permanentlyDenied = false
                scope.launch { save(false) }
            } else {
                when (PermissionLogic.step(granted, permanentlyDenied)) {
                    PermissionStep.SAVE -> scope.launch { save(true) }
                    PermissionStep.REQUEST -> launcher.launch(request)
                    PermissionStep.OPEN_APP_INFO -> openAppInfo()
                }
            }
        },
    )
    if (denied && !granted) MetroText(why, MetroType.caption, color = Metro.colors.subtle)
    if (permanentlyDenied && !granted) MetroButton("open app info", onClick = openAppInfo)
}
