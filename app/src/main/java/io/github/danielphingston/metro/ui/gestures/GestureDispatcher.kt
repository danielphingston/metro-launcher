package io.github.danielphingston.metro.ui.gestures

import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.GestureAction
import io.github.danielphingston.metro.data.GestureBinding
import io.github.danielphingston.metro.live.MetroAccessibilityService
import io.github.danielphingston.metro.ui.settings.SettingsActivity

/** The parts of the launcher a gesture can drive. Implemented by the launcher screen. */
interface GestureHost {
    fun openSearch()
    fun showApps()
    fun editTiles()

    /** Opens [app]; does nothing when it is gone. */
    fun openApp(app: AppKey)
}

/** What a gesture does right now; pure, so the rules are testable. */
enum class GestureRoute {
    /** No action, or an app gesture without an app. */
    NOTHING,

    /** Go ahead (an action with a direct way, or one whose service is on). */
    PERFORM,

    /** The action only works through the accessibility service, which is off: say so instead. */
    NEEDS_SERVICE,
}

object GestureRouting {
    fun route(binding: GestureBinding, serviceOn: Boolean): GestureRoute = when {
        !binding.active -> GestureRoute.NOTHING
        binding.action.access == GestureAction.Access.REQUIRED && !serviceOn -> GestureRoute.NEEDS_SERVICE
        else -> GestureRoute.PERFORM
    }
}

/**
 * Carries out a gesture's action. Launcher actions go to [host]; lock, recents and (when the
 * direct way is refused) the notification shade and quick settings go through the optional
 * accessibility service, which only does something when this asks it to.
 */
class GestureDispatcher(
    private val context: Context,
    private val host: GestureHost,
    private val tick: () -> Unit,
) {
    fun perform(binding: GestureBinding) {
        when (GestureRouting.route(binding, MetroAccessibilityService.isConnected)) {
            GestureRoute.NOTHING -> Unit
            GestureRoute.NEEDS_SERVICE -> toastServiceOff()
            GestureRoute.PERFORM -> {
                tick()
                run(binding)
            }
        }
    }

    private fun run(binding: GestureBinding) {
        when (binding.action) {
            GestureAction.NONE -> Unit
            GestureAction.SLEEP -> MetroAccessibilityService.lockScreen()
            GestureAction.RECENTS -> MetroAccessibilityService.recents()
            GestureAction.NOTIFICATIONS ->
                if (!expandStatusBar("expandNotificationsPanel") && !MetroAccessibilityService.notifications()) toastServiceOff()
            GestureAction.QUICK_SETTINGS ->
                if (!expandStatusBar("expandSettingsPanel") && !MetroAccessibilityService.quickSettings()) toastServiceOff()
            GestureAction.SEARCH -> host.openSearch()
            GestureAction.APP_LIST -> host.showApps()
            GestureAction.EDIT_TILES -> host.editTiles()
            GestureAction.METRO_SETTINGS ->
                context.startActivity(SettingsActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GestureAction.OPEN_APP -> binding.app?.let(host::openApp)
        }
    }

    /**
     * Opens the shade or quick settings through the hidden StatusBarManager method (needs the
     * normal EXPAND_STATUS_BAR permission), which needs no accessibility service. Newer Android
     * versions may refuse the reflection, then this is false and the service is the way.
     */
    private fun expandStatusBar(method: String): Boolean = runCatching {
        val manager = context.getSystemService("statusbar") ?: return false
        manager.javaClass.getMethod(method).invoke(manager)
    }.isSuccess

    private fun toastServiceOff() {
        Toast.makeText(context, "turn on Metro's accessibility service in gestures settings", Toast.LENGTH_SHORT).show()
    }
}
