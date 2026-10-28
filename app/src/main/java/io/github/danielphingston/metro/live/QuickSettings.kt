package io.github.danielphingston.metro.live

import android.app.NotificationManager
import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.github.danielphingston.metro.data.QuickSettingKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * What a quick-setting tile shows. [on] picks the bright or dimmed face, [text] is the state line
 * (empty for none) and [level] is a kind-specific number the glyph can use: the ringer mode, the
 * [QuickSettingLogic.Brightness] ordinal or the battery percentage.
 */
data class QuickState(val on: Boolean, val text: String, val level: Int = 0, val info: QuickInfo = QuickInfo.None)

/**
 * What a tile can say beyond its state line, for the sizes with room. Unknown numbers are -1 (or 0
 * for the frequency) and their part is left out; [QuickSettingLogic.lines] turns this into text.
 */
sealed interface QuickInfo {
    data object None : QuickInfo

    /** [bars] is 0..4; [ssid] is only set when the user allowed it and android handed it over. */
    data class Wifi(val connected: Boolean, val bars: Int, val frequencyMhz: Int, val linkMbps: Int, val ssid: String?) : QuickInfo

    data class Mobile(val connected: Boolean, val carrier: String, val bars: Int, val roaming: Boolean) : QuickInfo

    /** Names of the connected devices; empty unless the user allowed it. */
    data class Bluetooth(val devices: List<String>) : QuickInfo

    data class Battery(val percent: Int, val charging: Boolean, val toFullMs: Long, val tempC: Float?) : QuickInfo
}

/** The optional extras the user switched on in settings; each also needs its runtime permission. */
data class QuickOptions(val wifiName: Boolean = false, val bluetoothDevices: Boolean = false)

/** The decisions behind the quick-setting tiles, free of system services so they can be tested. */
object QuickSettingLogic {
    private const val MAX_BRIGHTNESS = 255

    /** How a tap that has to open a screen starts it. */
    enum class LaunchStyle {
        /** The usual: the turnstile swings the start screen away and the app fades in. */
        TURNSTILE,

        /** A system panel that slides up over the start screen, which must stay exactly as it is. */
        OVER_START,

        /** A plain launch with no transition of ours, no turnstile either. */
        NO_ANIMATION,
    }

    /**
     * Wi-Fi and mobile data open bottom-sheet panels: flipping the tiles away first would leave
     * the panel over an empty screen. Battery lands through a trampoline inside Settings that our
     * own transition would only amplify. The hotspot tile opens the tethering page the same plain way. Other kinds, and the permission screens the toggles
     * fall back to, are full pages and keep the turnstile.
     */
    fun launchStyle(kind: QuickSettingKind): LaunchStyle = when (kind) {
        QuickSettingKind.WIFI, QuickSettingKind.MOBILE_DATA -> LaunchStyle.OVER_START
        QuickSettingKind.BATTERY, QuickSettingKind.HOTSPOT -> LaunchStyle.NO_ANIMATION
        else -> LaunchStyle.TURNSTILE
    }

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

    /** Whether [kind] is a plain on/off setting; the others cycle through steps or only open a screen. */
    fun isToggle(kind: QuickSettingKind): Boolean =
        kind != QuickSettingKind.BRIGHTNESS && kind != QuickSettingKind.BATTERY && kind != QuickSettingKind.HOTSPOT && kind != QuickSettingKind.RINGER

    private const val SEPARATOR = " · "

    /** One line per group, the group's parts joined; empty parts and groups drop out and extra lines are cut. */
    private fun layout(groups: List<List<String?>>, maxLines: Int): List<String> =
        groups.map { parts -> parts.filterNotNull().filter { it.isNotEmpty() }.joinToString(SEPARATOR) }
            .filter { it.isNotEmpty() }
            .take(maxLines)

