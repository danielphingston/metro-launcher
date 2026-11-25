package io.github.danielphingston.metro.ui.tiles

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.UserHandle
import android.os.UserManager
import android.util.SizeF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.theme.tileSurface
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A widget tile whose widget is gone: restored from a backup made on another phone or by another
 * app, or the widget's app was removed. The tile still knows exactly which widget it was (its
 * provider) and where and how big it was, so its widget can be created again in place; the
 * widget's own settings were kept by that app and are not in any backup, so it may ask for them.
 * Nothing is offered in place of a widget that no longer exists: then the tile can only be removed.
 */
internal object WidgetRestore {
    /** What a tap on the tile offers. */
    enum class Offer {
        /** The widget's provider is on the phone: create it again in place. */
        RECREATE,

        /** Its app isn't installed and a store app opens the app's page: find it there, or remove the tile. */
        STORE_OR_REMOVE,

        /** The widget can't be found (its app is installed without it, or there is no store to look in): remove the tile. */
        REMOVE,
    }

    /** What to offer; pure. */
    fun offer(providerInstalled: Boolean, appInstalled: Boolean, storeOpensLink: Boolean): Offer = when {
        providerInstalled -> Offer.RECREATE
        !appInstalled && storeOpensLink -> Offer.STORE_OR_REMOVE
        else -> Offer.REMOVE
    }

    /** The store's page of [packageName]; it opens only when a store app handles it (see [diagnose]). */
    fun storeLink(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What [diagnose] found: the offer, and for [Offer.RECREATE] the provider to create it from. */
    class Diagnosis(val offer: Offer, val packageName: String, val provider: AppWidgetProviderInfo?, val user: UserHandle?)

    /**
     * Looks the widget up: is its provider installed for its user, is its app, and does a store app
     * open the app's page. Binder calls, one of them listing every provider: off the main thread.
     * Anything the system won't answer counts as "no", which can only lead to fewer offers.
     */
    fun diagnose(context: Context, widget: TileContent.Widget): Diagnosis {
        val component = ComponentName.unflattenFromString(widget.provider)
        val packageName = component?.packageName.orEmpty()
        val user = runCatching { context.getSystemService(UserManager::class.java).getUserForSerialNumber(widget.userSerial) }.getOrNull()
        val provider = if (component != null && user != null) {
            runCatching { AppWidgetManager.getInstance(context).getInstalledProvidersForProfile(user).firstOrNull { it.provider == component } }.getOrNull()
        } else {
            null
        }
        val appInstalled = packageName.isNotEmpty() && user != null &&
            runCatching { context.getSystemService(LauncherApps::class.java).isPackageEnabled(packageName, user) }.getOrDefault(false)
        // Only for a profile this phone has: a work profile's widget can't come back without it, so
        // installing the app would only lead back to the store.
        val storeOpensLink = packageName.isNotEmpty() && user != null &&
            runCatching { context.packageManager.resolveActivity(storeLink(packageName), 0) != null }.getOrDefault(false)
        return Diagnosis(offer(provider != null, appInstalled, storeOpensLink), packageName, provider, user)
    }
}

/**
 * The tile of a widget that is gone (see [WidgetRestore]): its name, and what a tap does, so the
 * empty tile doesn't look broken. [appInstalled] is a cheap guess for the wording only; the tap
 * looks the widget up properly.
 */
@Composable
internal fun UnavailableWidgetTile(tile: ResolvedTile, cellSize: Dp) {
    val m = rememberTileMetrics(cellSize)
    Box(Modifier.fillMaxSize().tileSurface()) {
        // Sized with the tile like its label, and white like all tile text, so it reads on any tile colour.
        MetroText(
            if (tile.app != null) "tap to set up again" else "widget unavailable",
            MetroType.caption.copy(fontSize = m.text(11f)),
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 2,
            modifier = Modifier.align(Alignment.TopStart).padding(m.dp(8f)),
        )
        TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
    }
}

/**
 * A tap on the tile of a gone widget: creates it again in place when it can ([onRecreated] gets
 * the new id), else offers the store (when a store app opens the page) and removal ([onRemove]).
 * Ends with [onDismiss], whatever the user chose or cancelled.
 */
@Composable
internal fun WidgetRestoreFlow(
    widget: TileContent.Widget,
    onRecreated: (Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val creator = rememberWidgetCreator()
    val diagnosis by produceState<WidgetRestore.Diagnosis?>(null, widget) {
        value = withContext(Dispatchers.IO) { WidgetRestore.diagnose(context, widget) }
    }
    val found = diagnosis ?: return
    val currentOnRecreated by rememberUpdatedState(onRecreated)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val provider = found.provider
    val user = found.user
    if (found.offer == WidgetRestore.Offer.RECREATE && provider != null && user != null) {
        LaunchedEffect(found) {
            // The widget's own default size until its tile reports the real one, as from the picker.
            val dims = WidgetTiles.dimsOf(context, provider)
            creator.create(provider, user, SizeF(dims.minWidth, dims.minHeight), onCreated = {
                currentOnRecreated(it)
                currentOnDismiss()
            }, onCancelled = { currentOnDismiss() })
        }
        return
    }
    BackHandler(onBack = onDismiss)
    MetroFlyout(onDismiss) {
        val remove = "remove" to {
            onRemove()
            onDismiss()
        }
        val items = if (found.offer == WidgetRestore.Offer.STORE_OR_REMOVE) {
            listOf(
                "find in store" to {
                    runCatching { context.startActivity(WidgetRestore.storeLink(found.packageName)) }
                    onDismiss()
                },
                remove,
            )
        } else {
            listOf(remove)
        }
        MetroMenu(items, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
