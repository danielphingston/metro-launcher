package io.github.danielphingston.metro.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.tiles.WidgetTiles

/** Handles an app asking to pin one of its widgets to the start screen. */
class PinWidgetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = try {
            getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        val info = request?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET && it.isValid }
            ?.getAppWidgetProviderInfo(this)
        if (request == null || info == null) {
            finish()
            return
        }
        val metro = application as MetroApplication
        val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            .ifEmpty { info.provider.packageName }

        fun add(columns: Int) {
            val host = WidgetTiles.host(this)
            val id = try {
                host.allocateAppWidgetId()
            } catch (_: RuntimeException) {
                return finish() // The widget service is gone (rethrown as a RuntimeException).
            }
            val accepted = try {
                request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })
            } catch (_: RuntimeException) {
                false
            }
            if (accepted) {
                val cell = WidgetTiles.cellDp(resources.configuration.screenWidthDp, columns)
                val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(info.profile)
                val span = WidgetTiles.suggestedSpan(WidgetTiles.dimsOf(this, info), cell, columns)
                metro.tiles.add(
                    TileContent.Widget(id, info.provider.flattenToString(), serial),
                    WidgetTiles.nearestSize(span),
                    span,
                )
                setResult(Activity.RESULT_OK)
            } else {
                WidgetTiles.release(this, id)
            }
            finish()
        }

        setContent {
            val settings by metro.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MetroTheme(settings) {
                Box(
                    Modifier.fillMaxSize().clickable(onClickLabel = "cancel") { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Metro.colors.chrome)
                            // Keeps taps on the dialog itself from cancelling it.
                            .clickable(enabled = false, onClick = {})
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetroText("add to start?", MetroType.sectionTitle)
                        MetroText(label, MetroType.body, color = Metro.colors.subtle, maxLines = 2)
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetroButton("add", onClick = { add(settings.columns) }, modifier = Modifier.weight(1f))
                            MetroButton("cancel", onClick = { finish() }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
