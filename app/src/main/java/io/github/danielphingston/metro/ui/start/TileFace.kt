package io.github.danielphingston.metro.ui.start

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileIcon
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.CalendarEvent
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.tiles.NowPlayingBack
import io.github.danielphingston.metro.ui.tiles.NowPlayingFront
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.tileSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What a live tile shows, derived from the app it represents. */
sealed interface LiveContent {
    data class Clock(val state: ClockState, val is24Hour: Boolean) : LiveContent
    data class Calendar(val now: Long, val events: List<CalendarEvent>, val is24Hour: Boolean) : LiveContent
    data class Notifications(val data: AppNotifications, val showContent: Boolean) : LiveContent
    data class NowPlaying(val info: io.github.danielphingston.metro.live.NowPlaying) : LiveContent

    /** Whether the tile has a second face to flip to. Small tiles never flip. */
    fun hasBack(size: TileSize): Boolean = size != TileSize.SMALL && when (this) {
        is Clock -> state.nextAlarm != null
        is Calendar -> events.isNotEmpty()
        is Notifications -> showContent && data.previews.isNotEmpty()
        is NowPlaying -> true
    }
}

/**
 * Tile typography is sized to the tile, not the user's font scale: tiles have a fixed size
 * and text that grows with font scale would just be clipped.
 */
internal class TileMetrics(val cellSize: Dp, private val density: androidx.compose.ui.unit.Density) {
    private val scale = cellSize.value / 96f
    fun text(base: Float): TextUnit = with(density) { (base * scale).dp.toSp() }
    fun dp(base: Float): Dp = (base * scale).dp
}

@Composable
fun TileFace(
    app: AppInfo,
    size: TileSize,
    live: LiveContent?,
    flipped: Boolean,
    monochromeIcons: Boolean,
    icons: IconCache,
    cellSize: Dp,
) {
    val metrics = TileMetrics(cellSize, LocalDensity.current)
    FlipCard(
        showBack = flipped && live?.hasBack(size) == true,
        front = { Front(app, size, live, monochromeIcons, icons, metrics) },
        // The back face stays on screen while the card flips back, which can outlast the content
        // (a dismissed notification): the live content is null by then.
        back = { live?.let { Back(app, size, it, monochromeIcons, icons, metrics) } },
    )
}

/**
 * A tile surface that flips around its horizontal axis between two faces, the Windows Phone
 * live tile animation. Any tile kind can use it. Without a [background] the surface is the
 * shared [tileSurface]: accent over the start background, with the user's transparency.
 */
@Composable
fun FlipCard(
    showBack: Boolean,
    modifier: Modifier = Modifier,
    background: Color = Color.Unspecified,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (showBack) 180f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "tileFlip",
    )
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationX = rotation
                cameraDistance = 16f * density
            }
            .then(if (background.isSpecified) Modifier.background(background) else Modifier.tileSurface()),
    ) {
        if (rotation <= 90f) {
            front()
        } else {
            // Counter-rotate so the back face isn't drawn mirrored.
            Box(Modifier.fillMaxSize().graphicsLayer { rotationX = 180f }) { back() }
        }
    }
}

@Composable
private fun Front(
    app: AppInfo,
    size: TileSize,
    live: LiveContent?,
    monochromeIcons: Boolean,
    icons: IconCache,
    m: TileMetrics,
) {
    when (live) {
        is LiveContent.Clock -> ClockFront(app, size, live, m)
        is LiveContent.Calendar -> CalendarFront(app, size, live, m)
        is LiveContent.NowPlaying -> NowPlayingFront(app, size, live.info, m)
        else -> IconFront(app, size, (live as? LiveContent.Notifications)?.data?.count ?: 0, monochromeIcons, icons, m)
    }
}

@Composable
private fun Back(
    app: AppInfo,
    size: TileSize,
    live: LiveContent,
    monochromeIcons: Boolean,
    icons: IconCache,
    m: TileMetrics,
) {
    when (live) {
        is LiveContent.Clock -> ClockBack(app, live, m)
        is LiveContent.Calendar -> CalendarBack(app, size, live, m)
        is LiveContent.Notifications -> NotificationsBack(app, size, live.data, monochromeIcons, icons, m)
        is LiveContent.NowPlaying -> NowPlayingBack(app, size, live.info, m)
    }
}

