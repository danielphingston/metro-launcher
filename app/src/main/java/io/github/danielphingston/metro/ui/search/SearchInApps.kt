package io.github.danielphingston.metro.ui.search

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.SearchInApp
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

    /** The store's search page; [app] true opens it in the Amazon Shopping app, false in the browser. */
    fun amazon(query: String, domain: String, app: Boolean): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.$domain/s?k=${enc(query)}")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (app) setPackage(AMAZON_PACKAGE)
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
    private val builtInPackages = SearchInApp.entries.mapTo(HashSet()) { it.packageName }

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
            SearchInIntents.amazon(query, domain, app = true).takeIf { phone.resolves(it) }
                ?: SearchInIntents.amazon(query, domain, app = false)
        }
    }

    /** The intent for an installed app that takes a search (ACTION_SEARCH, else ACTION_WEB_SEARCH), or null if it takes neither. */
    fun intentForOther(packageName: String, query: String, phone: SearchInPhone): Intent? =
        listOf(false, true).map { SearchInIntents.other(packageName, query, it) }.firstOrNull { phone.resolves(it) }

    /** The title of a row: "search YouTube for 'cats'". */
    fun title(label: String, query: String) = "search $label for “$query”"
}

/** Feature 10: rows that hand the typed text to another app's own search. */
internal class SearchInAppsProvider(
    private val phoneFor: (Context) -> SearchInPhone = ::RealSearchInPhone,
    private val others: (Context, List<AppInfo>) -> List<OtherSearchApp> = SearchInApps::otherApps,
) : SearchProvider {
    override val id = "search_in_apps"
    override val feature = SmartFeature.SEARCH_IN_APPS
    override val placement = Placement.BOTTOM
    override val section = "search in apps"

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val phone = phoneFor(ctx.context)
        val items = ArrayList<SearchItem>()
        fun iconOf(packageName: String, fallback: SearchIcon) =
            ctx.apps.firstOrNull { it.key.packageName == packageName && it.key.userSerial == 0L }
                ?.let { SearchIcon.App(it) }
                ?: ctx.apps.firstOrNull { it.key.packageName == packageName }?.let { SearchIcon.App(it) }
                ?: fallback

        for (app in SearchInApp.entries) {
            if (app !in ctx.settings.searchInApps) continue
            val intent = SearchInApps.intentFor(app, query, phone, ctx.locale) ?: continue
            items += SearchItem(
                key = app.id,
                title = SearchInApps.title(app.label, query),
                icon = iconOf(app.packageName, SearchGlyphs.search),
                action = SearchActions.start(intent),
            )
        }
        val enabledOthers = ctx.settings.searchInOtherApps
        if (enabledOthers.isNotEmpty()) {
            others(ctx.context, ctx.apps).filter { it.packageName in enabledOthers }.forEach { other ->
                val intent = SearchInIntents.other(other.packageName, query, other.web)
                if (!phone.resolves(intent)) return@forEach
                items += SearchItem(
                    key = "other:${other.packageName}",
                    title = SearchInApps.title(other.label, query),
                    icon = iconOf(other.packageName, SearchGlyphs.search),
                    action = SearchActions.start(intent),
                )
            }
        }
        return items
    }
}
