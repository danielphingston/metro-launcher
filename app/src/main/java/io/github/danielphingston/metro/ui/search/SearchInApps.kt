package io.github.danielphingston.metro.ui.search

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SearchInOrdering
import io.github.danielphingston.metro.data.SearchInTarget
import io.github.danielphingston.metro.data.SmartFeature
import java.util.Locale

/**
 * Which Amazon store to search. The country comes from the SIM, else the network the phone is on,
 * else the language settings (the first of the three that is known decides); an unknown country,
 * or one without a store of its own, gets amazon.com.
 */
internal object AmazonDomain {
    private val stores = mapOf(
        "us" to "amazon.com", "in" to "amazon.in", "gb" to "amazon.co.uk", "uk" to "amazon.co.uk", "ie" to "amazon.ie",
        "de" to "amazon.de", "at" to "amazon.de", "fr" to "amazon.fr", "it" to "amazon.it", "es" to "amazon.es",
        "nl" to "amazon.nl", "be" to "amazon.com.be", "se" to "amazon.se", "pl" to "amazon.pl", "tr" to "amazon.com.tr",
        "ae" to "amazon.ae", "sa" to "amazon.sa", "eg" to "amazon.eg", "jp" to "amazon.co.jp", "ca" to "amazon.ca",
        "mx" to "amazon.com.mx", "br" to "amazon.com.br", "au" to "amazon.com.au", "sg" to "amazon.sg",
    )

    const val DEFAULT = "amazon.com"

    /** The store's host for the first of [sim], [network], [locale] (ISO 3166 codes, any case) that has an Amazon store. */
    fun choose(sim: String?, network: String?, locale: String?): String =
        listOf(sim, network, locale).firstNotNullOfOrNull { stores[it?.trim()?.lowercase(Locale.ROOT)] } ?: DEFAULT

    /** [choose] with the phone's own SIM, network and language. */
    fun forPhone(context: Context, locale: Locale): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return choose(telephony?.simCountryIso, telephony?.networkCountryIso, locale.country)
    }
}

/** How to search each app: intents are built here, without touching the phone, so they can be tested. */
internal object SearchInIntents {
    const val AMAZON_PACKAGE = "com.amazon.mShop.android.shopping"

    private fun enc(query: String) = Uri.encode(query)

    fun youtubeApp(query: String): Intent =
        Intent(Intent.ACTION_SEARCH).setPackage(SearchInApp.YOUTUBE.packageName).putExtra(SearchManager.QUERY, query)