@Composable
internal fun rememberIcon(icons: IconCache, app: AppInfo, sizePx: Int, monochrome: Boolean): TileIcon? {
    val cached = icons.peek(app, sizePx, monochrome)
    val loaded by produceState(cached, app.key, sizePx, monochrome, icons.generation) {
        // Keep showing the previous icon until the new one is ready.
        value = icons.peek(app, sizePx, monochrome) ?: withContext(Dispatchers.Default) { icons.load(app, sizePx, monochrome) }
    }
    return loaded
}

@Composable
internal fun AppIcon(app: AppInfo, icons: IconCache, size: Dp, monochrome: Boolean, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon = rememberIcon(icons, app, px, monochrome)
    Box(modifier.size(size)) {
        icon?.let { Image(it.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
internal fun TileLabel(text: String, m: TileMetrics, modifier: Modifier = Modifier) {
    MetroText(
        text,
        MetroType.style(m.text(12f)),
        color = Metro.colors.onAccent,
        maxLines = 1,
        modifier = modifier.padding(start = m.dp(8f), bottom = m.dp(6f), end = m.dp(8f)),
    )
}

@Composable
private fun IconFront(app: AppInfo, size: TileSize, count: Int, monochrome: Boolean, icons: IconCache, m: TileMetrics) {
    val white = Metro.colors.onAccent
    Box(Modifier.fillMaxSize()) {
        if (size == TileSize.SMALL) {
            AppIcon(app, icons, m.dp(40f), monochrome, Modifier.align(Alignment.Center))
            if (count > 0) {
                MetroText(
                    count.coerceAtMost(99).toString(),
                    MetroType.style(m.text(12f), FontWeight.SemiBold),
                    color = white,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = m.dp(6f), bottom = m.dp(3f)),
                )
            }
            return@Box
        }
        val iconSize = if (size == TileSize.LARGE) m.dp(80f) else m.dp(64f)
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, icons, iconSize, monochrome)
            if (count > 0) {
                Spacer(Modifier.width(m.dp(8f)))
                MetroText(
                    count.coerceAtMost(99).toString(),
                    MetroType.style(m.text(if (size == TileSize.LARGE) 48f else 36f), FontWeight.Light),
                    color = white,
                )
            }
        }
        TileLabel(app.label, m, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun NotificationsBack(
    app: AppInfo,
    size: TileSize,
    data: AppNotifications,
    monochrome: Boolean,
    icons: IconCache,
    m: TileMetrics,
) {
    val white = Metro.colors.onAccent
    val shown = if (size == TileSize.LARGE) data.previews.take(3) else data.previews.take(1)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(m.dp(8f)),
            verticalArrangement = Arrangement.spacedBy(m.dp(6f)),
        ) {
            shown.forEach { preview ->
                Column {
                    if (preview.title.isNotBlank()) {
                        MetroText(preview.title, MetroType.style(m.text(17f), FontWeight.SemiBold), color = white, maxLines = 1)
                    }
                    MetroText(
                        preview.text,
                        MetroType.style(m.text(14f)),
                        color = white,
                        maxLines = if (size == TileSize.LARGE) 2 else 3,
                    )
                }
            }
        }
        TileLabel(app.label, m, Modifier.align(Alignment.BottomStart))
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = m.dp(7f), bottom = m.dp(5f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app, icons, m.dp(20f), monochrome)
            Spacer(Modifier.width(m.dp(3f)))
            MetroText(data.count.coerceAtMost(99).toString(), MetroType.style(m.text(14f)), color = white)
        }
    }
}

private fun zoned(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
private fun format(pattern: String, millis: Long): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(zoned(millis)).lowercase(Locale.getDefault())
private fun time(millis: Long, is24Hour: Boolean) = format(if (is24Hour) "H:mm" else "h:mm", millis)

@Composable
private fun ClockFront(app: AppInfo, size: TileSize, live: LiveContent.Clock, m: TileMetrics) {
    val white = Metro.colors.onAccent
    val now = live.state.now
    Box(Modifier.fillMaxSize()) {
        when (size) {
            TileSize.SMALL -> MetroText(
                time(now, live.is24Hour),
                MetroType.style(m.text(24f), FontWeight.Light),
                color = white,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> {
                Column(Modifier.padding(start = m.dp(8f), top = m.dp(2f))) {
                    MetroText(
                        time(now, live.is24Hour),
                        MetroType.style(m.text(if (size == TileSize.MEDIUM) 52f else 64f), FontWeight.Light),
                        color = white,
                        maxLines = 1,
                    )
                    MetroText(format("EEEE", now), MetroType.style(m.text(17f)), color = white, maxLines = 1)
                    MetroText(format("MMMM d", now), MetroType.style(m.text(17f)), color = white, maxLines = 1)
                }
                TileLabel(app.label, m, Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

@Composable
private fun ClockBack(app: AppInfo, live: LiveContent.Clock, m: TileMetrics) {
    val white = Metro.colors.onAccent
    val alarm = live.state.nextAlarm ?: return
    val days = ChronoUnit.DAYS.between(zoned(live.state.now).toLocalDate(), zoned(alarm).toLocalDate())
    val day = when (days) {
        0L -> "today"
        1L -> "tomorrow"
        else -> format("EEEE", alarm)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = m.dp(8f), top = m.dp(6f))) {
            MetroText("next alarm", MetroType.style(m.text(15f)), color = white)
            MetroText(time(alarm, live.is24Hour), MetroType.style(m.text(44f), FontWeight.Light), color = white)
            MetroText(day, MetroType.style(m.text(15f)), color = white)
        }
        TileLabel(app.label, m, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun CalendarFront(app: AppInfo, size: TileSize, live: LiveContent.Calendar, m: TileMetrics) {
    val next = live.events.firstOrNull()
    if (next == null || size == TileSize.SMALL) {
        DateFace(app, size, live.now, m)
        return
    }
    val white = Metro.colors.onAccent
    val shown = if (size == TileSize.LARGE) live.events else listOf(next)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(m.dp(8f)),
            verticalArrangement = Arrangement.spacedBy(m.dp(8f)),
        ) {
            shown.forEach { event ->
                Column {
                    MetroText(event.title, MetroType.style(m.text(17f), FontWeight.SemiBold), color = white, maxLines = 2)
                    event.location?.let { MetroText(it, MetroType.style(m.text(14f)), color = white, maxLines = 1) }
                    MetroText(eventTime(event, live), MetroType.style(m.text(14f)), color = white, maxLines = 1)
                }
            }
        }
        TileLabel(app.label, m, Modifier.align(Alignment.BottomStart))
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = m.dp(7f), bottom = m.dp(2f)),
            verticalAlignment = Alignment.Bottom,
        ) {
            MetroText(format("EEE", live.now), MetroType.style(m.text(13f)), color = white, modifier = Modifier.padding(bottom = m.dp(3f)))
            Spacer(Modifier.width(m.dp(4f)))
            MetroText(format("d", live.now), MetroType.style(m.text(26f), FontWeight.Light), color = white)
        }
    }
}

private fun eventTime(event: CalendarEvent, live: LiveContent.Calendar): String {
    val today = zoned(live.now).toLocalDate()
    val day = zoned(event.begin).toLocalDate()
    val dayText = when (ChronoUnit.DAYS.between(today, day)) {
        0L -> ""
        1L -> "tomorrow "
        else -> format("EEEE ", event.begin)
    }
    if (event.allDay) return "${dayText}all day".trim()
    return "$dayText${time(event.begin, live.is24Hour)}–${time(event.end, live.is24Hour)}"
}

@Composable
private fun CalendarBack(app: AppInfo, size: TileSize, live: LiveContent.Calendar, m: TileMetrics) =
    DateFace(app, size, live.now, m)

@Composable
private fun DateFace(app: AppInfo, size: TileSize, now: Long, m: TileMetrics) {
    val white = Metro.colors.onAccent
    Box(Modifier.fillMaxSize()) {
        if (size == TileSize.SMALL) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                MetroText(format("d", now), MetroType.style(m.text(30f), FontWeight.Light), color = white)
                MetroText(format("EEE", now), MetroType.style(m.text(11f)), color = white)
            }
            return@Box
        }
        Column(Modifier.padding(start = m.dp(8f), top = m.dp(2f))) {
            MetroText(format("d", now), MetroType.style(m.text(64f), FontWeight.Light), color = white)
            MetroText(format("EEEE", now), MetroType.style(m.text(17f)), color = white)
        }
        TileLabel(app.label, m, Modifier.align(Alignment.BottomStart))
    }
}
