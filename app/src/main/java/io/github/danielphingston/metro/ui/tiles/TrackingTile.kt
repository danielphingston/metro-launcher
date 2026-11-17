package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.live.tracking.ParcelStage
import io.github.danielphingston.metro.live.tracking.TrackImages
import io.github.danielphingston.metro.live.tracking.TrackProgress
import io.github.danielphingston.metro.live.tracking.TrackingFeed
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.settings.SettingsActivity
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.start.AppIcon
import io.github.danielphingston.metro.ui.start.FlipCard
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/**
 * Which OTP is shown, by notification key; none by default. In memory only: it hides again when the
 * start screen stops (screen off, another app), when another page is shown and in edit mode.
 */
object TrackingReveal {
    var key by mutableStateOf<String?>(null)
        private set

    fun toggle(rowKey: String) {
        key = if (key == rowKey) null else rowKey
    }

    fun hide() {
        key = null
    }
}

/**
 * The tracking tile: the rides, food orders and parcels on their way, read from the notifications
 * the listener already receives. It never flips; it recomposes when the listener publishes or, while
 * a line counts down and the start screen is visible, once a minute.
 */
@Composable
fun TrackingTile(
    tile: ResolvedTile,
    live: LiveContent.Tracking?,
    monochrome: Boolean,
    icons: IconCache,
    cellSize: Dp,
    editing: Boolean,
) {
    val m = rememberTileMetrics(cellSize)
    val model = live?.model ?: TrackingModel.EMPTY
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) TrackingReveal.hide() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(editing) { if (editing) TrackingReveal.hide() }
    val face = TrackFace(model, live?.apps.orEmpty(), live?.images.orEmpty(), monochrome, icons, m, editing, tile.label)
    FlipCard(
        showBack = false,
        front = {
            when (tile.spec.size) {
                TileSize.SMALL -> face.Small()
                TileSize.MEDIUM -> face.Medium()
                TileSize.WIDE -> face.Wide()
                TileSize.LARGE -> face.Large()
            }
        },
        back = {},
    )
}

