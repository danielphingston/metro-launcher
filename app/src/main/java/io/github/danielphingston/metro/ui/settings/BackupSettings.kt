package io.github.danielphingston.metro.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.BackupException
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate

/** Settings section to save the tiles and settings to a file and to restore them from one; see [Backup]. */
@Composable
internal fun BackupSettingsSection() {
    val context = LocalContext.current
    val colors = Metro.colors
    val scope = rememberCoroutineScope()
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                Backup.export(context, uri)
                toast("Backup saved")
            } catch (e: IOException) {
                toast("Couldn't save the backup: ${e.message ?: "write failed"}")
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }

    Section("backup")
    MetroText(
        "Save your tiles and settings to a file, or bring them back from one, for example on a new phone.",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(10.dp))
    MetroButton("export backup", onClick = { exporter.launch("metro-backup-${LocalDate.now()}.json") })
    Spacer(Modifier.height(10.dp))
    MetroButton("restore from backup", onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) })
    Spacer(Modifier.height(6.dp))
    MetroText(
        "Photos chosen for tiles or the background may need to be picked again on a new phone.",
        MetroType.caption,
        color = colors.subtle,
    )

    pendingRestore?.let { uri ->
        Dialog(onDismissRequest = { pendingRestore = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.chrome)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetroText("restore backup?", MetroType.sectionTitle)
                MetroText(
                    "Replace your current tiles and settings with this backup?",
                    MetroType.body,
                    color = colors.subtle,
                    maxLines = 4,
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetroButton("restore", modifier = Modifier.weight(1f), onClick = {
                        pendingRestore = null
                        scope.launch {
                            try {
                                Backup.import(context, uri)
                                toast("Backup restored")
                            } catch (e: BackupException) {
                                toast(e.message ?: "This backup can't be restored.")
                            } catch (e: IOException) {
                                toast("Couldn't read the backup: ${e.message ?: "read failed"}")
                            }
                        }
                    })
                    MetroButton("cancel", onClick = { pendingRestore = null }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