    fun youtubeWeb(query: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${enc(query)}"))
            .addCategory(Intent.CATEGORY_BROWSABLE)

    fun playStore(query: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${enc(query)}")).setPackage(SearchInApp.PLAY_STORE.packageName)

    fun maps(query: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${enc(query)}"))

    fun spotify(query: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${enc(query)}")).setPackage(SearchInApp.SPOTIFY.packageName)

    /**
     * The store's search page; [app] true opens it in the Amazon Shopping app ([packageName]: the
     * global build, or the India one), false in the browser.
     */
    fun amazon(query: String, domain: String, app: Boolean, packageName: String = AMAZON_PACKAGE): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.$domain/s?k=${enc(query)}")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (app) setPackage(packageName)
        }

    /** A search through any app that offers one; [web] picks ACTION_WEB_SEARCH over ACTION_SEARCH. */
    fun other(packageName: String, query: String, web: Boolean): Intent =
        Intent(if (web) Intent.ACTION_WEB_SEARCH else Intent.ACTION_SEARCH)
            .setPackage(packageName)
            .putExtra(SearchManager.QUERY, query)
}

/** An installed app (other than the built-in ones) that can be searched, for the settings list and the results. */
internal data class OtherSearchApp(val packageName: String, val label: String, val web: Boolean)

/** What the phone can answer; a seam so the provider is tested without a phone. */
internal interface SearchInPhone {
    fun resolves(intent: Intent): Boolean
    fun amazonDomain(locale: Locale): String
}

internal class RealSearchInPhone(private val context: Context) : SearchInPhone {
    override fun resolves(intent: Intent) = context.packageManager.resolveActivity(intent, 0) != null

    override fun amazonDomain(locale: Locale) = AmazonDomain.forPhone(context, locale)
}

/** The rows "search <app> for ..." and which apps they are for. */
internal object SearchInApps {
    private val builtInPackages = SearchInApp.entries.flatMapTo(HashSet()) { it.packages }

    /**
     * The installed app (one of [packages], the main profile's copy first) among [apps], or null. Looked up by
     * every package the app ships under, so Amazon's India build gets its own icon too.
     */
    fun installedApp(apps: List<AppInfo>, packages: List<String>): AppInfo? =
        packages.firstNotNullOfOrNull { pkg ->
            apps.firstOrNull { it.key.packageName == pkg && it.key.userSerial == 0L } ?: apps.firstOrNull { it.key.packageName == pkg }
        }

    /** The picture of [app] in the strip and the settings: its own icon when installed, else the plain search glyph. */
    fun icon(apps: List<AppInfo>, app: SearchInApp): SearchIcon =
        installedApp(apps, app.packages)?.let { SearchIcon.App(it) } ?: SearchGlyphs.search

    /** Every target the strip and its settings can offer, in natural order: the built-in apps, then [others]. */
    fun allTargets(others: List<OtherSearchApp>): List<SearchInTarget> =
        SearchInApp.entries.map { SearchInTarget(SearchInOrdering.builtInId(it), it.label) } +
            others.map { SearchInTarget(SearchInOrdering.otherId(it.packageName), it.label) }

    /** Apps that take an ACTION_SEARCH or ACTION_WEB_SEARCH aimed at their package, other than the built-in ones and Metro itself. */
    fun otherApps(context: Context, apps: List<AppInfo>): List<OtherSearchApp> {
        val cache = otherCache
        if (cache != null && cache.apps === apps) return cache.found
        return queryOtherApps(context, apps).also { otherCache = OtherCache(apps, it) }
    }

    /** The last answer of [otherApps], for the apps list it was asked about: the list is replaced when packages change. */
    private class OtherCache(val apps: List<AppInfo>, val found: List<OtherSearchApp>)

    @Volatile
    private var otherCache: OtherCache? = null

    private fun queryOtherApps(context: Context, apps: List<AppInfo>): List<OtherSearchApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, OtherSearchApp>()
        for (web in listOf(false, true)) {
            val probe = Intent(if (web) Intent.ACTION_WEB_SEARCH else Intent.ACTION_SEARCH)
            pm.queryIntentActivities(probe, 0).forEach { info ->
                val pkg = info.activityInfo?.packageName ?: return@forEach
                if (pkg in builtInPackages || pkg == context.packageName || pkg in found) return@forEach
                val label = apps.firstOrNull { it.key.packageName == pkg }?.label ?: info.loadLabel(pm).toString()
                found[pkg] = OtherSearchApp(pkg, label, web)
            }
        }
        return found.values.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    /**
     * The intent that searches [app] for [query], or null when it can't be done: the app is
     * missing and no web page stands in for it. YouTube and Amazon fall back to their websites.
     */
    fun intentFor(app: SearchInApp, query: String, phone: SearchInPhone, locale: Locale): Intent? = when (app) {
        SearchInApp.YOUTUBE ->
            SearchInIntents.youtubeApp(query).takeIf { phone.resolves(it) } ?: SearchInIntents.youtubeWeb(query)
        SearchInApp.PLAY_STORE -> SearchInIntents.playStore(query).takeIf { phone.resolves(it) }
        SearchInApp.MAPS -> SearchInIntents.maps(query).takeIf { phone.resolves(it) }
        SearchInApp.SPOTIFY -> SearchInIntents.spotify(query).takeIf { phone.resolves(it) }
        SearchInApp.AMAZON -> {
            val domain = phone.amazonDomain(locale)
            app.packages.firstNotNullOfOrNull { pkg ->
                SearchInIntents.amazon(query, domain, app = true, packageName = pkg).takeIf { phone.resolves(it) }
            } ?: SearchInIntents.amazon(query, domain, app = false)
        }
    }

    /** The intent for an installed app that takes a search (ACTION_SEARCH, else ACTION_WEB_SEARCH), or null if it takes neither. */
    fun intentForOther(packageName: String, query: String, phone: SearchInPhone): Intent? =
        listOf(false, true).map { SearchInIntents.other(packageName, query, it) }.firstOrNull { phone.resolves(it) }

    /** The title of a row: "search YouTube for 'cats'". */
    fun title(label: String, query: String) = "search $label for “$query”"

    /** The caption above the strip: search “cats” in */
    fun stripCaption(query: String) = "search “$query” in"
}

/**
 * Feature 10: one row for "search <query> in", a strip of the apps that take the typed text, in the
 * order the user chose (most used from search, or custom). Each chip is the app's own icon in the
 * app list's style, or a plain search glyph when the app isn't installed and a web page stands in.
 */
internal class SearchInAppsProvider(
    private val phoneFor: (Context) -> SearchInPhone = ::RealSearchInPhone,
    private val others: (Context, List<AppInfo>) -> List<OtherSearchApp> = SearchInApps::otherApps,
) : SearchProvider {
    override val id = "search_in_apps"
    override val feature = SmartFeature.SEARCH_IN_APPS
    override val placement = Placement.BOTTOM

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val phone = phoneFor(ctx.context)
        // In natural order (built-in apps, then others by name); the user's order is applied below.
        val found = ArrayList<Pair<SearchInTarget, ItemChip>>()
        fun add(id: String, label: String, icon: SearchIcon, intent: Intent) {
            found += SearchInTarget(id, label) to ItemChip(id, label, "search $label for $query", icon, SearchActions.start(intent))
        }
        for (app in SearchInApp.entries) {
            if (app !in ctx.settings.searchInApps) continue
            val intent = SearchInApps.intentFor(app, query, phone, ctx.locale) ?: continue
            add(SearchInOrdering.builtInId(app), app.label, SearchInApps.icon(ctx.apps, app), intent)
        }
        val enabledOthers = ctx.settings.searchInOtherApps
        if (enabledOthers.isNotEmpty()) {
            others(ctx.context, ctx.apps).filter { it.packageName in enabledOthers }.forEach { other ->
                val intent = SearchInIntents.other(other.packageName, query, other.web)
                if (!phone.resolves(intent)) return@forEach
                val icon = SearchInApps.installedApp(ctx.apps, listOf(other.packageName))?.let { SearchIcon.App(it) } ?: SearchGlyphs.search
                add(SearchInOrdering.otherId(other.packageName), other.label, icon, intent)
            }
        }
        if (found.isEmpty()) return emptyList()
        val chips = found.associate { (target, chip) -> target.id to chip }
        val ordered = SearchInOrdering.ordered(found.map { it.first }, ctx.settings.searchInOrder, ctx.settings.searchInCustomOrder, ctx.searchInUses)
        return listOf(
            SearchItem(
                key = "strip",
                title = SearchInApps.stripCaption(query),
                icon = SearchGlyphs.search,
                action = ItemAction.Run { },
                chips = ordered.mapNotNull { chips[it.id] },
                capturesEnter = false,
            ),
        )
    }
}
