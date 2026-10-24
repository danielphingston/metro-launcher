package io.github.danielphingston.metro.ui.tiles

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.PeopleSource
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.settings.Section
import io.github.danielphingston.metro.ui.settings.SettingsActivity
import io.github.danielphingston.metro.ui.start.FlipCard
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.tileSurface
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlin.random.Random

object PeopleTiles {
    /** Settings while contacts access is off, so the tile can be enabled; otherwise the contacts app. */
    fun intent(context: Context): Intent? =
        if (PeopleSource.hasPermission(context)) {
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
        } else {
            Intent(context, SettingsActivity::class.java)
        }
}

/**
 * A mosaic of contact photos. Each change of [flipped] flips one random photo over to
 * another contact. Contacts are read when the tile starts, not observed.
 */
@Composable
fun PeopleTile(tile: ResolvedTile, cellSize: Dp, flipped: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val m = rememberTileMetrics(cellSize)
    val size = tile.spec.size
    // Null until the first query finishes; empty without permission or photos.
    var contacts by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val latest = PeopleSource.photoUris(context)
            if (latest != contacts) contacts = latest
        }
    }
    val pool = contacts
    when {
        pool == null -> Box(Modifier.fillMaxSize().tileSurface())
        pool.isEmpty() -> HintFace(tile.label, "allow contacts in settings", size, m)
        else -> MosaicTile(tile.label, pool, size, cellSize, flipped, m)
    }
}

/** One photo position: [showBack] turns the card over, and the hidden face is always the one to refill. */
private class CellState {
    var front by mutableStateOf<ImageBitmap?>(null)
    var back by mutableStateOf<ImageBitmap?>(null)
    var showBack by mutableStateOf(false)

    fun show(bitmap: ImageBitmap) {
        if (showBack) front = bitmap else back = bitmap
        showBack = !showBack
    }

    fun setFirst(bitmap: ImageBitmap) {
        front = bitmap
    }
}

@Composable
private fun MosaicTile(
    label: String,
    pool: List<String>,
    size: TileSize,
    cellSize: Dp,
    flipped: Boolean,
    m: TileMetrics,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val grid = Mosaic.grid(size)
    val photoPx = with(density) { ((cellSize * size.width).roundToPx() / grid.columns).coerceAtLeast(1) }

    val cells = remember(pool, grid) { List(grid.cells) { CellState() } }
    val shown = remember(pool, grid) { mutableStateListOf<Int>().apply { addAll(Mosaic.initial(grid.cells, pool.size)) } }
    var lastCell by remember(pool, grid) { mutableStateOf<Int?>(null) }
    var lastFlipped by remember { mutableStateOf(flipped) }

    LaunchedEffect(pool, grid, photoPx) {
        shown.forEachIndexed { cell, contact ->
            if (contact < 0) return@forEachIndexed
            PeopleSource.thumbnail(context, pool[contact], photoPx)?.let { cells[cell].setFirst(it.asImageBitmap()) }
        }
    }

    LaunchedEffect(flipped) {
        if (flipped == lastFlipped) return@LaunchedEffect
        lastFlipped = flipped
        val swap = Mosaic.nextSwap(shown, pool.size, lastCell, Random) ?: return@LaunchedEffect
        val bitmap = PeopleSource.thumbnail(context, pool[swap.contact], photoPx) ?: return@LaunchedEffect
        shown[swap.cell] = swap.contact
        lastCell = swap.cell
        cells[swap.cell].show(bitmap.asImageBitmap())
    }

    val gap = m.dp(1f)
    Box(Modifier.fillMaxSize().background(Metro.colors.accent)) {
        Column(Modifier.fillMaxSize()) {
            repeat(grid.rows) { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(grid.columns) { column ->
                        Box(Modifier.weight(1f).fillMaxHeight().padding(gap)) {
                            ContactCell(cells[row * grid.columns + column])
                        }
                    }
                }
            }
        }
        if (size != TileSize.SMALL) ScrimmedLabel(label, m)
    }
}

@Composable
private fun ContactCell(cell: CellState) {
    FlipCard(
        showBack = cell.showBack,
        background = Metro.colors.accent, // Photos stay opaque: nothing shows through them.
        front = { cell.front?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } },
        back = { cell.back?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } },
    )
}

/** Section in Metro settings: opt in to reading contact photos. */
@Composable
fun PeopleSettingsSection() {
    val context = LocalContext.current
    val colors = Metro.colors
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(PeopleSource.hasPermission(context)) }
    var declined by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        declined = !it
    }
    // Re-check after returning from system settings.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { granted = PeopleSource.hasPermission(context) }
    }

    Section("people")
    MetroText(
        "The people tile shows photos of your contacts. They are read on this phone only and " +
            "nothing leaves your device.",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(10.dp))
    if (granted) {
        MetroText("People tile shows your contacts' photos", MetroType.body)
    } else {
        MetroButton("show contacts on people tile", onClick = { request.launch(Manifest.permission.READ_CONTACTS) })
        if (declined) {
            Spacer(Modifier.height(8.dp))
            MetroText(
                "Contacts access was declined. You can allow it in the app's permissions.",
                MetroType.caption,
                color = colors.subtle,
            )
            Spacer(Modifier.height(8.dp))
            MetroButton("open app info", onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            })
        }
    }
}