/** Everything one tile face needs, so the per-size layouts below stay short. */
private class TrackFace(
    val model: TrackingModel,
    val apps: Map<String, AppInfo>,
    val images: Map<String, TrackImages>,
    val monochrome: Boolean,
    val icons: IconCache,
    val m: TileMetrics,
    val editing: Boolean,
    val label: String,
) {
    fun app(row: TrackRow): AppInfo? = apps[row.appKey]
    fun appName(row: TrackRow): String = app(row)?.label ?: row.packageName.substringAfterLast('.')
    fun picture(row: TrackRow): Bitmap? = if (row.showPicture) images[row.key]?.picture else null
    fun tracker(row: TrackRow): Bitmap? = if (row.showPicture) images[row.key]?.tracker else null
    fun minutes(row: TrackRow): Int? = TrackingModel.minutesLeft(row.etaAt, model.now)

    @Composable
    fun Small() {
        val white = Metro.colors.onAccent
        val top = model.top
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (model.state != TrackingModel.State.ACTIVE || top == null) {
                PinGlyph(m.dp(34f))
                return@Box
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                app(top)?.let { AppIcon(it, icons, m.dp(34f), monochrome) }
                val text = minutes(top)?.let { TrackingModel.shortEta(it) }
                    ?: if (!top.isLive) "today" else top.shortText
                text?.let {
                    MetroText(it, MetroType.style(m.text(18f), FontWeight.Light), color = white, maxLines = 1, modifier = Modifier.padding(top = m.dp(2f)))
                }
            }
        }
    }

    @Composable
    fun Medium() {
        val white = Metro.colors.onAccent
        val top = model.top
        Box(Modifier.fillMaxSize()) {
            when {
                model.state == TrackingModel.State.LOCKED -> Notice("unlock to use", 16f)
                model.state == TrackingModel.State.NO_ACCESS -> NoAccess(16f)
                top == null -> PinGlyph(m.dp(48f), Modifier.align(Alignment.Center))
                top.isLive -> {
                    Column(Modifier.padding(start = m.dp(8f), top = m.dp(6f), end = m.dp(8f))) {
                        val min = minutes(top)
                        if (min != null) BigEta(min, 44f) else MetroText(top.shortText ?: top.status, MetroType.style(m.text(20f), FontWeight.Light), color = white, maxLines = 2)
                        if (min != null || top.shortText != null) {
                            MetroText(top.status, MetroType.style(m.text(14f)), color = white, maxLines = if (top.otp != null) 1 else 2, modifier = Modifier.padding(top = m.dp(2f)))
                        }
                        top.otp?.let { OtpChip(top, it, 13f, Modifier.padding(top = m.dp(4f))) }
                    }
                    picture(top)?.let { Picture(it, m.dp(40f), Modifier.align(Alignment.TopEnd).padding(top = m.dp(8f), end = m.dp(8f))) }
                    top.progress?.let { Bar(it, tracker(top), stops = false, Modifier.align(Alignment.BottomStart).padding(start = m.dp(8f), end = m.dp(8f), bottom = m.dp(26f))) }
                    Footer(top, more = model.rows.size - 1, showName = false)
                }
                else -> {
                    val parcels = model.parcels
                    Column(Modifier.padding(start = m.dp(8f), top = m.dp(4f), end = m.dp(8f))) {
                        MetroText(parcels.size.toString(), MetroType.style(m.text(44f), FontWeight.Light), color = white, maxLines = 1)
                        MetroText(
                            if (parcels.size == 1) TrackingModel.stageText(top.stage ?: ParcelStage.ARRIVING_TODAY) else "arriving today",
                            MetroType.style(m.text(14f)), color = white, maxLines = 1,
                        )
                        val second = if (top.status != TrackingModel.stageText(top.stage ?: ParcelStage.ARRIVING_TODAY)) top.status else appName(top)
                        MetroText(second, MetroType.style(m.text(13f)), color = white.copy(alpha = 0.78f), maxLines = 2)
                    }
                    picture(top)?.let { Picture(it, m.dp(40f), Modifier.align(Alignment.TopEnd).padding(top = m.dp(8f), end = m.dp(8f))) }
                    Footer(top, more = 0, showName = false)
                }
            }
        }
    }

    @Composable
    fun Wide() {
        val top = model.top
        Box(Modifier.fillMaxSize()) {
            when {
                model.state == TrackingModel.State.LOCKED -> Notice("unlock metro plus to use", 18f)
                model.state == TrackingModel.State.NO_ACCESS -> NoAccess(18f)
                top == null -> Empty()
                top.isLive -> {
                    LiveHeader(top, picture = 72f, large = false, Modifier.padding(start = m.dp(8f), top = m.dp(8f), end = m.dp(10f)))
                    top.progress?.let { Bar(it, tracker(top), stops = false, Modifier.align(Alignment.BottomStart).padding(start = m.dp(10f), end = m.dp(10f), bottom = m.dp(26f))) }
                    Footer(top, more = model.rows.size - 1, showName = true)
                }
                else -> {
                    ParcelList(model.parcels, max = 2, Modifier.padding(start = m.dp(10f), top = m.dp(6f), end = m.dp(10f)))
                    TileLabel(label, m, Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }

    @Composable
    fun Large() {
        val top = model.top
        Box(Modifier.fillMaxSize()) {
            when {
                model.state == TrackingModel.State.LOCKED -> Notice("unlock metro plus to use", 22f)
                model.state == TrackingModel.State.NO_ACCESS -> NoAccess(22f)
                top == null -> Empty()
                top.isLive -> {
                    Column(Modifier.fillMaxSize().padding(start = m.dp(10f), top = m.dp(8f), end = m.dp(10f), bottom = m.dp(24f))) {
                        LiveHeader(top, picture = 88f, large = true)
                        top.progress?.let {
                            Spacer(Modifier.height(m.dp(14f)))
                            Bar(it, tracker(top), stops = true)
                        }
                        val rest = model.rows.drop(1)
                        if (rest.isNotEmpty()) {
                            Spacer(Modifier.height(m.dp(10f)))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Metro.colors.onAccent.copy(alpha = 0.3f)))
                            rest.take(4).forEach { ListRow(it) }
                        }
                    }
                    Footer(top, more = 0, showName = true)
                }
                else -> {
                    ParcelList(model.parcels, max = 6, Modifier.padding(start = m.dp(10f), top = m.dp(8f), end = m.dp(10f)))
                    TileLabel(label, m, Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.NoAccess(size: Float) = Notice("turn on notification access", size)

    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.Notice(text: String, size: Float) {
        MetroText(
            text,
            MetroType.style(m.text(size), FontWeight.SemiBold),
            color = Metro.colors.onAccent,
            maxLines = 3,
            modifier = Modifier.align(Alignment.TopStart).padding(m.dp(8f)),
        )
        TileLabel(label, m, Modifier.align(Alignment.BottomStart))
    }

    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.Empty() {
        val white = Metro.colors.onAccent
        Column(Modifier.align(Alignment.TopStart).padding(start = m.dp(10f), top = m.dp(8f), end = m.dp(64f))) {
            MetroText("nothing on the way", MetroType.style(m.text(22f), FontWeight.Light), color = white, maxLines = 1)
            MetroText("rides, food and parcels show up here", MetroType.style(m.text(13f)), color = white.copy(alpha = 0.78f), maxLines = 2)
        }
        PinGlyph(m.dp(40f), Modifier.align(Alignment.TopEnd).padding(top = m.dp(12f), end = m.dp(12f)))
        TileLabel(label, m, Modifier.align(Alignment.BottomStart))
    }

    /** The tile name on the left; the app (icon, and its name on wide tiles) and "+N" more on the right. */
    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.Footer(top: TrackRow, more: Int, showName: Boolean) {
        val white = Metro.colors.onAccent
        TileLabel(label, m, Modifier.align(Alignment.BottomStart))
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = m.dp(8f), bottom = m.dp(5f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(5f)),
        ) {
            app(top)?.let { AppIcon(it, icons, m.dp(18f), monochrome) }
            if (showName) MetroText(appName(top), MetroType.style(m.text(12f)), color = white, maxLines = 1)
            if (more > 0) MetroText("+$more", MetroType.style(m.text(12f)), color = white, maxLines = 1)
        }
    }

    /** The top trip: picture, status, ETA, details, plate and OTP. */
    @Composable
    private fun LiveHeader(row: TrackRow, picture: Float, large: Boolean, modifier: Modifier = Modifier) {
        val white = Metro.colors.onAccent
        Row(modifier.fillMaxWidth()) {
            picture(row)?.let {
                Picture(it, m.dp(picture))
                Spacer(Modifier.width(m.dp(10f)))
            }
            Column(Modifier.weight(1f)) {
                MetroText(
                    row.status, MetroType.style(m.text(if (large) 18f else 17f), FontWeight.SemiBold), color = white,
                    maxLines = 2, modifier = Modifier.padding(top = m.dp(2f)),
                )
                if (row.detail.isNotBlank()) {
                    MetroText(row.detail, MetroType.style(m.text(13f)), color = white.copy(alpha = 0.85f), maxLines = if (large) 2 else 1)
                }
                if (row.plate != null || row.otp != null) {
                    Row(Modifier.padding(top = m.dp(5f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(6f))) {
                        row.plate?.let { PlateChip(it, 12f) }
                        row.otp?.let { OtpChip(row, it, 13f) }
                    }
                }
            }
            // The ETA has its own column, so its height never pushes the details down.
            val min = minutes(row)
            if (min != null) {
                Spacer(Modifier.width(m.dp(8f)))
                BigEta(min, if (large) 44f else 40f)
            } else {
                row.shortText?.let {
                    Spacer(Modifier.width(m.dp(8f)))
                    MetroText(it, MetroType.style(m.text(22f), FontWeight.Light), color = white, maxLines = 1)
                }
            }
        }
    }

    /** Another trip or parcel under the top one on the large tile. */
    @Composable
    private fun ListRow(row: TrackRow) {
        val white = Metro.colors.onAccent
        Row(Modifier.fillMaxWidth().padding(top = m.dp(7f)), verticalAlignment = Alignment.CenterVertically) {
            Thumb(row, 38f)
            Spacer(Modifier.width(m.dp(8f)))
            Column(Modifier.weight(1f)) {
                MetroText("${appName(row)} · ${row.status}", MetroType.style(m.text(14f)), color = white, maxLines = 1)
                if (row.plate != null || row.otp != null) {
                    Row(Modifier.padding(top = m.dp(2f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(5f))) {
                        row.plate?.let { PlateChip(it, 11f) }
                        row.otp?.let { OtpChip(row, it, 11f) }
                    }
                } else if (row.detail.isNotBlank()) {
                    MetroText(row.detail, MetroType.style(m.text(12f)), color = white.copy(alpha = 0.78f), maxLines = 1)
                }
            }
            minutes(row)?.let { MetroText(TrackingModel.shortEta(it), MetroType.style(m.text(22f), FontWeight.Light), color = white, maxLines = 1) }
        }
    }

    @Composable
    private fun ParcelList(parcels: List<TrackRow>, max: Int, modifier: Modifier) {
        val white = Metro.colors.onAccent
        Column(modifier.fillMaxWidth()) {
            val header = if (parcels.size == 1) TrackingModel.stageText(parcels[0].stage ?: ParcelStage.ARRIVING_TODAY) else "${parcels.size} arriving today"
            MetroText(header, MetroType.style(m.text(17f), FontWeight.SemiBold), color = white, maxLines = 1)
            parcels.take(max).forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(white.copy(alpha = 0.25f)))
                Row(Modifier.fillMaxWidth().padding(vertical = m.dp(5f)), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(row, 40f)
                    Spacer(Modifier.width(m.dp(9f)))
                    Column(Modifier.weight(1f)) {
                        val stage = TrackingModel.stageText(row.stage ?: ParcelStage.ARRIVING_TODAY)
                        MetroText(if (row.status == stage) stage else row.status, MetroType.style(m.text(14f)), color = white, maxLines = 1)
                        val second = listOf(row.detail, appName(row)).filter { it.isNotBlank() }.joinToString(" · ")
                        MetroText(second, MetroType.style(m.text(12.5f)), color = white.copy(alpha = 0.78f), maxLines = 1)
                    }
                }
            }
        }
    }

    /** The row's picture, else its app's icon in the same square. */
    @Composable
    private fun Thumb(row: TrackRow, size: Float) {
        val picture = picture(row)
        if (picture != null) {
            Picture(picture, m.dp(size))
        } else {
            Box(Modifier.size(m.dp(size)), contentAlignment = Alignment.Center) {
                app(row)?.let { AppIcon(it, icons, m.dp(size * 0.7f), monochrome) }
            }
        }
    }

    @Composable
    private fun Picture(bitmap: Bitmap, size: Dp, modifier: Modifier = Modifier) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size))
    }

    @Composable
    private fun BigEta(minutes: Int, size: Float) {
        val white = Metro.colors.onAccent
        val (number, unit) = TrackingModel.bigEta(minutes)
        Row(verticalAlignment = Alignment.Bottom) {
            MetroText(number, MetroType.style(m.text(size), FontWeight.Light), color = white, maxLines = 1)
            if (unit.isNotEmpty()) {
                MetroText(unit, MetroType.style(m.text(size * 0.42f), FontWeight.Light), color = white, maxLines = 1, modifier = Modifier.padding(start = m.dp(3f), bottom = m.dp(size * 0.12f)))
            }
        }
    }

    @Composable
    private fun PlateChip(plate: String, size: Float) {
        Box(Modifier.background(Metro.colors.onAccent).padding(horizontal = m.dp(5f))) {
            MetroText(plate, MetroType.style(m.text(size), FontWeight.SemiBold), color = Metro.colors.accent, maxLines = 1)
        }
    }

    /** The code, hidden until tapped; a second tap hides it. Taps do nothing in edit mode. */
    @Composable
    private fun OtpChip(row: TrackRow, otp: String, size: Float, modifier: Modifier = Modifier) {
        val white = Metro.colors.onAccent
        val shown = TrackingReveal.key == row.key
        val interaction = remember { MutableInteractionSource() }
        Row(
            modifier
                .border(1.dp, white.copy(alpha = 0.8f))
                .then(
                    if (editing) {
                        Modifier
                    } else {
                        Modifier.clickable(interaction, indication = null, role = Role.Button, onClickLabel = if (shown) "hide code" else "show code") {
                            TrackingReveal.toggle(row.key)
                        }
                    },
                )
                .semantics { contentDescription = if (shown) "code ${otp.toList().joinToString(" ")}" else "code hidden" }
                .padding(horizontal = m.dp(6f), vertical = m.dp(1f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(5f)),
        ) {
            MetroText(
                if (shown) otp else "•".repeat(otp.length.coerceIn(4, 6)),
                MetroType.style(m.text(size), FontWeight.SemiBold).copy(letterSpacing = 0.12.em),
                color = white,
                maxLines = 1,
            )
            EyeGlyph(m.dp(size + 2f), crossed = shown)
        }
    }

    /** The app's progress bar: segments with small gaps, Android 16's points as stops on the large tile, and its tracker icon riding on top. */
    @Composable
    private fun Bar(progress: TrackProgress, tracker: Bitmap?, stops: Boolean, modifier: Modifier = Modifier) {
        val white = Metro.colors.onAccent
        val accent = Metro.colors.accent
        val trackerSize = m.dp(18f)
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val width = maxWidth
            Column {
                if (tracker != null) {
                    val image = remember(tracker) { tracker.asImageBitmap() }
                    val x = (width * progress.fraction - trackerSize / 2).coerceIn(0.dp, (width - trackerSize).coerceAtLeast(0.dp))
                    Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.offset(x = x).size(trackerSize))
                }
                Canvas(Modifier.fillMaxWidth().height(if (stops) m.dp(13f) else m.dp(5f))) {
                    val h = m.dp(5f).toPx()
                    val top = (size.height - h) / 2
                    val gap = if (progress.segmentEnds.isEmpty()) 0f else 3.dp.toPx()
                    val ends = progress.segmentEnds + 1f
                    var start = 0f
                    for (end in ends) {
                        val x0 = start * size.width + if (start > 0f) gap / 2 else 0f
                        val x1 = end * size.width - if (end < 1f) gap / 2 else 0f
                        if (x1 > x0) {
                            drawRect(white.copy(alpha = 0.35f), Offset(x0, top), Size(x1 - x0, h))
                            val fill = minOf(x1, progress.fraction * size.width)
                            if (fill > x0) drawRect(white, Offset(x0, top), Size(fill - x0, h))
                        }
                        start = end
                    }
                    if (stops) {
                        val r = size.height / 2 - 1.dp.toPx()
                        for (p in progress.pointFractions) {
                            val c = Offset((p * size.width).coerceIn(r + 1f, size.width - r - 1f), size.height / 2)
                            val reached = p <= progress.fraction
                            drawCircle(if (reached) white else accent, r, c)
                            drawCircle(white, r, c, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EyeGlyph(size: Dp, crossed: Boolean) {
        val white = Metro.colors.onAccent
        val accent = Metro.colors.accent
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val eye = Path().apply {
                moveTo(w * 0.06f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.0f, w * 0.94f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 1.0f, w * 0.06f, h * 0.5f)
                close()
            }
            drawPath(eye, white, style = Stroke(w * 0.09f))
            drawCircle(white, w * 0.13f, Offset(w / 2, h / 2))
            if (crossed) {
                drawLine(accent, Offset(w * 0.15f, h * 0.88f), Offset(w * 0.85f, h * 0.12f), w * 0.2f, StrokeCap.Round)
                drawLine(white, Offset(w * 0.15f, h * 0.88f), Offset(w * 0.85f, h * 0.12f), w * 0.09f, StrokeCap.Round)
            }
        }
    }

    @Composable
    fun PinGlyph(size: Dp, modifier: Modifier = Modifier) {
        val white = Metro.colors.onAccent
        Canvas(modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val pin = Path().apply {
                moveTo(w * 0.5f, h * 0.92f)
                cubicTo(w * 0.28f, h * 0.68f, w * 0.2f, h * 0.55f, w * 0.2f, h * 0.4f)
                cubicTo(w * 0.2f, h * 0.2f, w * 0.34f, h * 0.08f, w * 0.5f, h * 0.08f)
                cubicTo(w * 0.66f, h * 0.08f, w * 0.8f, h * 0.2f, w * 0.8f, h * 0.4f)
                cubicTo(w * 0.8f, h * 0.55f, w * 0.72f, h * 0.68f, w * 0.5f, h * 0.92f)
                close()
            }
            drawPath(pin, white, style = Stroke(w * 0.055f))
            drawCircle(white, w * 0.09f, Offset(w * 0.5f, h * 0.4f), style = Stroke(w * 0.055f))
        }
    }
}

internal object TrackingTiles {
    /**
     * A tap opens what the tile leads with in its own app (the notification's tracking screen), else
     * that app; without notification access, the setting that turns it on.
     */
    fun activate(context: Context, bounds: androidx.compose.ui.geometry.Rect?, launcher: AppLauncher) {
        if (!NotificationAccess.isGranted(context)) {
            launcher.start(SettingsActivity.intent(context, SettingsPage.TILES), bounds)
            return
        }
        val snapshot = TrackingFeed.snapshot.value
        val parcels = (context.applicationContext as? MetroApplication)?.parcels?.entries?.value.orEmpty()
        val top = TrackingModel.of(snapshot.items, parcels, true, true, System.currentTimeMillis()).top ?: return
        val appIntent = {
            try {
                context.packageManager.getLaunchIntentForPackage(top.packageName)
            } catch (_: RuntimeException) {
                null
            }
        }
        val intent = snapshot.intents[top.key]
        if (intent == null) {
            appIntent()?.let { launcher.start(it, bounds) }
            return
        }
        launcher.open(bounds) { _, options ->
            MeTiles.send(context, intent, options) || appIntent()?.let { i ->
                runCatching { context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), options) }.isSuccess
            } == true
        }
    }
}
