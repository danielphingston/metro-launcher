package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.PowerManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiView
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.api.ApiAddress
import io.github.danielphingston.metro.live.api.ApiRefresh
import io.github.danielphingston.metro.live.api.ApiRender
import io.github.danielphingston.metro.live.api.ApiRendered
import io.github.danielphingston.metro.live.api.ApiRequest
import io.github.danielphingston.metro.live.api.ApiSecrets
import io.github.danielphingston.metro.live.api.ApiStore
import io.github.danielphingston.metro.live.api.CachedApi
import io.github.danielphingston.metro.live.api.FormulaContext
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.tileSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Helpers shared by the API tile, its expanded view and its editor. */
internal object ApiTiles {
    /** The rows a tile of [size] shows from its list. */
    /** At most this many rows on a tile of [size]; it shows those that fit under its text. */
    fun rows(size: TileSize): Int = when (size) {
        TileSize.LARGE -> 12
        TileSize.WIDE, TileSize.MEDIUM -> 6
        else -> 0
    }

    /** The rows the expanded view shows. */
    const val DETAIL_ROWS = 50

    fun batterySaver(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)

    /** Whether the network in use is metered (mobile data, a hotspot): "Wi-Fi only" live polling pauses on it. */
    fun metered(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered }.getOrDefault(true)

    fun formulaContext(context: Context, now: Long, seed: Long? = null) = FormulaContext(now, is24Hour = DateFormat.is24HourFormat(context), seed = seed)

