package io.github.danielphingston.metro.data

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable

// TODO(iconpacks): implement. Owned by the icon pack feature.

data class IconPackInfo(val packageName: String, val label: String)

class IconPacks(private val context: Context) {
    fun installed(): List<IconPackInfo> = emptyList()

    /** The pack's icon for [component], or null if the pack has none. Called off the main thread. */
    fun drawable(packPackage: String, component: ComponentName, density: Int): Drawable? = null
}
