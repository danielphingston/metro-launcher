package io.github.danielphingston.metro.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/** The one-time "Quick settings tiles" purchase: unlocked, a buy button with Play's price, or not on sale yet. */
@Composable
fun QuickTilesSettingsSection() {
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
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
