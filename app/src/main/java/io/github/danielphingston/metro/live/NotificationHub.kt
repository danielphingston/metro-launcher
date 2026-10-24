package io.github.danielphingston.metro.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationPreview(val title: String, val text: String, val postTime: Long)

/** What a tile needs to know about an app's unread notifications. Previews are newest first. */
data class AppNotifications(val count: Int, val previews: List<NotificationPreview>)

/**
 * In-memory bridge between [MetroNotificationListener] and the UI. Both run in the launcher
 * process, so no IPC, database or polling is needed: the UI just observes [byApp].
 */
object NotificationHub {
    private val _byApp = MutableStateFlow<Map<String, AppNotifications>>(emptyMap())
    /** Keyed by [io.github.danielphingston.metro.data.AppKey.notificationKey]. */
    val byApp: StateFlow<Map<String, AppNotifications>> = _byApp.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    internal fun publish(value: Map<String, AppNotifications>) {
        _byApp.value = value
    }

    internal fun setConnected(connected: Boolean) {
        _connected.value = connected
        if (!connected) _byApp.value = emptyMap()
    }
}
