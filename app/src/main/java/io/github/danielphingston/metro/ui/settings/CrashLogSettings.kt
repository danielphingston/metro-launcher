package io.github.danielphingston.metro.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.CrashLog
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lines of the newest report that the section shows: the header and the top of the stack trace. */
private const val PREVIEW_LINES = 10

/**
 * Section in Metro settings with the crash the launcher recorded last (see [CrashLog]). Shown
 * only while a report exists. The report stays on the phone unless "share" is pressed.
 */
@Composable
fun CrashLogSettingsSection() {
    val context = LocalContext.current
    val colors = Metro.colors
    var reports by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { reports = withContext(Dispatchers.IO) { CrashLog.read(context) } }
    val latest = reports.firstOrNull() ?: return

    Section("last crash")
    MetroText(
        "Metro stopped unexpectedly. The details below are saved on this phone only and are never " +
            "sent anywhere. Sharing is up to you.",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(10.dp))
    MetroText(CrashLog.preview(latest, PREVIEW_LINES), MetroType.caption, maxLines = PREVIEW_LINES + 2)
    if (reports.size > 1) {
        Spacer(Modifier.height(6.dp))
        MetroText("${reports.size - 1} earlier crash reports are saved too", MetroType.caption, color = colors.subtle)
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetroButton("share", modifier = Modifier.weight(1f), onClick = {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Metro Launcher crash report")
                .putExtra(Intent.EXTRA_TEXT, reports.joinToString(CrashLog.SEPARATOR))
            runCatching { context.startActivity(Intent.createChooser(send, "share crash report")) }
        })
        MetroButton("clear", modifier = Modifier.weight(1f), onClick = {
            CrashLog.clear(context)
            reports = emptyList()
        })
    }
}