    /**
     * The lines a tile of [maxLines] text lines shows: the state line first, then what [QuickState.info]
     * adds while there is room. A kind with nothing extra, or one line of room for little, reads as before.
     */
    fun lines(state: QuickState, maxLines: Int): List<String> {
        val extra = when (val info = state.info) {
            QuickInfo.None -> null
            is QuickInfo.Wifi -> wifiLines(state, maxLines)
            is QuickInfo.Mobile -> mobileLines(state, maxLines)
            is QuickInfo.Bluetooth -> bluetoothLines(state, maxLines)
            is QuickInfo.Battery -> batteryLines(state, maxLines)
        }
        return extra?.takeIf { it.isNotEmpty() } ?: listOf(state.text).filter { it.isNotEmpty() }
    }

    /** Signal strength as growing bars; [level] is 0..4 and anything else reads as no bars to show. */
    fun signalBars(level: Int): String? = when (level) {
        0 -> "▁"
        1 -> "▂"
        2 -> "▂▄"
        3 -> "▂▄▆"
        4 -> "▂▄▆█"
        else -> null
    }

    /** [level] out of [maxLevel] on the 0..4 scale of [signalBars]; -1 when there is no level. */
    fun bars(level: Int, maxLevel: Int): Int =
        if (level < 0 || maxLevel <= 0) -1 else (level * 4f / maxLevel).roundToInt().coerceIn(0, 4)

    /** The wi-fi band of a channel [mhz] wide apart, or null for an unknown frequency. */
    fun wifiBand(mhz: Int): String? = when (mhz) {
        in 2400..2500 -> "2.4 ghz"
        in 4900..5895 -> "5 ghz"
        in 5925..7125 -> "6 ghz"
        else -> null
    }

    /** The network name without its quotes, or null when android withheld it ("<unknown ssid>") or there is none. */
    fun cleanSsid(raw: String?): String? {
        val name = raw?.removeSurrounding("\"")?.trim()
        return name?.takeIf { it.isNotEmpty() && !it.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true) }
    }

    fun wifiLines(state: QuickState, maxLines: Int): List<String> {
        val info = state.info as? QuickInfo.Wifi ?: return emptyList()
        if (!state.on || !info.connected) return listOf(state.text)
        val name = info.ssid ?: state.text
        val bars = signalBars(info.bars)
        val band = wifiBand(info.frequencyMhz)
        val speed = info.linkMbps.takeIf { it > 0 }?.let { "$it mbps" }
        return if (maxLines >= 3) layout(listOf(listOf(name), listOf(bars, band), listOf(speed)), maxLines)
        else layout(listOf(listOf(name, bars), listOf(band, speed)), maxLines)
    }

    fun mobileLines(state: QuickState, maxLines: Int): List<String> {
        val info = state.info as? QuickInfo.Mobile ?: return emptyList()
        if (!state.on) return layout(listOf(listOf(state.text), listOf(info.carrier)), maxLines)
        val carrier = info.carrier.takeIf { it.isNotEmpty() }
        val bars = signalBars(info.bars)
        val roaming = if (info.roaming) "roaming" else null
        return if (carrier == null) layout(listOf(listOf(state.text, bars), listOf(roaming)), maxLines)
        else layout(listOf(listOf(carrier, bars), listOf(state.text, roaming)), maxLines)
    }

    /** Off or no devices reads as the state line; otherwise a device per line, the ones that don't fit counted. */
    fun bluetoothLines(state: QuickState, maxLines: Int): List<String> {
        val devices = (state.info as? QuickInfo.Bluetooth)?.devices.orEmpty()
        if (!state.on || devices.isEmpty()) return listOf(state.text)
        if (devices.size <= maxLines) return devices
        return if (maxLines == 1) {
            listOf("${devices.first()} +${devices.size - 1}")
        } else {
            devices.take(maxLines - 1) + "+${devices.size - maxLines + 1} more"
        }
    }

    fun batteryLines(state: QuickState, maxLines: Int): List<String> {
        val info = state.info as? QuickInfo.Battery ?: return emptyList()
        if (info.percent < 0) return emptyList()
        val percent = "${info.percent}%"
        val toFull = if (info.charging) chargeTimeText(info.toFullMs) else null
        val temp = info.tempC?.let(::temperatureText)
        return when {
            maxLines >= 3 -> layout(listOf(listOf(percent, if (info.charging) "charging" else "discharging"), listOf(toFull), listOf(temp)), maxLines)
            maxLines == 2 -> layout(listOf(listOf(percent, if (info.charging) "charging" else "discharging"), listOf(toFull, temp)), maxLines)
            else -> listOf(batteryText(info.percent, info.charging))
        }
    }

    /**
     * Whether mobile data is on: the default data subscription's own answer when there is one,
     * else the global setting, which on a dual-SIM phone describes only one of the subscriptions.
     */
    fun dataEnabled(perSubscription: Boolean?, global: Boolean): Boolean = perSubscription ?: global

    /** "1 h 20 min to full" from the milliseconds android estimates; null when it can't tell (negative) or is done (0). */
    fun chargeTimeText(ms: Long): String? {
        if (ms <= 0) return null
        val minutes = ((ms + 59_999) / 60_000).toInt()
        val hours = minutes / 60
        val rest = minutes % 60
        val span = when {
            hours == 0 -> "$rest min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
        return "$span to full"
    }

    /** The battery's temperature in degrees from the broadcast's tenths; null when missing or not credible. */
    fun batteryTempC(tenths: Int?): Float? = tenths?.let { it / 10f }?.takeIf { it in -40f..120f }

    fun temperatureText(celsius: Float): String = String.format(Locale.ROOT, "%.1f°c", celsius)
}

