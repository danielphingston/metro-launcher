package io.github.danielphingston.metro.live

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.danielphingston.metro.data.QuickSettingKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * What a quick-setting tile shows. [on] picks the bright or dimmed face, [text] is the state line
 * (empty for none) and [level] is a kind-specific number the glyph can use: the ringer mode, the
 * [QuickSettingLogic.Brightness] ordinal or the battery percentage.
 */
data class QuickState(val on: Boolean, val text: String, val level: Int = 0)

/** The decisions behind the quick-setting tiles, free of system services so they can be tested. */
object QuickSettingLogic {
    private const val MAX_BRIGHTNESS = 255

    /** The brightness tile's steps, in the order a tap goes through them. */
    enum class Brightness(val label: String, val value: Int?) {
        AUTO("auto", null),
        QUARTER("25%", 64),
        HALF("50%", 128),
        FULL("100%", MAX_BRIGHTNESS);

        fun next(): Brightness = entries[(ordinal + 1) % entries.size]

        companion object {
            /** The step a stored setting is closest to; adaptive brightness is always [AUTO]. */
            fun of(auto: Boolean, value: Int): Brightness {
                if (auto) return AUTO
                val share = value / MAX_BRIGHTNESS.toFloat()
                return when {
                    share <= 0.375f -> QUARTER
                    share <= 0.75f -> HALF
                    else -> FULL
                }
            }
        }
    }

    /** Ringing, then vibrating, then silent, then ringing again. */
    fun nextRinger(mode: Int): Int = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
        AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
        else -> AudioManager.RINGER_MODE_NORMAL
    }

    fun ringerText(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "ring"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "silent"
    }

    /** The charge in percent, or -1 when the battery broadcast has no usable level. */
    fun batteryPercent(level: Int, scale: Int): Int =
        if (level < 0 || scale <= 0) -1 else (level * 100f / scale).roundToInt().coerceIn(0, 100)

    fun batteryText(percent: Int, charging: Boolean): String = when {
        percent < 0 -> ""
        charging -> "$percent% · charging"
        else -> "$percent%"
    }

    /** [connected] is null when the network state can't be read. */
    fun networkText(enabled: Boolean, connected: Boolean?): String = when {
        !enabled -> "off"
        connected == true -> "connected"
        else -> "on"
    }

    fun onOffText(on: Boolean): String = if (on) "on" else "off"
}

/** The flashlight: its camera, and the state its callback last reported. */
internal object Torch {
    @Volatile
    var on = false

    private var resolved = false
    private var id: String? = null

    /** The first back camera with a flash, or null on a device without one. */
    fun cameraId(context: Context): String? {
        if (!resolved) {
            id = runCatching {
                val manager = context.getSystemService(CameraManager::class.java)
                manager.cameraIdList.firstOrNull { camera ->
                    val characteristics = manager.getCameraCharacteristics(camera)
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            }.getOrNull()
            resolved = true
        }
        return id
    }

    /** Switches the torch; the camera callback then reports the new state. Returns false if the camera refused. */
    fun set(context: Context, on: Boolean): Boolean {
        val camera = cameraId(context) ?: return false
        return runCatching { context.getSystemService(CameraManager::class.java).setTorchMode(camera, on) }.isSuccess
    }
}

/** Reads and observes the system state behind the quick-setting tiles. */
object QuickSettings {
    /** The state of [kind] right now. A service that refuses to answer reads as an off, unavailable setting. */
    fun read(context: Context, kind: QuickSettingKind): QuickState = try {
        readState(context, kind)
    } catch (_: RuntimeException) {
        QuickState(false, "unavailable")
    }

    private fun readState(context: Context, kind: QuickSettingKind): QuickState {
        val resolver = context.contentResolver
        return when (kind) {
            QuickSettingKind.FLASHLIGHT -> when {
                Torch.cameraId(context) == null -> QuickState(false, "unavailable")
                else -> Torch.on.let { QuickState(it, QuickSettingLogic.onOffText(it)) }
            }
            QuickSettingKind.DO_NOT_DISTURB -> {
                val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
                val on = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                QuickState(on, QuickSettingLogic.onOffText(on))
            }
            QuickSettingKind.RINGER -> {
                val mode = context.getSystemService(AudioManager::class.java).ringerMode
                QuickState(mode == AudioManager.RINGER_MODE_NORMAL, QuickSettingLogic.ringerText(mode), mode)
            }
            QuickSettingKind.AUTO_ROTATE -> {
                val on = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                QuickState(on, QuickSettingLogic.onOffText(on))
            }
            QuickSettingKind.BRIGHTNESS -> {
                val auto = Settings.System.getInt(
                    resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                val value = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                val step = QuickSettingLogic.Brightness.of(auto, value)
                QuickState(true, step.label, step.ordinal)
            }
            QuickSettingKind.WIFI -> {
                val enabled = wifiEnabled(context)
                QuickState(enabled, QuickSettingLogic.networkText(enabled, if (enabled) onTransport(context, NetworkCapabilities.TRANSPORT_WIFI) else false))
            }
            QuickSettingKind.MOBILE_DATA -> {
                // The setting is readable by anyone; TelephonyManager.isDataEnabled needs READ_PHONE_STATE.
                val enabled = Settings.Global.getInt(resolver, MOBILE_DATA, 1) != 0
                QuickState(enabled, QuickSettingLogic.networkText(enabled, if (enabled) onTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR) else false))
            }
            QuickSettingKind.BLUETOOTH -> {
                val on = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
                QuickState(on, QuickSettingLogic.onOffText(on))
            }
            QuickSettingKind.AIRPLANE -> {
                val on = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                QuickState(on, QuickSettingLogic.onOffText(on))
            }
            QuickSettingKind.LOCATION -> {
                val on = context.getSystemService(LocationManager::class.java).isLocationEnabled
                QuickState(on, QuickSettingLogic.onOffText(on))
            }
            QuickSettingKind.HOTSPOT -> QuickState(true, "")
            QuickSettingKind.BATTERY -> {
                // A sticky broadcast: registering without a receiver just returns the last one.
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val percent = QuickSettingLogic.batteryPercent(
                    intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
                    intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
                )
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                QuickState(true, QuickSettingLogic.batteryText(percent, charging), percent)
            }
        }
    }

