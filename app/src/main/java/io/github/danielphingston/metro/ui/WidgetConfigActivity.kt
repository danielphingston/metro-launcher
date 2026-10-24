package io.github.danielphingston.metro.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import io.github.danielphingston.metro.ui.tiles.WidgetTiles

/**
 * Transparent trampoline that opens a widget's configuration activity and returns its result.
 *
 * Providers often declare the configuration activity unexported, and work-profile widgets must
 * be configured as their own user. Only the host can do that, through
 * [android.appwidget.AppWidgetHost.startAppWidgetConfigureActivityForResult], and its result
 * arrives as a legacy request code. This activity receives it and passes it on as an ordinary
 * activity result, so Compose's result launcher can use it.
 */
class WidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a recreation the configuration activity is already running (or finished).
        if (savedInstanceState != null) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finishCancelled()
        val started = try {
            WidgetTiles.host(this).startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE, null)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!started && !startPlain(id)) finishCancelled()
    }

    /** Fallback if the host call is refused: a plain intent to the configuration activity. */
    private fun startPlain(id: Int): Boolean {
        val component = WidgetTiles.providerInfo(this, id)?.configure ?: return false
        val configure = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(component)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return try {
            startActivityForResult(configure, REQUEST_CONFIGURE)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    @Deprecated("Receives the legacy request code used by AppWidgetHost.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        setResult(resultCode, data)
        finish()
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private companion object {
        const val REQUEST_CONFIGURE = 1
    }
}
