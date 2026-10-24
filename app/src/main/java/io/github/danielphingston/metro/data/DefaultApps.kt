package io.github.danielphingston.metro.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Telephony

/**
 * Packages of the user's default apps for common roles. Used to build the first-run start
 * screen and to decide which tiles get built-in live content (clock, calendar).
 */
data class DefaultApps(
    val dialer: String? = null,
    val messaging: String? = null,
    val browser: String? = null,
    val email: String? = null,
    val camera: String? = null,
    val calendar: String? = null,
    val clock: String? = null,
    val gallery: String? = null,
    val music: String? = null,
) {
    companion object {
        val EMPTY = DefaultApps()

        fun resolve(context: Context): DefaultApps {
            val pm = context.packageManager
            fun resolve(intent: Intent): String? =
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
                    // "android" is the chooser: no single default for this role.
                    ?.takeIf { it != "android" }

            fun category(category: String) =
                resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))

            return DefaultApps(
                dialer = resolve(Intent(Intent.ACTION_DIAL)),
                messaging = Telephony.Sms.getDefaultSmsPackage(context)
                    ?: resolve(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
                browser = resolve(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
                email = resolve(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))),
                camera = resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
                calendar = category(Intent.CATEGORY_APP_CALENDAR),
                clock = resolve(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
                gallery = category(Intent.CATEGORY_APP_GALLERY),
                music = category(Intent.CATEGORY_APP_MUSIC),
            )
        }
    }
}