/**
 * The flashlight: its camera, and the state its callback last reported. The state is only kept
 * fresh while a flashlight tile is collected ([track] and [untrack]); with none, it reads as off
 * instead of going stale.
 */
internal object Torch {
    @Volatile
    var on = false

    private val lock = Any()
    private var resolved = false
    private var id: String? = null
    private var trackers = 0

    /** The first back camera with a flash, or null on a device without one. */
    fun cameraId(context: Context): String? = synchronized(lock) {
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
        id
    }

    /** A flashlight tile starts listening; [untrack] must follow when it stops. */
    fun track() = synchronized(lock) { trackers++ }

    /** A tile stopped listening; with none left the remembered state is no longer kept up to date, so it is dropped. */
    fun untrack() = synchronized(lock) {
        if (--trackers <= 0) {
            trackers = 0
            on = false
        }
    }

    /** Switches the torch; the camera callback then reports the new state. Returns false if the camera refused. */
    fun set(context: Context, on: Boolean): Boolean {
        val camera = cameraId(context) ?: return false
        return runCatching { context.getSystemService(CameraManager::class.java).setTorchMode(camera, on) }.isSuccess
    }
}

/** What the callbacks of a [QuickSettings.states] flow last reported, for the reads they trigger. */
internal class LiveSources {
    @Volatile var wifi: WifiInfo? = null

    /** 0..4, or -1 while unknown. */
    @Volatile var signalLevel = -1

    @Volatile var devices: List<String> = emptyList()
}

/** Reads and observes the system state behind the quick-setting tiles. */
object QuickSettings {
    /** The state of [kind] right now. A service that refuses to answer reads as an off, unavailable setting. */
    fun read(context: Context, kind: QuickSettingKind, options: QuickOptions = QuickOptions()): QuickState =
        read(context, kind, options, LiveSources())

