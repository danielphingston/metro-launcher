package io.github.danielphingston.metro.ui.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.Gesture
import io.github.danielphingston.metro.data.GestureAction
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ListFilter
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.live.MetroAccessibilityService
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.SearchField
import io.github.danielphingston.metro.ui.components.SearchFieldStyle
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/** Whether the user has switched Metro's accessibility service on in system settings. */
internal fun accessibilityServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    val name = MetroAccessibilityService::class.java.name
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == name
    }
}

/** Double tap, two-finger swipes and pinch: what each does, and the optional accessibility service some actions need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GesturesPage(settings: LauncherSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
    val serviceOn = rememberOnResume { accessibilityServiceEnabled(context) }
    val apps by (context.applicationContext as MetroApplication).apps.apps.collectAsState()
    val launchable = apps.orEmpty()
    var open by remember { mutableStateOf<Gesture?>(null) }
    // The disclosure and consent Google Play asks for before the user is sent to turn the service on.
    var askingConsent by remember { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))
    MetroText(
        "Give each gesture on the start screen an action. They are all off until you choose one. " +
            "A double tap counts on empty space only; tiles, buttons and widgets work as always.",
        MetroType.caption,
        color = colors.subtle,
    )

    Gesture.entries.forEach { gesture ->
        val binding = settings.gestures[gesture]
        val appLabel = binding.app?.let { key -> launchable.firstOrNull { it.key == key }?.label ?: "app not found" }
        Section(gesture.title)
        MetroChoice(
            if (binding.action == GestureAction.OPEN_APP) "open ${appLabel ?: "an app"}" else binding.action.label,
            selected = open == gesture,
            onClick = { open = if (open == gesture) null else gesture },
        )
        if (open == gesture) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GestureAction.entries.forEach { action ->
                    MetroChoice(action.label, binding.action == action, {
                        // Picking an app is a second step; everything else is chosen right away.
                        if (action != GestureAction.OPEN_APP) {
                            open = null
                            scope.launch { repository.setGesture(gesture, action) }
                            if (action.access == GestureAction.Access.REQUIRED && !serviceOn) askingConsent = true
                        } else if (binding.action != action) {
                            scope.launch { repository.setGesture(gesture, action, binding.app) }
                        }
                    })
                }
            }
            if (binding.action == GestureAction.OPEN_APP) {
                Spacer(Modifier.height(10.dp))
                var filter by remember { mutableStateOf("") }
                if (launchable.size > FILTER_FROM) {
                    SearchField(filter, { filter = it }, placeholder = "filter apps", style = SearchFieldStyle.OUTLINED)
                    Spacer(Modifier.height(8.dp))
                }
                val shown = launchable.filter { ListFilter.matches(filter, it.label) }
                if (shown.isEmpty()) MetroText("no app matches “${filter.trim()}”", MetroType.caption, color = colors.subtle)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { app ->
                        MetroChoice(app.label, binding.app == app.key, {
                            open = null
                            scope.launch { repository.setGesture(gesture, GestureAction.OPEN_APP, app.key) }
                        })
                    }
                }
            }
        }
        if (binding.action.usesAccessibility && !serviceOn) {
            Spacer(Modifier.height(6.dp))
            MetroText("needs the accessibility service, see below", MetroType.caption, color = colors.accent)
        }
    }

    Section("accessibility service")
    MetroText(if (serviceOn) "on" else "off", MetroType.body, color = if (serviceOn) colors.accent else colors.foreground)
    Spacer(Modifier.height(6.dp))
    MetroText(
        "Turning the screen off and opening recent apps need Metro's accessibility service; the notification " +
            "shade and quick settings use it only when Android does not allow Metro to open them directly. " +
            "It is optional: without it those gestures show a short message and do nothing.",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(6.dp))
    MetroText(
        "Metro only uses it to lock the screen or open the shade, quick settings or recent apps when you " +
            "perform a gesture you set up. It reads nothing from the screen and receives no events.",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(6.dp))
    MetroText(
        "If you installed Metro outside the Play Store, Android 13 and newer may grey the switch out until " +
            "you open Metro's app info, tap the three dots and choose \"allow restricted settings\".",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(10.dp))
    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    MetroButton(if (serviceOn) "accessibility settings" else "turn on", onClick = {
        if (serviceOn) openAccessibilitySettings() else askingConsent = true
    })

    if (askingConsent) {
        AccessibilityConsentDialog(
            onAgree = {
                askingConsent = false
                openAccessibilitySettings()
            },
            onDecline = { askingConsent = false },
        )
    }
}

/** What Metro's accessibility service is for, word for word what the user agrees to. */
internal const val ACCESSIBILITY_DISCLOSURE =
    "Metro Launcher uses Android's Accessibility API for one thing: to turn the screen off, or to open recent apps, " +
        "the notification shade or quick settings, when you make a start screen gesture you set up here.\n\n" +
        "Through it Metro does not see, read or collect anything: not what is on your screen, not what you type, " +
        "not which apps you use. Nothing is stored and nothing is shared.\n\n" +
        "It is optional. If you agree, Android's accessibility settings open next; turn on \"Metro gestures\" there. " +
        "You can turn it off again at any time."

/**
 * The prominent disclosure for the accessibility service: shown before the user is sent to the
 * system settings, and only "agree" goes on. Declining changes nothing; it is shown again the next
 * time the user asks to turn the service on.
 */
@Composable
private fun AccessibilityConsentDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
    val colors = Metro.colors
    Dialog(onDismissRequest = onDecline) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.chrome)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetroText("use the accessibility service?", MetroType.sectionTitle, maxLines = 3)
            MetroText(ACCESSIBILITY_DISCLOSURE, MetroType.body, color = colors.subtle, maxLines = 40)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetroButton("agree", modifier = Modifier.weight(1f), onClick = onAgree)
                MetroButton("no thanks", modifier = Modifier.weight(1f), onClick = onDecline)
            }
        }
    }
}
