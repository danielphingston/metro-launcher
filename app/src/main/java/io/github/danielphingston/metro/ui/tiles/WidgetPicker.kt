package io.github.danielphingston.metro.ui.tiles

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.util.SizeF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.WidgetConfigActivity
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** An installed widget provider, ready to list. */
private class WidgetEntry(
    val info: AppWidgetProviderInfo,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
    val cols: Int,
    val rows: Int,
)

private class WidgetGroup(val appLabel: String, val widgets: List<WidgetEntry>)

/** A widget id that is allocated but not yet bound, configured and pinned. */
private class PendingWidget(val id: Int, val entry: WidgetEntry)

private fun loadWidgets(context: Context): List<WidgetGroup> {
    val manager = AppWidgetManager.getInstance(context)
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val userManager = context.getSystemService(UserManager::class.java)
    val pm = context.packageManager
    val entries = launcherApps.profiles.flatMap { user ->
        val serial = userManager.getSerialNumberForUser(user)
        manager.getInstalledProvidersForProfile(user).map { info ->
            val (cols, rows) = WidgetTiles.cellsOf(context, info)
            val label = info.loadLabel(pm)?.trim().orEmpty().ifEmpty { info.provider.packageName }
            WidgetEntry(info, user, serial, label, cols, rows)
        }
    }
    return entries
        .groupBy { entry ->
            val app = entry.info.activityInfo.applicationInfo.loadLabel(pm).toString()
            pm.getUserBadgedLabel(app, entry.user).toString()
        }
        .map { (app, widgets) -> WidgetGroup(app, widgets.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appLabel })
}

/** Flyout panel to choose a widget and pin it as a tile. */
@Composable
fun WidgetPicker(viewModel: LauncherViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val host = remember { WidgetTiles.host(context) }
    val density = LocalDensity.current.density
    val groups by produceState<List<WidgetGroup>?>(null) {
        value = withContext(Dispatchers.IO) { loadWidgets(context) }
    }
    var pending by remember { mutableStateOf<PendingWidget?>(null) }

    fun cancel() {
        pending?.let { host.deleteAppWidgetId(it.id) }
        pending = null
    }

    fun pin(widget: PendingWidget) {
        val provider = widget.entry.info.provider.flattenToString()
        val size = WidgetTiles.suggestedSize(widget.entry.cols, widget.entry.rows)
        viewModel.addTile(TileContent.Widget(widget.id, provider, widget.entry.userSerial), size)
        pending = null
        onDismiss()
    }

    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val widget = pending ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) pin(widget) else cancel()
    }

    fun configureOrPin(widget: PendingWidget) {
        val info = widget.entry.info
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        val activity = info.configure
        if (activity == null || optional) return pin(widget)
        // The trampoline starts it through the host, which handles unexported activities and work profiles.
        val intent = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.id)
        runCatching { configure.launch(intent) }.onFailure { cancel() }
    }

    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val widget = pending ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) configureOrPin(widget) else cancel()
    }

    fun choose(entry: WidgetEntry) {
        val id = host.allocateAppWidgetId()
        val widget = PendingWidget(id, entry)
        pending = widget
        val provider = entry.info.provider
        val options = Bundle().apply {
            val size = SizeF(entry.info.minWidth / density, entry.info.minHeight / density)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.width.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, size.width.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.height.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.height.toInt())
        }
        if (AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, entry.user, provider, options)) {
            configureOrPin(widget)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, entry.user)
            runCatching { bind.launch(intent) }.onFailure { cancel() }
        }
    }

    BackHandler(onBack = onDismiss)
    MetroFlyout(onDismiss) {
        val list = groups
        LazyColumn(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp)
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                // Keeps taps on the panel from dismissing it.
                .clickable(enabled = false, onClick = {}),
            contentPadding = PaddingValues(
                top = 20.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                MetroText("widgets", MetroType.pageTitle, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            if (list != null) {
                list.forEach { group ->
                    item(key = "app:${group.appLabel}") {
                        MetroText(
                            group.appLabel,
                            MetroType.sectionTitle,
                            color = Metro.colors.accent,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                    items(group.widgets, key = { "${group.appLabel}:${it.userSerial}:${it.info.provider.flattenToString()}" }) { entry ->
                        WidgetRow(entry, onClick = { choose(entry) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(entry: WidgetEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, entry) {
        value = withContext(Dispatchers.IO) {
            val drawable: Drawable? = entry.info.loadPreviewImage(context, 0) ?: entry.info.loadIcon(context, 0)
            drawable?.let { runCatching { it.toPreviewBitmap().asImageBitmap() }.getOrNull() }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .metroTilt()
            .clickable(onClickLabel = "add ${entry.label}", onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 96.dp, height = 72.dp).background(Metro.colors.control), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)) }
        }
        Column(Modifier.weight(1f)) {
            MetroText(entry.label, MetroType.listItem, maxLines = 2)
            MetroText("${entry.cols} × ${entry.rows}", MetroType.caption, color = Metro.colors.subtle)
        }
    }
}

/** Renders a preview no larger than a list thumbnail, so big previews don't waste memory. */
private fun Drawable.toPreviewBitmap(maxPx: Int = 384): android.graphics.Bitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: maxPx
    val h = intrinsicHeight.takeIf { it > 0 } ?: maxPx
    val scale = min(1f, maxPx.toFloat() / max(w, h))
    return toBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
}
