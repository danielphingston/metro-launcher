package io.github.danielphingston.metro.live

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

object NotificationAccess {
    private fun component(context: Context) = ComponentName(context, MetroNotificationListener::class.java)

    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Some OEM battery managers unbind listeners; ask the system to bind us again. */
    fun rebindIfNeeded(context: Context) {
        if (isGranted(context) && !NotificationHub.connected.value) {
            try {
                NotificationListenerService.requestRebind(component(context))
            } catch (_: RuntimeException) {
                // The notification service is gone or refuses (a binder failure is rethrown as a
                // RuntimeException); this runs on every start, so it must not take the app down.
            }
        }
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
}