    /** Opens an https link from the API in the browser; anything else is ignored. */
    fun open(context: Context, link: String?) {
        val url = link?.takeIf { it.startsWith("https://") } ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** "updated 4m ago", "updating…", "stale: no connection". */
    fun status(cached: CachedApi?, now: Long): String = when {
        cached == null || (!cached.hasData && !cached.failing) -> "updating…"
        !cached.hasData -> cached.error?.message() ?: "couldn't update"
        cached.failing -> "stale: ${cached.error?.short ?: "couldn't update"}, from ${FeedTiles.age(cached.fetchedAt, now)} ago"
        else -> "updated " + FeedTiles.age(cached.fetchedAt, now).let { if (it == "now") "just now" else "$it ago" }
    }
}

/** [api]'s header values, read once off the main thread; null until read. Tiles without headers need none. */
@Composable
internal fun rememberApiHeaders(api: TileContent.Api): State<Map<String, String>?> {
    val context = LocalContext.current
    return produceState<Map<String, String>?>(if (api.headers.isEmpty()) emptyMap() else null, api.created, api.headers) {
        if (api.headers.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        val stored = withContext(Dispatchers.IO) { ApiSecrets.of(context).get(api.created) }
        value = api.headers.mapNotNull { name -> stored[name]?.let { name to it } }.toMap()
    }
}

/**
 * The current time for text that changes by itself, moved on only when [changesAt] comes (a
 * countdown's next second or minute), and only while [active]: on screen, with Start showing.
 * No network: the countdown is worked out from the time the API gave.
 */
@Composable
internal fun rememberTickingNow(changesAt: Long?, active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(changesAt, active) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = System.currentTimeMillis()
            val at = changesAt ?: return@repeatOnLifecycle
            delay((at - System.currentTimeMillis()).coerceAtLeast(16))
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * A tile showing data from an API the user set up (see [TileContent.Api]). It refreshes only while
 * Start is on screen (the activity started: the screen on and the launcher showing), when its copy
 * is older than its interval; the next refresh is aligned to the clock's minute so several tiles
 * share one wake. Nothing runs in the background. While [unlocked] is false (Metro Plus) it
 * downloads nothing and says so.
 */
@Composable
fun ApiTile(tile: ResolvedTile, api: TileContent.Api, cellSize: Dp, unlocked: Boolean) {
    val context = LocalContext.current
    val m = rememberTileMetrics(cellSize)
    val headers by rememberApiHeaders(api)
    val request = headers?.let { ApiRequest(api.tile.url, it) }
    val data by ApiStore.data.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(request, api.refreshMinutes, unlocked) {
        if (!unlocked || request == null) return@LaunchedEffect
        ApiStore.load(context, request)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val interval = ApiRefresh.tileInterval(api.refreshMinutes, ApiTiles.batterySaver(context))
                ApiStore.refresh(context, request, interval - ApiRefresh.TOLERANCE_MS, retryMs = ApiRefresh.RETRY_MS)
                val now = System.currentTimeMillis()
                delay(ApiRefresh.nextWake(ApiStore.data.value[request.key], interval, now) - now)
            }
        }
    }
    val cached = request?.let { data[it.key] }
    // On screen at all: a tile scrolled away, or on a page not showing, doesn't tick its countdown.
    var visible by remember { mutableStateOf(true) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { coords ->
            val bounds = coords.boundsInWindow()
            val shown = bounds.width > 0f && bounds.height > 0f
            if (shown != visible) visible = shown
        },
    ) {
        var changesAt by remember { mutableStateOf<Long?>(null) }
        val now = rememberTickingNow(changesAt, visible && unlocked)
        ApiFace(tile.spec.size, tile.label, api.tile, cached, unlocked, m, now, onChangesAt = { changesAt = it })
    }
}

/** The tile without the store, the refresh and the ticker, for previews and tests. */
@Composable
internal fun ApiFace(
    size: TileSize,
    label: String,
    view: ApiView,
    cached: CachedApi?,
    unlocked: Boolean,
    m: TileMetrics,
    now: Long,
    onChangesAt: (Long?) -> Unit = {},
    picture: @Composable (url: String, width: Int, height: Int) -> ImageBitmap? = { url, w, h -> rememberApiImage(url, w, h) },
) {
    val context = LocalContext.current
    val white = Metro.colors.onAccent
    val root = cached?.root
    val rendered = remember(root, view, now, size) {
        if (cached?.hasData == true) ApiRender.render(view, root, ApiTiles.formulaContext(context, now, cached.fetchedAt), ApiTiles.rows(size)) else ApiRendered()
    }
    LaunchedEffect(rendered.changesAt) { onChangesAt(rendered.changesAt) }
    Box(Modifier.fillMaxSize().tileSurface()) {
        when {
            !unlocked -> TopText("unlock to use", m)
            cached?.hasData != true -> {
                ApiGlyph(m.dp(if (size == TileSize.SMALL) 40f else 32f), Modifier.align(if (size == TileSize.SMALL) Alignment.Center else Alignment.TopStart).padding(m.dp(8f)))
                if (size != TileSize.SMALL) {
                    MetroText(
                        cached?.error?.short ?: "loading…", MetroType.style(m.text(16f), FontWeight.Light), color = white, maxLines = 2,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = m.dp(8f), end = m.dp(8f), bottom = m.dp(26f)),
                    )
                }
            }
            // Data came but no field could be worked out: said plainly, not a blank tile.
            rendered.blank && rendered.error != null -> if (size == TileSize.SMALL) {
                ApiGlyph(m.dp(40f), Modifier.align(Alignment.Center))
            } else {
                TopText("can't show this: edit the tile's fields", m)
            }
            size == TileSize.SMALL -> {
                val text = rendered.text(ApiSlot.VALUE).ifEmpty { rendered.text(ApiSlot.TITLE) }
                MetroText(
                    text, MetroType.style(m.text(if (text.length <= 4) 26f else 15f), FontWeight.Light), color = white, maxLines = 2,
                    modifier = Modifier.align(Alignment.Center).padding(m.dp(4f)),
                )
            }
            else -> ApiContent(size, rendered, m, picture)
        }
        if (size != TileSize.SMALL) {
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { TileLabel(label, m) }
                // Old data, kept on screen when a refresh fails (offline, the API down): marked, not hidden.
                if (unlocked && cached?.hasData == true && cached.failing) {
                    MetroText(
                        "stale", MetroType.style(m.text(11f)), color = white.copy(alpha = 0.75f), maxLines = 1,
                        modifier = Modifier.padding(end = m.dp(8f), bottom = m.dp(6f)),
                    )
                }
            }
        }
    }
}

