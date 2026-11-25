package io.github.danielphingston.metro.ui.tiles

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.SizeF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.danielphingston.metro.ui.WidgetConfigActivity

/**
 * Creates a widget the way Android requires, for the widget picker and for setting a restored
 * widget up again: allocate an id, bind it (asking the user with the system's dialog when Metro
 * may not bind that provider yet), open the widget's own settings when it has required ones, then
 * hand the id over. Every way out that doesn't hand it over frees the id. One widget at a time;
 * main thread.
 */
internal class WidgetCreator(
    private val context: Context,
    private val bindLauncher: ActivityResultLauncher<Intent>,
    private val configureLauncher: ActivityResultLauncher<Intent>,
) {
    private class Pending(val id: Int, val info: AppWidgetProviderInfo, val onCreated: (Int) -> Unit, val onCancelled: () -> Unit)

    private var pending: Pending? = null

    /**
     * Creates a widget of [info] for [user], told [size] (dp) until its tile reports its own. Calls
     * [onCreated] with the new id once it is bound and set up, or [onCancelled] when the user says
     * no or the system can't.
     */
    fun create(info: AppWidgetProviderInfo, user: UserHandle, size: SizeF, onCreated: (Int) -> Unit, onCancelled: () -> Unit = {}) {
        cancel()
        val id = try {
            WidgetTiles.host(context).allocateAppWidgetId()
        } catch (_: RuntimeException) {
            return onCancelled() // The widget service is gone (rethrown as a RuntimeException).
        }
        pending = Pending(id, info, onCreated, onCancelled)
        val bound = try {
            AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, user, info.provider, WidgetTiles.sizeOptions(size))
        } catch (_: RuntimeException) {
            return cancel()
        }
        if (bound) {
            configureOrFinish()
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user)
            runCatching { bindLauncher.launch(intent) }.onFailure { cancel() }
        }
    }

    /** The system's bind dialog answered. */
    internal fun onBound(allowed: Boolean) = if (allowed) configureOrFinish() else cancel()

    /** The widget's own settings screen closed. */
    internal fun onConfigured(done: Boolean) = if (done) finish() else cancel()

    /** Frees the id of a widget being created, if any; nothing to do otherwise. */
    fun cancel() {
        val widget = pending ?: return
        pending = null
        WidgetTiles.release(context, widget.id)
        widget.onCancelled()
    }

    private fun configureOrFinish() {
        val widget = pending ?: return
        val optional = widget.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (widget.info.configure == null || optional) return finish()
        // The trampoline starts it through the host, which handles unexported activities and work profiles.
        val intent = Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.id)
        runCatching { configureLauncher.launch(intent) }.onFailure { cancel() }
    }

    private fun finish() {
        val widget = pending ?: return
        pending = null
        widget.onCreated(widget.id)
    }
}

/**
 * A [WidgetCreator] for this composition. Leaving it (Home or Back while the system's bind or
 * settings screen is up drops the caller) frees an id that was never handed over.
 */
@Composable
internal fun rememberWidgetCreator(): WidgetCreator {
    val context = LocalContext.current
    // The launchers need the creator and the creator needs the launchers: they meet through this.
    val holder = remember { arrayOfNulls<WidgetCreator>(1) }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        holder[0]?.onBound(it.resultCode == Activity.RESULT_OK)
    }
    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        holder[0]?.onConfigured(it.resultCode == Activity.RESULT_OK)
    }
    val creator = remember(bind, configure) { WidgetCreator(context, bind, configure) }
    holder[0] = creator
    DisposableEffect(creator) { onDispose(creator::cancel) }
    return creator
}
