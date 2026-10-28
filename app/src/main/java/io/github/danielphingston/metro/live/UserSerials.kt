package io.github.danielphingston.metro.live

import android.content.Context
import android.os.UserHandle
import android.os.UserManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached [UserManager.getSerialNumberForUser]. The lookup is a binder call and a handle's serial never
 * changes while the process lives, so notification and media code, which ask once per notification,
 * resolve each profile once.
 */
object UserSerials {
    private val cache = ConcurrentHashMap<UserHandle, Long>()

    fun of(context: Context, user: UserHandle): Long = cache.getOrPut(user) {
        context.getSystemService(UserManager::class.java).getSerialNumberForUser(user)
    }
}
