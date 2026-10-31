package io.github.danielphingston.metro.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

class AppInfo(
    val key: AppKey,
    val label: String,
    val user: UserHandle,
    val component: ComponentName,
    val isUninstallable: Boolean,
    /** When the app was first installed, in epoch millis; 0 if unknown. */
    val installTime: Long = 0L,
    /** Loads the raw icon at a screen density; `badged` adds the work-profile badge. */
    internal val loadIcon: (density: Int, badged: Boolean) -> Drawable,
) {
    companion object {
        fun from(info: LauncherActivityInfo, serial: Long): AppInfo {
            val flags = info.applicationInfo.flags
            return AppInfo(
                key = AppKey(info.componentName.packageName, info.componentName.className, serial),
                label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                user = info.user,
                component = info.componentName,
                isUninstallable = flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                    flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                // The same value as PackageInfo.firstInstallTime, and it also works for the work profile.
                installTime = info.firstInstallTime,
                loadIcon = { density, badged -> if (badged) info.getBadgedIcon(density) else info.getIcon(density) },
            )
        }
    }
}

/**
 * Launchable apps across all user profiles, kept current through [LauncherApps.Callback].
 * Uses LauncherApps (no QUERY_ALL_PACKAGES) and only reloads when a package actually changes.
 */
class AppRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPackageRemoved: (packageName: String, userSerial: Long) -> Unit,
    private val onPackageChanged: (packageName: String) -> Unit,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val _apps = MutableStateFlow<List<AppInfo>?>(null)
    /** Null until the first load completes. */
    val apps: StateFlow<List<AppInfo>?> = _apps.asStateFlow()

    private val _defaults = MutableStateFlow(DefaultApps.EMPTY)
    val defaults: StateFlow<DefaultApps> = _defaults.asStateFlow()

    private val reloadRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * Packages updated since the last reload. Their icons are dropped only once the reload has
     * published the new AppInfo: dropping them earlier let visible tiles redraw from the old
     * AppInfo, whose APK is gone, and cache a blank or default icon until the next restart.
     */
    private val changedPackages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            onPackageRemoved(packageName, serialOf(user))
            requestReload()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = requestReload()

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            changedPackages.add(packageName)
            requestReload()
        }

        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            changedPackages.addAll(packageNames)
            requestReload()
        }

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            requestReload()
    }

    init {
        try {
            launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        } catch (_: RuntimeException) {
            // No live updates then; the list is still loaded now and whenever the app restarts.
            // This runs at process start, where a failure would repeat on every launch.
        }
        scope.launch {
            reloadRequests.receiveAsFlow().collect { reload() }
        }
        requestReload()
    }

    fun requestReload() {
        reloadRequests.trySend(Unit)
    }

    private suspend fun reload() = withContext(Dispatchers.Default) {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val result = launcherApps.profiles.flatMap { user ->
            try {
                val serial = serialOf(user)
                launcherApps.getActivityList(null, user).map { AppInfo.from(it, serial) }
            } catch (_: RuntimeException) {
                // A profile that is stopped, locked or being removed can refuse (SecurityException,
                // IllegalStateException, binder failures); the others still load. This runs at start.
                emptyList()
            }
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
        _defaults.value = try {
            DefaultApps.resolve(context)
        } catch (_: RuntimeException) {
            DefaultApps.EMPTY
        }
        _apps.value = result
        val changed = changedPackages.toList().also { changedPackages.removeAll(it.toSet()) }
        if (changed.isNotEmpty()) withContext(Dispatchers.Main) { changed.forEach(onPackageChanged) }
    }

    fun serialOf(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    /** Returns false if the system refused. */
    fun launch(app: AppInfo, sourceBounds: Rect?, options: Bundle?): Boolean =
        runCatching { launcherApps.startMainActivity(app.component, app.user, sourceBounds, options) }.isSuccess

    fun openAppInfo(app: AppInfo) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    fun uninstall(app: AppInfo) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.key.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
