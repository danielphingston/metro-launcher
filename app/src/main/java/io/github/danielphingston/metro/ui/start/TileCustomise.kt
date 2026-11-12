package io.github.danielphingston.metro.ui.start

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.data.PhotoUse
import io.github.danielphingston.metro.data.StartEdit
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileOption
import io.github.danielphingston.metro.data.TileOptions
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroSlider
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlin.math.roundToInt

/** Where a tile's icon comes from, as the customise sheet offers it. */
internal enum class IconSource { APP, PACK, PHOTO }

/** What the photo choices in the customise sheet ask for. */
internal enum class PhotoChoice { NONE, FILL, ANOTHER }

/** The icon source [spec] uses now. */
internal fun iconSourceOf(spec: TileSpec): IconSource = when {
    spec.look.photo == PhotoUse.ICON -> IconSource.PHOTO
    spec.icon != null -> IconSource.PACK
    else -> IconSource.APP
}

/**
 * Whether [spec] is an app tile drawn as its own live face, with no icon: the default clock and
 * calendar apps (TileFace.kt: ClockFront, CalendarFront). See [TileOptions.available].
 */
internal fun drawsOwnFace(spec: TileSpec, defaults: DefaultApps): Boolean {
    val pkg = (spec.content as? TileContent.App)?.app?.packageName ?: return false
    return pkg == defaults.clock || pkg == defaults.calendar
}

/**
 * The customise sheet of tile [spec] on the start screen: [TileCustomiseSheet] wired to the view
 * model, each change one undoable edit. Photos come from the system photo picker, which needs no
 * storage permission; the launcher keeps read access to just the picked photo and gives it back
 * when the tile stops using it. [onChooseIconPack] opens the icon picker.
 */
@Composable
internal fun TileCustomise(
    spec: TileSpec,
    options: Set<TileOption>,
    startTransparency: Int,
    viewModel: LauncherViewModel,
    onChooseIconPack: () -> Unit,
    onWidgetSettings: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val id = spec.id
    val hasLook = TileOption.SHOW_ICON in options
    // Another tile, a saved layout or a background may use the same photo: only then is it kept.
    fun release(uri: String?) = viewModel.releasePhotoIfUnused(uri)
    fun look(change: (TileSpec) -> TileSpec) {
        val before = spec.image
        val after = change(spec).image
        viewModel.updateTile(id, StartEdit.STYLE, change)
        if (before != null && before != after) release(before)
    }
    var pending by rememberSaveable { mutableStateOf<PhotoUse?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val use = pending
        pending = null
        if (uri != null && use != null) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            look { TileOptions.withPhoto(it, use, uri.toString()) }
        }
    }
    fun pickPhoto(use: PhotoUse) {
        pending = use
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    TileCustomiseSheet(
        spec = spec,
        options = options,
        startTransparency = startTransparency,
        onShowName = { shown -> look { TileOptions.withNameShown(it, shown, hasLook) } },
        onShowIcon = { shown -> look { TileOptions.withIconShown(it, shown) } },
        onIconSource = { source ->
            when (source) {
                IconSource.APP -> viewModel.updateTile(id, StartEdit.ICON) { TileOptions.withIcon(it, null) }.also {
                    if (spec.look.photo == PhotoUse.ICON) release(spec.image)
                }
                IconSource.PACK -> onChooseIconPack()
                IconSource.PHOTO -> pickPhoto(PhotoUse.ICON)
            }
        },
        onPhoto = { choice ->
            when (choice) {
                PhotoChoice.NONE -> look { TileOptions.withPhoto(it, PhotoUse.NONE, null) }
                // The photo already picked (as the icon) fills the tile; else one is picked first.
                PhotoChoice.FILL -> spec.image?.let { image -> look { TileOptions.withPhoto(it, PhotoUse.FILL, image) } } ?: pickPhoto(PhotoUse.FILL)
                PhotoChoice.ANOTHER -> pickPhoto(PhotoUse.FILL)
            }
        },
        onBackground = { shown -> viewModel.updateTile(id, StartEdit.BACKGROUND) { TileOptions.withBackground(it, shown) } },
        onColour = { colour -> viewModel.updateTile(id, StartEdit.COLOUR) { it.copy(color = io.github.danielphingston.metro.data.TileColors.parse(colour)) } },
        onTransparency = { percent -> viewModel.updateTile(id, StartEdit.TRANSPARENCY) { TileOptions.withTransparency(it, percent) } },
        onWidgetSettings = onWidgetSettings,
        onDismiss = onDismiss,
    )
}

/**
 * The customise sheet: a flyout from the bottom, in sections, with only what [options] says
 * applies to [spec] (see [TileOptions.available]). It scrolls when it is taller than the screen,
 * stays clear of the status bar, and its last row clears the navigation bar. Changes apply at once
 * and the sheet stays open; the dimmed page or back closes it.
 */
