package io.github.danielphingston.metro.live

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, and only for Metro's own gestures: it locks the screen, opens the notification shade,
 * quick settings or recent apps when the launcher asks. It requests no events and no window
 * content (see res/xml/accessibility_service_config.xml), so it never sees anything on screen
 * and costs nothing while idle.
 */
class MetroAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: MetroAccessibilityService? = null

        /** Whether the user has turned the service on and the system has connected it. */
        val isConnected: Boolean get() = instance != null

        fun lockScreen() = perform(GLOBAL_ACTION_LOCK_SCREEN)
        fun notifications() = perform(GLOBAL_ACTION_NOTIFICATIONS)
        fun quickSettings() = perform(GLOBAL_ACTION_QUICK_SETTINGS)
        fun recents() = perform(GLOBAL_ACTION_RECENTS)

        /** False when the service is off or the system refused. */
        private fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }
}
