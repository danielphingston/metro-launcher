package io.github.danielphingston.metro.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

data class IconPackInfo(val packageName: String, val label: String)

/**
 * Launcher icon packs in the common ADW/Nova/Apex format: an `appfilter.xml` (in the pack's
 * `res/xml` or assets) mapping each app's component to a drawable name in the pack.
 */
class IconPacks(private val context: Context) {
    /** Parsed `appfilter.xml` per pack package; an empty map for a pack that has none or fails to parse. */
    private val filters = ConcurrentHashMap<String, Map<ComponentName, String>>()

    /** Installed icon packs, sorted by label. Blocking: call off the main thread. */
    fun installed(): List<IconPackInfo> {
        val pm = context.packageManager
        return PACK_ACTIONS
            .flatMap { queryActivities(pm, Intent(it)) }
            .distinctBy { it.activityInfo.packageName }
            .map { IconPackInfo(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /** The pack's icon for [component], or null if the pack has none. Called off the main thread. */
    fun drawable(packPackage: String, component: ComponentName, density: Int): Drawable? = try {
        val name = filters.getOrPut(packPackage) { loadFilter(packPackage) }[component]
        if (name == null) {
            null
        } else {
            val resources = context.packageManager.getResourcesForApplication(packPackage)
            val id = resources.getIdentifier(name, "drawable", packPackage).takeIf { it != 0 }
                ?: resources.getIdentifier(name, "mipmap", packPackage)
            if (id == 0) null else resources.getDrawableForDensity(id, density, null)
        }
    } catch (_: Exception) {
        null
    }

    private fun loadFilter(pack: String): Map<ComponentName, String> = try {
        val resources = context.packageManager.getResourcesForApplication(pack)
        val id = resources.getIdentifier("appfilter", "xml", pack)
        if (id != 0) {
            resources.getXml(id).use { parseAppFilter(it) }
        } else {
            context.createPackageContext(pack, 0).assets.open("appfilter.xml").use { input ->
                parseAppFilter(Xml.newPullParser().apply { setInput(input, null) })
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    @Suppress("DEPRECATION")
    private fun queryActivities(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }

    private companion object {
        val PACK_ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME")
    }
}

/**
 * Reads the `<item component="ComponentInfo{pkg/cls}" drawable="name"/>` entries of an
 * `appfilter.xml`. Short class names (`pkg/.Cls`) are expanded; malformed items are skipped and
 * the first entry wins when a component is listed twice.
 */
fun parseAppFilter(parser: XmlPullParser): Map<ComponentName, String> {
    val result = HashMap<ComponentName, String>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == "item") {
            val component = parser.getAttributeValue(null, "component")?.trim()
            val drawable = parser.getAttributeValue(null, "drawable")?.trim()
            if (!component.isNullOrEmpty() && !drawable.isNullOrEmpty()) {
                val flat = component.removePrefix("ComponentInfo{").removeSuffix("}").trim()
                ComponentName.unflattenFromString(flat)?.let { result.putIfAbsent(it, drawable) }
            }
        }
        event = parser.next()
    }
    return result
}