    // Settings.Global.WIFI_ON and MOBILE_DATA are hidden constants; the keys themselves are stable.
    private const val WIFI_ON = "wifi_on"
    private const val MOBILE_DATA = "mobile_data"

    private fun wifiEnabled(context: Context): Boolean = try {
        context.getSystemService(WifiManager::class.java).isWifiEnabled
    } catch (_: SecurityException) {
        Settings.Global.getInt(context.contentResolver, WIFI_ON, 0) != 0
    }

    /** Whether the default network uses [transport]; null without network-state access, which the manifest may not declare. */
    private fun onTransport(context: Context, transport: Int): Boolean? = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.activeNetwork?.let(manager::getNetworkCapabilities)?.hasTransport(transport) == true
    } catch (_: SecurityException) {
        null
    }

    /**
     * The state of [kind], again after every change the system reports. Receivers and observers
     * exist only while this is collected, so collect it only while the tile is on screen.
     */
    fun states(context: Context, kind: QuickSettingKind): Flow<QuickState> = callbackFlow {
        val app = context.applicationContext
        val cleanups = mutableListOf<() -> Unit>()
        val changed = { trySend(read(app, kind)); Unit }
        when (kind) {
            QuickSettingKind.FLASHLIGHT -> Torch.cameraId(app)?.let { camera ->
                val manager = app.getSystemService(CameraManager::class.java)
                val callback = object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId != camera) return
                        Torch.on = enabled
                        changed()
                    }
                }
                // Reports the current state straight away.
                manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                cleanups += { manager.unregisterTorchCallback(callback) }
            }
            QuickSettingKind.DO_NOT_DISTURB ->
                cleanups += broadcasts(app, listOf(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), changed)
            QuickSettingKind.RINGER -> cleanups += broadcasts(app, listOf(AudioManager.RINGER_MODE_CHANGED_ACTION), changed)
            QuickSettingKind.AUTO_ROTATE ->
                cleanups += observe(app, listOf(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)), changed)
            QuickSettingKind.BRIGHTNESS -> cleanups += observe(
                app,
                listOf(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                ),
                changed,
            )
            QuickSettingKind.WIFI ->
                cleanups += broadcasts(app, listOf(WifiManager.WIFI_STATE_CHANGED_ACTION, CONNECTIVITY_CHANGED), changed)
            QuickSettingKind.MOBILE_DATA -> {
                cleanups += broadcasts(app, listOf(CONNECTIVITY_CHANGED), changed)
                cleanups += observe(app, listOf(Settings.Global.getUriFor(MOBILE_DATA)), changed)
            }
            QuickSettingKind.BLUETOOTH -> cleanups += broadcasts(app, listOf(BluetoothAdapter.ACTION_STATE_CHANGED), changed)
            QuickSettingKind.AIRPLANE -> cleanups += broadcasts(app, listOf(Intent.ACTION_AIRPLANE_MODE_CHANGED), changed)
            QuickSettingKind.LOCATION -> cleanups += broadcasts(app, listOf(LocationManager.MODE_CHANGED_ACTION), changed)
            QuickSettingKind.HOTSPOT -> Unit
            QuickSettingKind.BATTERY -> cleanups += broadcasts(app, listOf(Intent.ACTION_BATTERY_CHANGED), changed)
        }
        changed()
        awaitClose { cleanups.forEach { it() } }
    }.conflate().distinctUntilChanged()

    // ConnectivityManager.CONNECTIVITY_ACTION is deprecated but still delivered to receivers registered at runtime,
    // and unlike a network callback it needs no permission to listen for.
    @Suppress("DEPRECATION")
    private const val CONNECTIVITY_CHANGED = ConnectivityManager.CONNECTIVITY_ACTION

    /** Registers for system broadcasts [actions]; returns what unregisters again. */
    private fun broadcasts(context: Context, actions: List<String>, onEvent: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onEvent()
        }
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        // System broadcasts reach a not-exported receiver; the flag is required on Android 14 and up.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return { context.unregisterReceiver(receiver) }
    }

    /** Watches settings [uris]; returns what stops watching. */
    private fun observe(context: Context, uris: List<Uri>, onEvent: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onEvent()
        }
        uris.forEach { context.contentResolver.registerContentObserver(it, false, observer) }
        return { context.contentResolver.unregisterContentObserver(observer) }
    }
}
