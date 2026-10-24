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
            NotificationListenerService.requestRebind(component(context))
        }
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
}
