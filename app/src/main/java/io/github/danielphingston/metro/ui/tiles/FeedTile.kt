package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.feed.CachedFeed
import io.github.danielphingston.metro.live.feed.FeedAddress
import io.github.danielphingston.metro.live.feed.FeedItem
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.LocalMotion
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.tileSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object FeedTiles {
    /** The link each feed tile (by tile id) shows right now, for the tap; see [FeedTile]. */
    private val shown = ConcurrentHashMap<Long, String>()

    internal fun setShown(tileId: Long, link: String?) {
        if (link == null) shown.remove(tileId) else shown[tileId] = link
    }

    /**
     * What tapping the feed tile [tileId] opens: the headline on it, else its first feed's page.
     * A Lemmy tile's links go to [lemmyApp] (chosen in settings) as long as it takes them: Lemmy
     * apps list the instances they open, and Android only sends a web link to an app on its own
     * when the site verified the app, which no instance does. Otherwise the system decides,
     * usually the browser.
     */
    fun intent(context: Context, tileId: Long, feed: TileContent.Feed, lemmyApp: String?): Intent {
        val link = shown[tileId] ?: FeedAddress.homePage(feed.urls.first())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (lemmyApp == null || !feed.isLemmy) return intent
        val direct = Intent(intent).setPackage(lemmyApp)
        val handled = runCatching { context.packageManager.resolveActivity(direct, PackageManager.MATCH_ALL) }.getOrNull() != null
        return if (handled) direct else intent
    }

    /** A Lemmy post being looked up for opening; a second tap meanwhile does nothing. */
    private val opening = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Opens what tapping the feed tile [tileId] opens, through [start]. For a Lemmy tile with a
     * Lemmy app chosen, the post is first looked up on its feed's instance (at most a couple of
     * seconds) for the address it has where it was written: see [FeedStore.canonicalPostLink] and
     * [canonicalIntent].
     */
    fun open(context: Context, tileId: Long, feed: TileContent.Feed, lemmyApp: String?, start: (Intent) -> Unit) {
        val plain = intent(context, tileId, feed, lemmyApp)
        val link = shown[tileId]
        if (lemmyApp == null || link == null || plain.`package` == null) {
            start(plain)
            return
        }
        if (!opening.compareAndSet(false, true)) return
        val scope = (context.applicationContext as? io.github.danielphingston.metro.MetroApplication)?.appScope
        if (scope == null) {
            opening.set(false)
            start(plain)
            return
        }
        scope.launch {
            try {
                val canonical = withTimeoutOrNull(2_500) { FeedStore.canonicalPostLink(link) }
                start(canonicalIntent(context, canonical, lemmyApp, plain))
            } finally {
                opening.set(false)
            }
        }
    }

    /**
     * Where a Lemmy post opens once its own address is known. [plain] is the link from the feed,
     * already aimed at [lemmyApp]. A post written on the feed's own instance (or one not looked
     * up) stays as it is. Otherwise its own address goes to [lemmyApp] if the app lists that
     * site; if not, the feed's link opens in the browser: given another instance's copy number,
     * a Lemmy app signed in elsewhere only says it can't find the post.
     */
    fun canonicalIntent(context: Context, canonical: String?, lemmyApp: String, plain: Intent): Intent {
        val link = plain.data?.toString()
        if (canonical == null || canonical == link) return plain
        val direct = Intent(Intent.ACTION_VIEW, Uri.parse(canonical)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(lemmyApp)
        val handled = runCatching { context.packageManager.resolveActivity(direct, PackageManager.MATCH_ALL) }.getOrNull() != null
        return if (handled) direct else Intent(plain).setPackage(null)
    }

    /** An app that opens Lemmy links: [packageName] and the name to show. */
    data class LinkApp(val packageName: String, val label: String)

    /** Large instances, to ask which apps open Lemmy links; with [lemmyApps]' extra hosts, those of the pinned tiles. */
    private val knownInstances = listOf(
        "lemmy.world", "lemmy.ml", "programming.dev", "sh.itjust.works", "lemm.ee", "beehaw.org",
        "feddit.org", "lemmy.zip", "lemmy.ca", "discuss.tchncs.de", "lemmy.dbzer0.com", "slrpnk.net",
    )

    /**
     * The installed apps that open Lemmy post links (Jerboa, Voyager, Thunder...), browsers left
     * out, with how many of [hosts] (instances the user follows) each one takes. Blocking binder
     * work: call off the main thread.
     */
    fun lemmyApps(context: Context, hosts: Collection<String> = emptyList()): List<LinkApp> {
        val pm = context.packageManager
        fun handlers(link: String): Set<String> = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE),
                PackageManager.MATCH_ALL,
            ).mapTo(HashSet()) { it.activityInfo.packageName }
        }.getOrDefault(emptySet())
        // Browsers open any address; an app that also opens one on a made-up site is a browser.
        val browsers = handlers("https://metro-launcher.invalid/")
        val own = context.packageName
        return (knownInstances + hosts).distinct()
            .flatMap { handlers("https://$it/post/1") - browsers - own }
            .distinct()
            .mapNotNull { pkg ->
                runCatching { LinkApp(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()) }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Whether [app] opens posts on [instance]: a Lemmy app only takes the instances it lists. */
    fun opens(context: Context, app: String, instance: String): Boolean = runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://$instance/post/1")).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app),
            PackageManager.MATCH_ALL,
        ) != null
    }.getOrDefault(false)

    /**
     * Several feeds' posts as one list, taken in turn (first of each, then second of each...),
     * so each feed keeps its own order (hot, new) and none crowds out the rest. A post that two
     * feeds share is shown once.
     */
    fun merge(lists: List<List<FeedItem>>): List<FeedItem> {
        val out = ArrayList<FeedItem>()
        val seen = HashSet<String>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (list in lists) {
            val item = list.getOrNull(i) ?: continue
            if (seen.add(item.link ?: item.title)) out += item
        }
        return out
    }

    /**
     * The state a tile with no headline to show describes: a feed that loaded (it is just empty),
     * else the first failure, else none yet (loading).
     */
    fun summary(cached: List<CachedFeed?>): CachedFeed? =
        cached.firstOrNull { it?.feed != null } ?: cached.firstOrNull { it?.error != null }

    /** "5m", "3h", "2d": how long ago [at] was, as Windows Phone's tiles put it. */
    fun age(at: Long, now: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes((now - at).coerceAtLeast(0))
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            else -> "${minutes / (60 * 24)}d"
        }
    }

    /** The line under a headline: Lemmy's points and comments when the feed has them, else who wrote it; then how long ago. */
    fun byline(item: FeedItem, now: Long): String {
        val who = if (item.points != null && item.comments != null) {
            "${item.points} points · ${item.comments} comments"
        } else {
            item.author
        }
        return listOfNotNull(who, item.publishedAt?.let { age(it, now) }).joinToString(" · ")
    }

    /** The headline the tile shows after [step] flips, of [count]. */
    fun index(step: Int, count: Int): Int = if (count == 0) 0 else Math.floorMod(step, count)
}

