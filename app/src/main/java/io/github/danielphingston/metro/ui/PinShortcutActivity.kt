package io.github.danielphingston.metro.ui

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles

/** Confirms a shortcut an app asked to pin (`ACTION_CONFIRM_PIN_SHORTCUT`) and adds it as a tile. */
class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = try {
            getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        val info = request?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }?.shortcutInfo
        if (request == null || info == null) {
            finish()
            return
        }
        val metro = application as MetroApplication
        val label = info.shortLabel?.toString()?.takeIf { it.isNotBlank() } ?: info.id

        setContent {
            val settings by metro.settings.settings.collectAsState(LauncherSettings())
            MetroTheme(settings) {
                PinDialog(
                    label = label,
                    onAdd = {
                        // accept() also pins the shortcut with the system, so it stays valid.
                        val accepted = try {
                            request.isValid && request.accept()
                        } catch (_: SecurityException) {
                            false
                        } catch (_: IllegalStateException) {
                            false
                        }
                        if (accepted) {
                            val serial = ShortcutTiles.serialOf(this, info.userHandle)
                            metro.tiles.add(TileContent.Shortcut(info.`package`, info.id, serial, label), TileSize.MEDIUM)
                        }
                        finish()
                    },
                    onCancel = ::finish,
                )
            }
        }
    }
}

@Composable
private fun PinDialog(label: String, onAdd: () -> Unit, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetroText("add to start?", MetroType.sectionTitle)
            MetroText(label, MetroType.body, color = Metro.colors.subtle, maxLines = 2)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetroButton("add", onAdd, Modifier.weight(1f))
                MetroButton("cancel", onCancel, Modifier.weight(1f))
            }
        }
    }
}
