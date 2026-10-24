package io.github.danielphingston.metro.data

/** Stable identity of a launchable activity in a given user profile. */
data class AppKey(val packageName: String, val className: String, val userSerial: Long) {
    fun serialize(): String = "$packageName/$className#$userSerial"

    /** Key used to match notifications, which are per package rather than per activity. */
    val notificationKey: String get() = notificationKey(packageName, userSerial)

    companion object {
        fun notificationKey(packageName: String, userSerial: Long) = "$packageName#$userSerial"

        fun parse(value: String): AppKey? {
            val hash = value.lastIndexOf('#')
            val slash = value.indexOf('/')
            if (hash < 0 || slash < 0 || slash > hash) return null
            val serial = value.substring(hash + 1).toLongOrNull() ?: return null
            return AppKey(value.substring(0, slash), value.substring(slash + 1, hash), serial)
        }
    }
}