@Composable
internal fun TileCustomiseSheet(
    spec: TileSpec,
    options: Set<TileOption>,
    /** The start screen's tile transparency, 0..100: where the slider starts while the tile follows it. */
    startTransparency: Int,
    onShowName: (Boolean) -> Unit,
    onShowIcon: (Boolean) -> Unit,
    onIconSource: (IconSource) -> Unit,
    onPhoto: (PhotoChoice) -> Unit,
    onBackground: (Boolean) -> Unit,
    onColour: (String?) -> Unit,
    onTransparency: (Int?) -> Unit,
    onWidgetSettings: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    MetroFlyout(onDismiss, paneTitle = "customise tile") {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 48.dp)
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                .verticalScroll(rememberScrollState())
                // Same 24dp margins as the menus; the chrome runs under the navigation bar and the
                // last row clears it with normal spacing, so it never sits under the gesture handle.
                .padding(
                    start = 24.dp, end = 24.dp, top = 16.dp,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
        ) {
            MetroText("CUSTOMISE", MetroType.appName, color = Metro.colors.subtle)
            CustomiseSections(spec, options, startTransparency, onShowName, onShowIcon, onIconSource, onPhoto, onBackground, onColour, onTransparency, onWidgetSettings)
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Spacer(Modifier.height(20.dp))
    MetroText(title, MetroType.sectionTitle, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { heading() })
}

@Composable
private fun CustomiseSections(
    spec: TileSpec,
    options: Set<TileOption>,
    startTransparency: Int,
    onShowName: (Boolean) -> Unit,
    onShowIcon: (Boolean) -> Unit,
    onIconSource: (IconSource) -> Unit,
    onPhoto: (PhotoChoice) -> Unit,
    onBackground: (Boolean) -> Unit,
    onColour: (String?) -> Unit,
    onTransparency: (Int?) -> Unit,
    onWidgetSettings: (() -> Unit)?,
) {
    val look = spec.look
    val folder = spec.content is TileContent.Folder
    val hasLook = TileOption.SHOW_ICON in options
    if (TileOption.SHOW_NAME in options || hasLook) {
        SheetSection("content")
        if (TileOption.SHOW_NAME in options) {
            val shown = if (hasLook) look.showName else !spec.hideName
            val canHide = !hasLook || TileOptions.canHide(spec, name = true)
            MetroToggle(
                "Show name", shown, onShowName,
                description = if (shown && !canHide) "The tile has to show its name or its icon" else null,
                enabled = !shown || canHide,
            )
        }
        if (hasLook) {
            val canHide = TileOptions.canHide(spec, name = false)
            MetroToggle(
                if (folder) "Show app icons" else "Show icon", look.showIcon, onShowIcon,
                description = if (look.showIcon && !canHide) "The tile has to show its name or its icon" else null,
                enabled = !look.showIcon || canHide,
            )
        }
    }
    if (TileOption.ICON_SOURCE in options) {
        SheetSection("icon")
        val source = iconSourceOf(spec)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetroChoice("app icon", source == IconSource.APP, { onIconSource(IconSource.APP) }, Modifier.weight(1f))
            MetroChoice("from icon pack…", source == IconSource.PACK, { onIconSource(IconSource.PACK) }, Modifier.weight(1f))
            MetroChoice("my photo…", source == IconSource.PHOTO, { onIconSource(IconSource.PHOTO) }, Modifier.weight(1f))
        }
    }
    if (TileOption.PHOTO in options) {
        SheetSection("photo")
        val fill = look.photo == PhotoUse.FILL
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetroChoice("none", !fill, { onPhoto(PhotoChoice.NONE) }, Modifier.weight(1f))
            MetroChoice("fill the tile", fill, { onPhoto(PhotoChoice.FILL) }, Modifier.weight(1f))
        }
        if (fill) {
            Spacer(Modifier.height(12.dp))
            MetroButton("choose another photo", { onPhoto(PhotoChoice.ANOTHER) })
        }
    }
    if (TileOption.BACKGROUND in options) {
        SheetSection("background")
        MetroToggle(
            "Show background", spec.showsBackground, onBackground,
            description = if (spec.showsBackground) null else "Only the tile's own content shows",
        )
        if (spec.showsBackground) {
            if (TileOption.COLOUR in options) {
                MetroText("Colour", MetroType.body, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                TileColourChoices(spec, onColour)
            }
            if (TileOption.TRANSPARENCY in options) TransparencyRow(spec.transparency, startTransparency, onTransparency)
        }
    }
    if (TileOption.WIDGET_SETTINGS in options && onWidgetSettings != null) {
        SheetSection("widget")
        MetroButton("widget settings…", onWidgetSettings)
    }
}

/**
 * The tile's own transparency: "same as start" (null) until the slider is moved, then 0..100%,
 * saved when the finger lifts (one undo step per drag); "same as start" goes back.
 */
@Composable
private fun TransparencyRow(percent: Int?, startTransparency: Int, onChange: (Int?) -> Unit) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: percent
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MetroText("Transparency", MetroType.body, modifier = Modifier.weight(1f))
        MetroText(shown?.let { "$it%" } ?: "same as start", MetroType.body, color = Metro.colors.accent)
    }
    MetroSlider(
        value = (shown ?: startTransparency) / 100f,
        onValueChange = { dragging = (it * 100).roundToInt().coerceIn(0, 100) },
        steps = 99,
        valueDescription = { "${(it * 100).roundToInt()} percent" },
        onValueChangeFinished = {
            dragging?.let(onChange)
            dragging = null
        },
    )
    if (percent != null) {
        Spacer(Modifier.height(8.dp))
        MetroButton("same as start", { onChange(null) })
    }
}
