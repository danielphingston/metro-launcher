package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.ImageDecode
import io.github.danielphingston.metro.live.PhotoSource
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.settings.Section
import io.github.danielphingston.metro.ui.settings.SettingsActivity
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.tileSurface
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PhotosTiles {
    /**
     * Settings while no photos are chosen, so the tile explains itself; otherwise the gallery
     * app, the default one if known.
     */
    fun intent(context: Context, defaults: DefaultApps): Intent? {
        // The tile collects the photo list while shown, which keeps this copy current.
        if (PhotoSource.latest.isEmpty()) return Intent(context, SettingsActivity::class.java)
        return defaults.gallery?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
    }
}

/**
 * Photos the user picked, one at a time. Each change of [flipped] slides the next photo up
 * over the current one; nothing animates otherwise.
 */
@Composable
fun PhotosTile(tile: ResolvedTile, cellSize: Dp, flipped: Boolean) {
    val context = LocalContext.current
    val m = rememberTileMetrics(cellSize)
    val size = tile.spec.size
    val uris by remember(context) { PhotoSource.uris(context) as Flow<List<String>?> }
        .collectAsStateWithLifecycle<List<String>?>(initialValue = null)
    when {
        uris == null -> Box(Modifier.fillMaxSize().tileSurface())
        uris!!.isEmpty() -> HintFace(tile.label, "choose photos in settings", size, m)
        else -> PhotoStack(tile.label, uris!!, size, cellSize, flipped, m)
    }
}

@Composable
private fun PhotoStack(
    label: String,
    uris: List<String>,
    size: TileSize,
    cellSize: Dp,
    flipped: Boolean,
    m: TileMetrics,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { (cellSize * size.width).roundToPx() }
    val heightPx = with(density) { (cellSize * size.height).roundToPx() }

    // Only the photo on show and, during the slide, the one arriving are held in memory.
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    var cursor by remember { mutableIntStateOf(0) }
    var lastFlipped by remember { mutableStateOf(flipped) }
    val slide = remember { Animatable(1f) }
    val unreadable = remember { mutableSetOf<String>() }

    // The tile's size is part of the key, so resizing reloads the photo at the new resolution.
    LaunchedEffect(uris, widthPx, heightPx) {
        val first = loadFrom(context, uris, cursor, widthPx, heightPx, unreadable)
        first?.let {
            cursor = it.index
            incoming = null
            current = it.bitmap
        }
        forgetUnreadable(context, unreadable)
    }

    LaunchedEffect(flipped) {
        if (flipped == lastFlipped) return@LaunchedEffect
        lastFlipped = flipped
        if (current == null || uris.size < 2) return@LaunchedEffect
        // A flip that interrupted a slide settles it first.
        incoming?.let { current = it }
        incoming = null
        val next = loadFrom(context, uris, cursor + 1, widthPx, heightPx, unreadable)
        if (next != null && next.index != cursor) {
            cursor = next.index
            incoming = next.bitmap
            slide.snapTo(0f)
            slide.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
            current = next.bitmap
            incoming = null
        }
        forgetUnreadable(context, unreadable)
    }

    Box(Modifier.fillMaxSize().background(Metro.colors.accent).clipToBounds()) {
        current?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        incoming?.let {
            Image(
                it,
                null,
                Modifier.fillMaxSize().graphicsLayer { translationY = (1f - slide.value) * this.size.height },
                contentScale = ContentScale.Crop,
            )
        }
        if (size != TileSize.SMALL) ScrimmedLabel(label, m)
    }
}

private class LoadedPhoto(val index: Int, val bitmap: ImageBitmap)

/**
 * Decodes the first readable photo at or after [from], wrapping around. Photos that fail are
 * skipped; those whose access is gone (see [PhotoSource.isAccessLost]) are added to [unreadable].
 */
private suspend fun loadFrom(
    context: Context,
    uris: List<String>,
    from: Int,
    widthPx: Int,
    heightPx: Int,
    unreadable: MutableSet<String>,
): LoadedPhoto? = withContext(Dispatchers.IO) {
    for (step in uris.indices) {
        val index = (from + step) % uris.size
        val uri = uris[index]
        if (uri in unreadable) continue
        try {
            return@withContext LoadedPhoto(index, ImageDecode.decode(context, Uri.parse(uri), widthPx, heightPx).asImageBitmap())
        } catch (e: Exception) {
            // Only lost access forgets the photo; a missing file or I/O error may be temporary.
            if (PhotoSource.isAccessLost(e)) unreadable += uri
        }
    }
    null
}

private suspend fun forgetUnreadable(context: Context, unreadable: Set<String>) {
    if (unreadable.isNotEmpty()) PhotoSource.remove(context, unreadable.toList())
}

/** Section in Metro settings: choose the photos the photos tile shows. */
@Composable
fun PhotosSettingsSection() {
    val context = LocalContext.current
    val colors = Metro.colors
    val scope = rememberCoroutineScope()
    val count = remember(context) { PhotoSource.uris(context) }
        .collectAsStateWithLifecycle(initialValue = emptyList()).value.size
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PhotoSource.MAX_PHOTOS),
    ) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            fun release(uri: Uri) = runCatching {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // A photo whose grant can't be kept would stop loading later, so don't save it.
            val chosen = PhotoSource.keepPersisted(picked.map { it.toString() }) {
                runCatching {
                    resolver.takePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
            }
            if (chosen.isEmpty()) return@launch
            val previous = PhotoSource.current(context)
            PhotoSource.save(context, chosen)
            (previous - chosen.toSet()).forEach { release(Uri.parse(it)) }
        }
    }

    Section("photos")
    MetroText(
        "The photos tile shows pictures you choose here. Metro only sees the photos you pick: " +
            "it never browses your gallery, and nothing leaves your device.",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(10.dp))
    MetroButton("choose photos", onClick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    })
    Spacer(Modifier.height(8.dp))
    MetroText(
        if (count == 0) "No photos chosen" else if (count == 1) "1 photo chosen" else "$count photos chosen",
        MetroType.body,
    )
}
