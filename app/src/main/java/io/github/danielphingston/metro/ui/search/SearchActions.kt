package io.github.danielphingston.metro.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import io.github.danielphingston.metro.data.LaunchTarget
import io.github.danielphingston.metro.data.TileContent

/** Ready-made [ItemAction]s that more than one provider needs. */
internal object SearchActions {
    /**
     * Puts [text] on the clipboard. From Android 13 the system shows its own "copied" confirmation,
     * so the toast is only for 12 and 12L, where there is none.
     */
    fun copy(text: String, sdk: Int = Build.VERSION.SDK_INT): ItemAction = ItemAction.Run { env ->
        env.context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("answer", text))
        if (sdk < Build.VERSION_CODES.TIRAMISU) Toast.makeText(env.context, "copied", Toast.LENGTH_SHORT).show()
    }

    /** [intent] started in its own task, as every launcher intent has to be. */
    fun start(intent: Intent): ItemAction = ItemAction.Start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** A start tile named [label] with [icon] that starts [intent], for [SearchItem.pin]. */
    fun pin(label: String, icon: SearchIcon, intent: Intent): TileContent =
        TileContent.Action(label, icon.actionIcon, LaunchTarget.Activity(intent))
}