    private fun read(context: Context, kind: QuickSettingKind, options: QuickOptions, live: LiveSources): QuickState = try {
        readState(context, kind, options, live)
    } catch (_: RuntimeException) {
        QuickState(false, "unavailable")
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Android shows the network name only to apps with precise location, so both the option and the permission are needed. */
    private fun wifiNameAllowed(context: Context, options: QuickOptions) =
        options.wifiName && granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun bluetoothDevicesAllowed(context: Context, options: QuickOptions) =
        options.bluetoothDevices && granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    private fun readState(context: Context, kind: QuickSettingKind, options: QuickOptions, live: LiveSources): QuickState {
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
                val connected = if (enabled) onTransport(context, NetworkCapabilities.TRANSPORT_WIFI) else false
                val info = if (connected == true) wifiDetails(context, options, live) else QuickInfo.Wifi(false, -1, 0, -1, null)
                QuickState(enabled, QuickSettingLogic.networkText(enabled, connected), info = info)
            }
            QuickSettingKind.MOBILE_DATA -> {
                val enabled = QuickSettingLogic.dataEnabled(
                    dataEnabledForDefaultSubscription(context),
                    Settings.Global.getInt(resolver, MOBILE_DATA, 1) != 0,
                )
                val connected = if (enabled) onTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR) else false
                QuickState(enabled, QuickSettingLogic.networkText(enabled, connected), info = mobileDetails(context, connected == true, live))
            }
            QuickSettingKind.BLUETOOTH -> {
                val on = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
                val devices = if (on && bluetoothDevicesAllowed(context, options)) live.devices else emptyList()
                QuickState(on, QuickSettingLogic.onOffText(on), info = QuickInfo.Bluetooth(devices))
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
                val toFull = if (charging) context.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining() else -1L
                val tenths = intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val info = QuickInfo.Battery(percent, charging, toFull, QuickSettingLogic.batteryTempC(tenths))
                QuickState(true, QuickSettingLogic.batteryText(percent, charging), percent, info)
            }
        }
    }

    // Settings.Global.WIFI_ON and MOBILE_DATA are hidden constants; the keys themselves are stable.
    // WifiInfo.INVALID_RSSI is hidden too.
    private const val INVALID_RSSI = -127
    private const val WIFI_ON = "wifi_on"
    private const val MOBILE_DATA = "mobile_data"

    /**
     * Mobile data for the default data subscription, null when it can't be asked (no READ_PHONE_STATE,
     * no telephony, no default subscription): the global setting is then the answer.
     */
    private fun dataEnabledForDefaultSubscription(context: Context): Boolean? {
        // The per-subscription key is readable by anyone; TelephonyManager.isDataEnabled would need
        // READ_PHONE_STATE, which a launcher shouldn't ask for just to show this tile.
        val subscription = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        return Settings.Global.getString(context.contentResolver, "$MOBILE_DATA$subscription")
            ?.toIntOrNull()?.let { it != 0 }
    }

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

    /** Signal, band and speed of the wi-fi connection, and its name when [wifiNameAllowed]. */
    private fun wifiDetails(context: Context, options: QuickOptions, live: LiveSources): QuickInfo.Wifi {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val info = live.wifi ?: connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)?.transportInfo as? WifiInfo
            ?: return QuickInfo.Wifi(true, -1, 0, -1, null)
        val wifi = context.getSystemService(WifiManager::class.java)
        val bars = if (info.rssi == INVALID_RSSI) -1 else QuickSettingLogic.bars(wifi.calculateSignalLevel(info.rssi), wifi.maxSignalLevel)
        val ssid = if (wifiNameAllowed(context, options)) QuickSettingLogic.cleanSsid(info.ssid) else null
        return QuickInfo.Wifi(true, bars, info.frequency, info.linkSpeed, ssid)
    }

    /** Carrier, signal and roaming; none of it needs a permission. */
    private fun mobileDetails(context: Context, connected: Boolean, live: LiveSources): QuickInfo.Mobile {
        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?: return QuickInfo.Mobile(connected, "", -1, false)
        val level = live.signalLevel.takeIf { it >= 0 }
            ?: runCatching { telephony.signalStrength?.level }.getOrNull() ?: -1
        return QuickInfo.Mobile(connected, telephony.networkOperatorName.orEmpty(), level, telephony.isNetworkRoaming)
    }

    /**
     * The state of [kind], again after every change the system reports. Receivers and observers
     * exist only while this is collected, so collect it only while the tile is on screen.
     */
    fun states(context: Context, kind: QuickSettingKind, options: QuickOptions = QuickOptions()): Flow<QuickState> = flow {
        val app = context.applicationContext
        val live = LiveSources()
        emitAll(changes(app, kind, options, live).map { read(app, kind, options, live) })
    }.flowOn(Dispatchers.Default).distinctUntilChanged() // the reads are binder calls: keep them off the main thread

    /**
     * A signal for every change the system reports for [kind], conflated so a burst becomes one read.
     * Each registration is on its own: one the system refuses (no telephony on a Wi-Fi-only tablet,
     * too many network callbacks) loses only that source, and whatever did register is always undone.
     * The callbacks still deliver on the main looper and only nudge the channel.
     */
    private fun changes(app: Context, kind: QuickSettingKind, options: QuickOptions, live: LiveSources): Flow<Unit> = callbackFlow {
        val cleanups = mutableListOf<() -> Unit>()
        val changed = { trySend(Unit); Unit }
        try {
            register(app, kind, options, live, cleanups, changed)
            changed()
            awaitClose()
        } finally {
            cleanups.forEach { runCatching(it) }
        }
    }.conflate()

    private fun register(
        app: Context,
        kind: QuickSettingKind,
        options: QuickOptions,
        live: LiveSources,
        cleanups: MutableList<() -> Unit>,
        changed: () -> Unit,
    ) {
        when (kind) {
            QuickSettingKind.FLASHLIGHT -> Torch.cameraId(app)?.let { camera ->
                cleanups += guarded {
                    val manager = app.getSystemService(CameraManager::class.java)
                    val callback = object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                            if (cameraId != camera) return
                            Torch.on = enabled
                            changed()
                        }
                    }
                    Torch.track()
                    // Reports the current state straight away.
                    try {
                        manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                    } catch (e: RuntimeException) {
                        Torch.untrack()
                        throw e
                    }
                    val stop: () -> Unit = {
                        manager.unregisterTorchCallback(callback)
                        Torch.untrack()
                    }
                    stop
                }
            }
            QuickSettingKind.DO_NOT_DISTURB ->
                cleanups += guarded { broadcasts(app, listOf(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), changed) }
            QuickSettingKind.RINGER -> cleanups += guarded { broadcasts(app, listOf(AudioManager.RINGER_MODE_CHANGED_ACTION), changed) }
            QuickSettingKind.AUTO_ROTATE ->
                cleanups += guarded { observe(app, listOf(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)), changed) }
            QuickSettingKind.BRIGHTNESS -> cleanups += guarded {
                observe(
                    app,
                    listOf(
                        Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                        Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    ),
                    changed,
                )
            }
            QuickSettingKind.WIFI -> {
                cleanups += guarded { broadcasts(app, listOf(WifiManager.WIFI_STATE_CHANGED_ACTION, CONNECTIVITY_CHANGED), changed) }
                cleanups += guarded { watchWifi(app, wifiNameAllowed(app, options), live, changed) }
            }
            QuickSettingKind.MOBILE_DATA -> {
                cleanups += guarded { broadcasts(app, listOf(CONNECTIVITY_CHANGED), changed) }
                // On dual-SIM phones the data switch of each subscription lives under its own key.
                cleanups += guarded {
                    val subscription = SubscriptionManager.getDefaultDataSubscriptionId()
                    val uris = listOfNotNull(
                        Settings.Global.getUriFor(MOBILE_DATA),
                        Settings.Global.getUriFor("$MOBILE_DATA$subscription")
                            .takeIf { subscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID },
                    )
                    observe(app, uris, changed)
                }
                cleanups += guarded { watchSignal(app, live, changed) }
            }
            QuickSettingKind.BLUETOOTH -> {
                cleanups += guarded { broadcasts(app, listOf(BluetoothAdapter.ACTION_STATE_CHANGED), changed) }
                if (bluetoothDevicesAllowed(app, options)) cleanups += guarded { watchBluetoothDevices(app, live, changed) }
            }
            QuickSettingKind.AIRPLANE -> cleanups += guarded { broadcasts(app, listOf(Intent.ACTION_AIRPLANE_MODE_CHANGED), changed) }
            QuickSettingKind.LOCATION -> cleanups += guarded { broadcasts(app, listOf(LocationManager.MODE_CHANGED_ACTION), changed) }
            QuickSettingKind.HOTSPOT -> Unit
            QuickSettingKind.BATTERY -> cleanups += guarded { broadcasts(app, listOf(Intent.ACTION_BATTERY_CHANGED), changed) }
        }
    }

    private const val FLAG_INCLUDE_LOCATION_INFO = ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
    private val NO_CLEANUP: () -> Unit = {}

    /** Runs a registration; if the system refuses it (any runtime exception) that source is simply missing, with nothing to undo. */
    private inline fun guarded(register: () -> () -> Unit): () -> Unit = try {
        register()
    } catch (_: RuntimeException) {
        NO_CLEANUP
    }

    /**
     * Follows the default network's wi-fi details, signal changes included. The flag makes android
     * put the network name into them, which it does only for an app holding precise location.
     */
    private fun watchWifi(context: Context, withName: Boolean, live: LiveSources, onEvent: () -> Unit): () -> Unit {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback(if (withName) FLAG_INCLUDE_LOCATION_INFO else 0) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                live.wifi = capabilities.transportInfo as? WifiInfo
                onEvent()
            }

            override fun onLost(network: Network) {
                live.wifi = null
                onEvent()
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            val stop: () -> Unit = { manager.unregisterNetworkCallback(callback) }
            stop
        } catch (_: RuntimeException) {
            NO_CLEANUP
        }
    }

    /** Follows the mobile signal strength; android lets any app listen for it. */
    private fun watchSignal(context: Context, live: LiveSources, onEvent: () -> Unit): () -> Unit {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return NO_CLEANUP
        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                live.signalLevel = signalStrength.level
                onEvent()
            }
        }
        return try {
            telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback)
            val stop: () -> Unit = { telephony.unregisterTelephonyCallback(callback) }
            stop
        } catch (_: RuntimeException) {
            // No telephony stack (Wi-Fi-only tablets) or no permission: the signal just stays unknown.
            NO_CLEANUP
        }
    }

    /**
     * Follows the connected audio and headset devices. A profile proxy answers "who is connected
     * now" for each profile, and the connection broadcasts say when to ask again. Only for an app
     * holding the nearby devices permission, so every call is guarded against it being revoked.
     */
    private fun watchBluetoothDevices(context: Context, live: LiveSources, onEvent: () -> Unit): () -> Unit {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return NO_CLEANUP
        val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
        val refresh = {
            live.devices = try {
                proxies.values.flatMap { it.connectedDevices }.distinctBy { it.address }.mapNotNull(::deviceName)
            } catch (_: SecurityException) {
                emptyList()
            }
            onEvent()
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy
                refresh()
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile)
                refresh()
            }
        }
        val stopBroadcasts = try {
            listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { adapter.getProfileProxy(context, listener, it) }
            broadcasts(
                context,
                listOf(
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                ),
                refresh,
            )
        } catch (_: RuntimeException) {
            NO_CLEANUP
        }
        return {
            stopBroadcasts()
            proxies.forEach { (profile, proxy) -> runCatching { adapter.closeProfileProxy(profile, proxy) } }
        }
    }

    /** The name the user sees for [device], or null without one. */
    private fun deviceName(device: BluetoothDevice): String? = try {
        (device.alias ?: device.name)?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

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