/** A medium, wide or large tile's content: the picture behind, the title, the big value, two lines and list rows. */
@Composable
private fun ApiContent(
    size: TileSize,
    rendered: ApiRendered,
    m: TileMetrics,
    loadPicture: @Composable (url: String, width: Int, height: Int) -> ImageBitmap?,
) {
    val white = Metro.colors.onAccent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val picture = rendered.image?.let { loadPicture(it, widthPx, heightPx) }
        if (picture != null) {
            Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.5f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.8f)),
                ),
            )
        }
        val big = when (size) {
            TileSize.LARGE -> 44f
            TileSize.WIDE -> 36f
            else -> 30f
        }
        Column(
            Modifier.align(if (picture != null && rendered.rows.isEmpty()) Alignment.BottomStart else Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = m.dp(8f), end = m.dp(8f), top = m.dp(6f), bottom = m.dp(24f)),
        ) {
            rendered.text(ApiSlot.TITLE).takeIf { it.isNotEmpty() }?.let {
                MetroText(it, MetroType.style(m.text(if (size == TileSize.LARGE) 20f else 15f)), color = white, maxLines = if (size == TileSize.MEDIUM) 2 else 1)
            }
            rendered.text(ApiSlot.VALUE).takeIf { it.isNotEmpty() }?.let {
                MetroText(it, MetroType.style(m.text(big), FontWeight.Light), color = white, maxLines = 1)
            }
            for (slot in listOf(ApiSlot.LINE1, ApiSlot.LINE2)) {
                rendered.text(slot).takeIf { it.isNotEmpty() }?.let {
                    MetroText(it, MetroType.style(m.text(12.5f)), color = white.copy(alpha = 0.85f), maxLines = 1)
                }
            }
            if (rendered.rows.isNotEmpty()) {
                Spacer(Modifier.height(m.dp(4f)))
                // As many whole rows as fit in the space left.
                FitRows(Modifier.weight(1f, fill = false)) {
                    rendered.rows.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = m.dp(1f))) {
                            MetroText(row.primary, MetroType.style(m.text(14f)), color = white, maxLines = 1, modifier = Modifier.weight(1f))
                            if (row.secondary.isNotEmpty()) {
                                MetroText(row.secondary, MetroType.style(m.text(14f)), color = white.copy(alpha = 0.8f), maxLines = 1, modifier = Modifier.padding(start = m.dp(6f)))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Its children one under another, as many whole ones as fit in the height it is given; the rest are left out. */
@Composable
private fun FitRows(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, c ->
        val loose = c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val placed = ArrayList<androidx.compose.ui.layout.Placeable>()
        var height = 0
        for (m in measurables) {
            val p = m.measure(loose)
            if (c.hasBoundedHeight && height + p.height > c.maxHeight) break
            placed += p
            height += p.height
        }
        val width = if (c.hasBoundedWidth) c.maxWidth else placed.maxOfOrNull { it.width } ?: 0
        layout(width, height) {
            var y = 0
            placed.forEach { it.place(0, y); y += it.height }
        }
    }
}

@Composable
private fun TopText(text: String, m: TileMetrics) {
    Box(Modifier.fillMaxSize()) {
        MetroText(
            text, MetroType.style(m.text(16f)), color = Metro.colors.onAccent, maxLines = 2,
            modifier = Modifier.align(Alignment.TopStart).padding(start = m.dp(8f), top = m.dp(8f), end = m.dp(8f)),
        )
    }
}

/** The picture at [url], once downloaded and decoded at about [width] x [height]; null meanwhile and when there is none. */
@Composable
internal fun rememberApiImage(url: String, width: Int, height: Int): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, url, width, height) {
        // A picture that can't be had or decoded (too big for memory, not a picture) is left out.
        value = if (width <= 0 || height <= 0) null else withContext(Dispatchers.IO) {
            try {
                ApiStore.image(context.applicationContext, url, width, height)?.asImageBitmap()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
        }
    }
    return image
}

/** The API symbol: braces around a dot. */
@Composable
internal fun ApiGlyph(size: Dp, modifier: Modifier = Modifier) {
    val color = Metro.colors.onAccent
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.09f
        fun brace(left: Boolean) {
            val x = if (left) s * 0.3f else s * 0.7f
            val dir = if (left) -1f else 1f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x, s * 0.12f)
                quadraticTo(x + dir * s * 0.12f, s * 0.14f, x + dir * s * 0.1f, s * 0.32f)
                lineTo(x + dir * s * 0.1f, s * 0.42f)
                lineTo(x + dir * s * 0.22f, s * 0.5f)
                lineTo(x + dir * s * 0.1f, s * 0.58f)
                lineTo(x + dir * s * 0.1f, s * 0.68f)
                quadraticTo(x + dir * s * 0.12f, s * 0.86f, x, s * 0.88f)
            }
            drawPath(path, color, style = Stroke(stroke))
        }
        brace(true)
        brace(false)
        drawCircle(color, radius = s * 0.07f, center = Offset(s * 0.5f, s * 0.5f))
    }
}

