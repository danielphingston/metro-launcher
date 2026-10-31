package io.github.danielphingston.metro.live

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import kotlin.math.roundToInt

/**
 * What a quick-setting tile shows. [on] picks the bright or dimmed face, [text] is the state line
 * (empty for none) and [level] is a kind-specific number the glyph can use: the ringer mode, the
 * [QuickSettingLogic.Brightness] ordinal or the battery percentage.
 */
data class QuickState(val on: Boolean, val text: String, val level: Int = 0, val info: QuickInfo = QuickInfo.None)

/**
 * What a tile can say beyond its state line, for the sizes with room. Unknown numbers are -1 and
 * their part is left out; [QuickSettingLogic.lines] turns this into text.
 */
sealed interface QuickInfo {
    data object None : QuickInfo

    data class Battery(val percent: Int, val charging: Boolean, val toFullMs: Long, val tempC: Float?) : QuickInfo
}

/** The decisions behind the quick-setting tiles, free of system services so they can be tested. */
object QuickSettingLogic {
    private const val MAX_BRIGHTNESS = 255

    /** How a tap that has to open a screen starts it. */
    enum class LaunchStyle {
        /** The usual: the turnstile swings the start screen away and the app fades in. */
        TURNSTILE,

        /** A plain launch with no transition of ours, no turnstile either. */
        NO_ANIMATION,
    }

    /**
     * Battery lands through a trampoline inside Settings that our own transition would only amplify.
     * Other kinds, and the permission screens the toggles fall back to, are full pages and keep the turnstile.
     */
    fun launchStyle(kind: QuickSettingKind): LaunchStyle = when (kind) {
        QuickSettingKind.BATTERY -> LaunchStyle.NO_ANIMATION
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

    fun onOffText(on: Boolean): String = if (on) "on" else "off"

    /** Whether [kind] is a plain on/off setting; the others cycle through steps or only open a screen. */
    fun isToggle(kind: QuickSettingKind): Boolean =
        kind != QuickSettingKind.BRIGHTNESS && kind != QuickSettingKind.BATTERY && kind != QuickSettingKind.RINGER

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
            is QuickInfo.Battery -> batteryLines(state, maxLines)
        }
        return extra?.takeIf { it.isNotEmpty() } ?: listOf(state.text).filter { it.isNotEmpty() }
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

    /**
     * The state of [kind], again after every change the system reports. Receivers and observers
     * exist only while this is collected, so collect it only while the tile is on screen.
     */
    fun states(context: Context, kind: QuickSettingKind): Flow<QuickState> = flow {
        val app = context.applicationContext
        emitAll(changes(app, kind).map { read(app, kind) })
    }.flowOn(Dispatchers.Default).distinctUntilChanged() // the reads are binder calls: keep them off the main thread

    /**
     * A signal for every change the system reports for [kind], conflated so a burst becomes one read.
     * Each registration is on its own: one the system refuses loses only that source, and whatever
     * did register is always undone. The callbacks still deliver on the main looper and only nudge the channel.
     */
    private fun changes(app: Context, kind: QuickSettingKind): Flow<Unit> = callbackFlow {
        val cleanups = mutableListOf<() -> Unit>()
        val changed = { trySend(Unit); Unit }
        try {
            register(app, kind, cleanups, changed)
            changed()
            awaitClose()
        } finally {
            cleanups.forEach { runCatching(it) }
        }
    }.conflate()

    private fun register(
        app: Context,
        kind: QuickSettingKind,
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
            QuickSettingKind.BATTERY -> cleanups += guarded { broadcasts(app, listOf(Intent.ACTION_BATTERY_CHANGED), changed) }
        }
    }

    private val NO_CLEANUP: () -> Unit = {}

    /** Runs a registration; if the system refuses it (any runtime exception) that source is simply missing, with nothing to undo. */
    private inline fun guarded(register: () -> () -> Unit): () -> Unit = try {
        register()
    } catch (_: RuntimeException) {
        NO_CLEANUP
    }

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