/**
 * A feed's headlines, one at a time: every flip of the start screen's ticker slides the next one
 * up, Windows Phone news style. A headline's picture fills the tile behind it. Refreshes a copy
 * older than half an hour whenever Start comes back, and only then. While [unlocked] is false
 * (Metro Plus), downloads nothing and says so.
 */
@Composable
fun FeedTile(tile: ResolvedTile, urls: List<String>, cellSize: Dp, flipped: Boolean, unlocked: Boolean) {
    val context = LocalContext.current
    val m = rememberTileMetrics(cellSize)
    val feeds by FeedStore.feeds.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(urls, unlocked) {
        if (!unlocked) return@LaunchedEffect
        // The feeds download side by side; each keeps its own age and retry time.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { urls.forEach { launch { FeedStore.refresh(context, it) } } }
    }
    val copies = urls.map { feeds[it] }
    val merged = remember(copies) { FeedTiles.merge(copies.map { it?.feed?.items.orEmpty() }) }
    val cached = FeedTiles.summary(copies)
    // Each flip moves on one headline; the first shows the newest.
    var step by remember(urls) { mutableIntStateOf(0) }
    var lastFlipped by remember { mutableStateOf(flipped) }
    LaunchedEffect(flipped) {
        if (flipped != lastFlipped) step++
        lastFlipped = flipped
    }
    val items = if (unlocked) merged else emptyList()
    val item = items.getOrNull(FeedTiles.index(step, items.size))
    val id = tile.spec.id
    DisposableEffect(id, item?.link) {
        FeedTiles.setShown(id, item?.link)
        onDispose { FeedTiles.setShown(id, null) }
    }
    FeedFace(tile.spec.size, tile.label, item, cached, unlocked, m)
}