/**
 * The tile's expanded view, opened by a tap: everything its templates pick, and the whole list.
 * Reads [TileContent.Api.detailUrl]; with live updates on, polls it every
 * [TileContent.Api.liveSeconds] while this view is open and the launcher is in front (screen on),
 * slower in battery saver, paused on a metered network when "Wi-Fi only" is on, backing off after
 * failures. Closing it stops the poll; nothing continues in the background.
 */
@Composable
internal fun ApiDetail(api: TileContent.Api, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val headers by rememberApiHeaders(api)
    val data by ApiStore.data.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // An address with fields of the tile's answer ({species.url}) is worked out from its latest
    // answer: when the tile's refresh brings a new link, the request changes and the view follows it.
    val tileRequest = headers?.let { ApiRequest(api.tile.url, it) }
    val tileCached = tileRequest?.let { data[it.key] }
    val resolved = remember(api.detailUrl, tileCached?.root, tileCached?.hasData) {
        ApiAddress.resolve(api.detailUrl, tileCached?.root, tileCached?.hasData == true, ApiTiles.formulaContext(context, System.currentTimeMillis()))
    }
    val request = headers?.let { h ->
        (resolved as? ApiAddress.Resolved.Url)?.let { ApiRequest(it.url, ApiAddress.headersFor(api.detailUrl, it.url, api.tile.url, h)) }
    }
    if (ApiAddress.usesFields(api.detailUrl)) {
        LaunchedEffect(tileRequest) {
            if (tileRequest == null) return@LaunchedEffect
            ApiStore.load(context, tileRequest)
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ApiStore.refresh(context, tileRequest, ApiRefresh.tileInterval(api.refreshMinutes, ApiTiles.batterySaver(context)) - ApiRefresh.TOLERANCE_MS, retryMs = ApiRefresh.RETRY_MS)
            }
        }
    }
    var live by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(request, api.liveSeconds, api.wifiOnly) {
        if (request == null) return@LaunchedEffect
        ApiStore.load(context, request)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val saver = ApiTiles.batterySaver(context)
            // Opening the view brings it up to the tile's own interval at least.
            ApiStore.refresh(context, request, ApiRefresh.tileInterval(api.refreshMinutes, saver) - ApiRefresh.TOLERANCE_MS, retryMs = ApiRefresh.RETRY_MS)
            var failures = 0
            while (true) {
                val allowed = ApiRefresh.livePolling(api.liveSeconds, api.wifiOnly, ApiTiles.metered(context))
                live = allowed
                paused = api.liveSeconds > 0 && !allowed
                if (!allowed) return@repeatOnLifecycle
                val interval = ApiRefresh.liveInterval(api.liveSeconds, ApiTiles.batterySaver(context))
                delay(ApiRefresh.liveDelay(interval, failures))
                ApiStore.refresh(context, request, maxAgeMs = interval / 2, retryMs = 0)
                failures = if (ApiStore.data.value[request.key]?.failing == true) failures + 1 else 0
            }
        }
    }
    val cached = request?.let { data[it.key] }
    var changesAt by remember { mutableStateOf<Long?>(null) }
    val now = rememberTickingNow(changesAt, true)
    MetroFlyout(onDismiss = onDismiss, paneTitle = api.name.ifBlank { "api" }) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .background(Metro.colors.chrome)
                .clickable(enabled = false, onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        ) {
            val addressNote = when (resolved) {
                is ApiAddress.Resolved.Problem -> resolved.message
                ApiAddress.Resolved.Waiting -> "waiting for the tile's answer to find the address…"
                is ApiAddress.Resolved.Url -> null
            }
            ApiDetailBody(api, cached, now, live, paused, onChangesAt = { changesAt = it }, addressNote = addressNote)
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroButton("edit", onEdit)
                MetroButton("close", onDismiss)
            }
        }
    }
}

