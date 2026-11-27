package io.github.danielphingston.metro.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.danielphingston.metro.ui.tiles.TileImports

/**
 * "Import API tile" in the share sheet. The shared text goes to [TileImports] and the launcher comes
 * forward with the API tile editor open on it. The text is only put in the editor's import box:
 * nothing is read, checked or fetched until the user taps there, so another app can't add a tile.
 */
class ShareTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = runCatching { intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
        if (TileImports.offer(text)) {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }
}
