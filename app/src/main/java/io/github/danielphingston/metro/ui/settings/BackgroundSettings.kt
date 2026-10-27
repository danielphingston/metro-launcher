package io.github.danielphingston.metro.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.live.PhotoSource
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroSlider
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What each background mode does, as shown under the choices. */
private fun StartBackground.describe(): String = when (this) {
    StartBackground.SOLID -> "The plain black or white page."
    StartBackground.PHOTO_TILES -> "Each tile shows the part of your photo behind it, like a window onto one picture."
    StartBackground.PHOTO_FULL -> "Your photo fills the start screen behind translucent tiles."
    StartBackground.SYSTEM_WALLPAPER ->
        "Your phone's wallpaper shows behind the tiles. Android doesn't let apps read it, " +
            "so its colours can't be used for the accent."
}

private val Choices = listOf(
    StartBackground.SOLID to "solid",
    StartBackground.PHOTO_TILES to "photo tiles",
    StartBackground.PHOTO_FULL to "full photo",
    StartBackground.SYSTEM_WALLPAPER to "wallpaper",
)

/**
 * Section in Metro settings: what is behind the tiles, the photo for the photo modes and tile
 * transparency.
 */
@Composable
fun BackgroundSettingsSection(settings: LauncherSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
    val hasPhoto = settings.backgroundPhoto != null

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val accent = withContext(Dispatchers.IO) { BackgroundPhoto.deriveAccent(context, uri) }
            val previous = repository.settings.first().backgroundPhoto
            repository.setBackgroundPhoto(uri.toString(), accent)
            // Let go of the old photo, unless the photos tile shows it too.
            if (previous != null && previous != uri.toString() && previous !in PhotoSource.current(context)) {
                runCatching {
                    resolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }
    fun pickPhoto() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    Section("start background")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choices.chunked(2).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { (mode, label) ->
                    MetroChoice(label, settings.background == mode, {
                        scope.launch { repository.setBackground(mode) }
                        val needsPhoto = mode == StartBackground.PHOTO_TILES || mode == StartBackground.PHOTO_FULL
                        if (needsPhoto && !hasPhoto) pickPhoto()
                    }, Modifier.weight(1f))
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    MetroText(settings.background.describe(), MetroType.caption, color = colors.subtle)

    if (settings.showsPhoto) {
        Spacer(Modifier.height(12.dp))
        MetroButton(if (hasPhoto) "change photo" else "choose photo", onClick = ::pickPhoto)
        Spacer(Modifier.height(8.dp))
        MetroText(
            if (hasPhoto) "Metro only keeps the photo you picked and nothing leaves your device." else "No photo chosen",
            MetroType.caption,
            color = colors.subtle,
        )
    }

    // The slider follows the finger; the setting is saved when it lets go.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val transparency = dragged ?: settings.transparencyFraction
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MetroText("Tile transparency", MetroType.body, modifier = Modifier.weight(1f))
        MetroText("${(transparency * 100).roundToInt()}%", MetroType.body, color = colors.accent)
    }
    MetroSlider(
        value = transparency,
        onValueChange = { dragged = it },
        modifier = Modifier.padding(top = 2.dp),
        onValueChangeFinished = {
            dragged?.let { value ->
                scope.launch {
                    repository.setTileTransparency((value * 100).roundToInt())
                    dragged = null
                }
            }
        },
    )
}

/** The accent-from-photo toggle; shown on the appearance page, only while a background photo is in use. */
@Composable
fun AccentFromBackgroundToggle(settings: LauncherSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    if (settings.showsPhoto && settings.backgroundPhoto != null) {
        Spacer(Modifier.height(4.dp))
        MetroToggle(
            "Accent colour from my background",
            settings.accentFromBackground,
            { scope.launch { repository.setAccentFromBackground(it) } },
            description = if (settings.accentFromBackground) "Using a colour from your photo" else "Using the accent colour above",
        )
    }
}