/** The tile without the store and the ticker, for previews and tests. */
@Composable
internal fun FeedFace(
    size: TileSize,
    label: String,
    item: FeedItem?,
    cached: CachedFeed?,
    unlocked: Boolean,
    m: TileMetrics,
    now: Long = System.currentTimeMillis(),
    picture: @Composable (url: String?, width: Int, height: Int) -> ImageBitmap? = { url, w, h -> rememberFeedImage(url, w, h) },
) {
    val white = Metro.colors.onAccent
    // The tile's own surface: its colour, its transparency, or none when "show background" is off.
    Box(Modifier.fillMaxSize().tileSurface()) {
        when {
            size == TileSize.SMALL -> FeedGlyph(m.dp(40f), Modifier.align(Alignment.Center))
            !unlocked -> Notice("unlock to use", m)
            item == null -> {
                FeedGlyph(m.dp(36f), Modifier.align(Alignment.TopStart).padding(start = m.dp(8f), top = m.dp(8f)))
                val text = when {
                    cached?.feed != null -> "nothing new"
                    cached?.error != null -> cached.error
                    else -> "loading…"
                }
                MetroText(
                    text, MetroType.style(m.text(17f), FontWeight.Light), color = white, maxLines = 2,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = m.dp(8f), end = m.dp(8f), bottom = m.dp(26f)),
                )
            }
            else -> {
                val motion = LocalMotion.current
                AnimatedContent(
                    targetState = item,
                    transitionSpec = {
                        val ms = if (motion.flipsEnabled) 450 else 0
                        (slideInVertically(tween(ms)) { it / 3 } + fadeIn(tween(ms))) togetherWith
                            (slideOutVertically(tween(ms)) { -it / 3 } + fadeOut(tween(ms)))
                    },
                    label = "feed headline",
                ) { shown -> Headline(size, shown, m, now, picture) }
            }
        }
        if (size != TileSize.SMALL) TileLabel(label, m, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun Notice(text: String, m: TileMetrics) {
    Box(Modifier.fillMaxSize()) {
        MetroText(
            text, MetroType.style(m.text(16f)), color = Metro.colors.onAccent, maxLines = 2,
            modifier = Modifier.align(Alignment.TopStart).padding(start = m.dp(8f), top = m.dp(8f), end = m.dp(8f)),
        )
    }
}

/** One headline: its picture behind (wide and large tiles, and medium ones), the title and who and when. */
@Composable
private fun Headline(
    size: TileSize,
    item: FeedItem,
    m: TileMetrics,
    now: Long,
    loadPicture: @Composable (url: String?, width: Int, height: Int) -> ImageBitmap?,
) {
    val white = Metro.colors.onAccent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val picture = loadPicture(item.image, widthPx, heightPx)
        if (picture != null) {
            Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Keeps the headline legible on any picture.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.05f), 0.45f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.8f)),
                ),
            )
        }
        val titleSize = when (size) {
            TileSize.LARGE -> 22f
            TileSize.WIDE -> 18f
            else -> 15f
        }
        val lines = when (size) {
            TileSize.LARGE -> 5
            TileSize.WIDE -> 3
            else -> 4
        }
        val byline = FeedTiles.byline(item, now)
        Column(
            Modifier.align(if (picture != null) Alignment.BottomStart else Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = m.dp(8f), end = m.dp(8f), top = m.dp(6f), bottom = if (picture != null) m.dp(24f) else 0.dp),
        ) {
            MetroText(item.title, MetroType.style(m.text(titleSize)), color = white, maxLines = lines)
            if (byline.isNotEmpty() && size != TileSize.MEDIUM) {
                MetroText(byline, MetroType.style(m.text(12.5f)), color = white.copy(alpha = 0.78f), maxLines = 1)
            }
        }
    }
}

/** The picture at [url] at about [width]x[height] pixels, once it has downloaded; null meanwhile and when there is none. */
@Composable
private fun rememberFeedImage(url: String?, width: Int, height: Int): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, url, width, height) {
        value = if (url == null || width <= 0 || height <= 0) {
            null
        } else {
            withContext(Dispatchers.IO) { FeedStore.image(context.applicationContext, url, width, height)?.asImageBitmap() }
        }
    }
    return image
}

/** The feed symbol: a dot and two arcs. */
@Composable
internal fun FeedGlyph(size: Dp, modifier: Modifier = Modifier) {
    val color = Metro.colors.onAccent
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.13f
        drawCircle(color, radius = s * 0.11f, center = Offset(s * 0.17f, s * 0.83f))
        for (r in listOf(0.42f, 0.72f)) {
            val radius = s * r
            drawArc(
                color, startAngle = 270f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(s * 0.06f - radius, s * 0.94f - radius), size = Size(radius * 2, radius * 2),
                style = Stroke(stroke),
            )
        }
    }
}
