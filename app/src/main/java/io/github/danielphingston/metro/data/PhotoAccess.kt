package io.github.danielphingston.metro.data

/** Whether the app may still read a photo the user picked. */
object PhotoAccess {
    /** Whether [uri] is among the [persistedReadUris] this app still holds read access to. */
    fun hasPersistedAccess(persistedReadUris: List<String>, uri: String): Boolean = uri in persistedReadUris
}
