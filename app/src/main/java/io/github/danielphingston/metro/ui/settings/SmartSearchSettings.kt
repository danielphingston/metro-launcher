package io.github.danielphingston.metro.ui.settings

import android.app.role.RoleManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.search.OtherSearchApp
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/**
 * The "smart search" section of the search settings. While Metro Plus is locked every feature is
 * listed with a lock and one button leads to the purchase; once unlocked each feature is a switch.
 * Calendar and contacts ask for their permission when switched on, like the tiles that use them.
 * [assistantReady] is whether an assistant app is chosen, which the ask row needs. "Search in
 * apps" lists the apps it can search under its own switch; [otherSearchApps] are the installed
 * apps beyond the built-in ones that accept a search.
 */
@Composable
internal fun SmartSearchSection(
    settings: LauncherSettings,
    repository: SettingsRepository,
    unlocked: Boolean,
    onUnlock: () -> Unit,
    assistantReady: Boolean = true,
    otherSearchApps: List<OtherSearchApp> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Section("smart search")
    if (!unlocked) {
        MetroText(
            "Smart search is part of Metro Plus, a one-time purchase that also unlocks the quick settings " +
                "and assistant tiles. Plain app search, the web row and smarter matching stay free.",
            MetroType.caption,
            color = Metro.colors.subtle,
        )
        Spacer(Modifier.height(8.dp))
        SmartFeature.entries.forEach { LockedFeatureRow(it.title, it.description) }
        LockedFeatureRow("smarter app matching", "initials such as yt, small typos, matches inside words", free = true)
        Spacer(Modifier.height(12.dp))
        MetroButton("unlock with Metro Plus", onClick = onUnlock)
        return
    }

    MetroText(
        "Everything stays on this phone. Results appear as you type, under the apps; tap one to use it.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )
    Spacer(Modifier.height(4.dp))
    SmartFeature.entries.forEach { feature ->
        val on = settings.smartOn(feature)
        val save: suspend (Boolean) -> Unit = { repository.setSmartFeature(feature, it) }
        when {
            feature.permission != null -> PermissionToggle(
                feature.title,
                arrayOf(feature.permission),
                feature.permission,
                on,
                save,
                if (feature == SmartFeature.CALENDAR) "calendar events need access to your calendar" else "contacts need access to your contacts",
                description = feature.description,
            )
            else -> MetroToggle(
                feature.title,
                on,
                { scope.launch { save(it) } },
                description = featureNote(feature, context.isDefaultHome(), assistantReady) ?: feature.description,
            )
        }
        if (feature == SmartFeature.SEARCH_IN_APPS && on) SearchInAppsList(settings, repository, otherSearchApps)
    }
    Spacer(Modifier.height(4.dp))
    MetroText(
        "Smarter app matching (initials such as yt, small typos, matches inside words) is free and always on.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )
}

/** The per-app switches under "search in apps": the built-in apps, then other installed apps that accept a search. */
@Composable
private fun SearchInAppsList(settings: LauncherSettings, repository: SettingsRepository, others: List<OtherSearchApp>) {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(start = 20.dp)) {
        SearchInApp.entries.forEach { app ->
            MetroToggle(
                app.label,
                app in settings.searchInApps,
                { scope.launch { repository.setSearchInApp(app, it) } },
                description = searchInAppNote(app),
            )
        }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MetroText("other apps", MetroType.caption, color = Metro.colors.subtle)
            others.forEach { other ->
                MetroToggle(
                    other.label,
                    other.packageName in settings.searchInOtherApps,
                    { scope.launch { repository.setSearchInOtherApp(other.packageName, it) } },
                )
            }
        }
    }
}

/** What to tell about a built-in app's row, or null for none. */
internal fun searchInAppNote(app: SearchInApp): String? = when (app) {
    SearchInApp.YOUTUBE -> "opens the website when the app isn't installed"
    SearchInApp.AMAZON -> "the store for your country, in the Amazon app or the browser"
    else -> null
}

/** A caption that replaces a feature's description when it can't work yet, or null when it can. */
internal fun featureNote(feature: SmartFeature, isDefaultHome: Boolean, assistantReady: Boolean): String? = when {
    feature == SmartFeature.WHATSAPP && !isDefaultHome ->
        "needs Metro as the default home app: Android only lets the home app read other apps' shortcuts"
    feature == SmartFeature.ASK_ASSISTANT && !assistantReady ->
        "choose an assistant app in the Metro Plus page first"
    else -> null
}

@Composable
private fun android.content.Context.isDefaultHome(): Boolean =
    rememberOnResume { getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true }

@Composable
private fun LockedFeatureRow(title: String, description: String, free: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (free) {
            Spacer(Modifier.width(20.dp))
        } else {
            LockGlyph(Metro.colors.subtle, Modifier.padding(top = 4.dp).size(width = 20.dp, height = 22.dp))
        }
        Column(Modifier.weight(1f)) {
            MetroText(title, MetroType.body)
            MetroText(
                if (free) "free: $description" else description,
                MetroType.caption,
                color = Metro.colors.subtle,
            )
        }
    }
}

/** A hairline padlock: the shackle over a body. */
@Composable
internal fun LockGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "locked" }) { drawLock(color) }
}

private fun DrawScope.drawLock(color: Color) {
    val stroke = 1.5.dp.toPx()
    val bodyTop = size.height * 0.45f
    val shackleWidth = size.width * 0.6f
    val left = (size.width - shackleWidth) / 2
    drawArc(
        color, 180f, 180f, false,
        topLeft = Offset(left, stroke / 2),
        size = Size(shackleWidth, (bodyTop - stroke / 2) * 1.2f),
        style = Stroke(stroke),
    )
    drawRoundRect(
        color,
        topLeft = Offset(stroke / 2, bodyTop),
        size = Size(size.width - stroke, size.height - bodyTop - stroke / 2),
        cornerRadius = CornerRadius(1.dp.toPx()),
        style = Stroke(stroke),
    )
}