/** The expanded view's content, without the poll: for the flyout, the editor's preview and tests. */
@Composable
internal fun ApiDetailBody(
    api: TileContent.Api,
    cached: CachedApi?,
    now: Long,
    live: Boolean,
    paused: Boolean,
    maxRows: Int = ApiTiles.DETAIL_ROWS,
    onChangesAt: (Long?) -> Unit = {},
    /** Why the address (taken from the tile's answer) can't be fetched yet; shown in place of the status. */
    addressNote: String? = null,
    picture: @Composable (url: String, width: Int, height: Int) -> ImageBitmap? = { url, w, h -> rememberApiImage(url, w, h) },
) {
    val context = LocalContext.current
    val colors = Metro.colors
    val rendered = remember(cached?.root, api, now, maxRows) {
        if (cached?.hasData == true) ApiRender.render(api.detail, cached.root, ApiTiles.formulaContext(context, now, cached.fetchedAt), maxRows, fallback = api.tile) else ApiRendered()
    }
    LaunchedEffect(rendered.changesAt) { onChangesAt(rendered.changesAt) }
    Column(Modifier.fillMaxWidth()) {
        MetroText(api.name.ifBlank { "api" }, MetroType.pageTitle, maxLines = 1)
        val status = addressNote ?: buildString {
            append(ApiTiles.status(cached, now))
            if (live) append(" · live, every ${api.liveSeconds} s")
            if (paused) append(" · live paused off Wi-Fi")
        }
        MetroText(status, MetroType.caption, color = colors.subtle, modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        rendered.image?.let { url ->
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(colors.control)) {
                val density = LocalDensity.current
                val w = with(density) { maxWidth.roundToPx() }
                val h = with(density) { maxHeight.roundToPx() }
                picture(url, w, h)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.height(12.dp))
        }
        rendered.text(ApiSlot.TITLE).takeIf { it.isNotEmpty() }?.let { MetroText(it, MetroType.sectionTitle) }
        rendered.text(ApiSlot.VALUE).takeIf { it.isNotEmpty() }?.let { MetroText(it, MetroType.pageTitle, color = colors.accent) }
        for (slot in listOf(ApiSlot.LINE1, ApiSlot.LINE2)) {
            rendered.text(slot).takeIf { it.isNotEmpty() }?.let { MetroText(it, MetroType.body) }
        }
        if (rendered.rows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            rendered.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.image?.let { url ->
                        val px = with(LocalDensity.current) { 44.dp.roundToPx() }
                        Box(Modifier.size(44.dp).background(colors.control)) {
                            picture(url, px, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Spacer(Modifier.size(12.dp))
                    }
                    MetroText(row.primary, MetroType.listItem, maxLines = 2, modifier = Modifier.weight(1f))
                    if (row.secondary.isNotEmpty()) MetroText(row.secondary, MetroType.body, color = colors.subtle, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        if (cached?.hasData == true && rendered.slots.isEmpty() && rendered.rows.isEmpty()) {
            MetroText("nothing to show yet: edit the tile and pick some fields", MetroType.body, color = colors.subtle)
        }
        // A field that can't be worked out stays empty; this says which problem, so edit can fix it.
        if (cached?.hasData == true) rendered.error?.let {
            MetroText("some fields can't be shown: $it", MetroType.caption, color = colors.subtle, modifier = Modifier.padding(top = 12.dp))
        }
        rendered.link?.let { link ->
            Spacer(Modifier.height(16.dp))
            MetroButton("open link", { ApiTiles.open(context, link) })
        }
    }
}
