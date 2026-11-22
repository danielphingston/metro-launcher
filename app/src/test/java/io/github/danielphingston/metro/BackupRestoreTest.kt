package io.github.danielphingston.metro

import android.net.Uri
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.InkPoint
import io.github.danielphingston.metro.data.InkStroke
import io.github.danielphingston.metro.data.NoteItem
import io.github.danielphingston.metro.data.NoteStyle
import io.github.danielphingston.metro.data.Routines
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.metroDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Export and restore through the real app: tool tiles (a routine, a note, handwriting) and the
 * handwriting's file go out in a backup and come back after everything was changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MetroApplication::class)
class BackupRestoreTest {
    private val app get() = RuntimeEnvironment.getApplication() as MetroApplication

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    /** Runs the main looper (the app's scope lives on it) until [done], for at most ten seconds. */
    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    private fun tiles(): List<io.github.danielphingston.metro.data.TileSpec> {
        until { app.tiles.tiles.value != null }
        return app.tiles.tiles.value!!
    }

    @Test
    fun toolTilesAndHandwritingSurviveARestore() {
        tiles()
        val drawing = listOf(InkStroke(listOf(InkPoint(0.1f, 0.1f, 0.5f), InkPoint(0.4f, 0.3f, 0.7f)), 0xFF43A047.toInt()))
        app.ink.write("ink-77-1", drawing)
        app.tiles.add(TileContent.Notes("sketch", NoteStyle.INK, inkId = "ink-77-1", penColor = 0xFF43A047.toInt(), created = 1), TileSize.WIDE)
        app.tiles.add(TileContent.Notes("groceries", NoteStyle.NOTE, listOf(NoteItem("milk")), text = "by 9", created = 2), TileSize.WIDE)
        app.tiles.add(Routines.new(3).copy(name = "vitamins"), TileSize.MEDIUM)
        // The save is debounced: wait until the store holds the new tiles.
        val key = androidx.datastore.preferences.core.stringPreferencesKey("tiles")
        until {
            app.tiles.flush()
            val stored = run { app.metroDataStore.data.first()[key] }
            stored != null && stored.contains("ink-77-1") && stored.contains("vitamins")
        }
        val file = File(app.cacheDir, "backup.json")
        run { Backup.export(app, Uri.fromFile(file)) }
        val json = JSONObject(file.readText())
        assertEquals(Backup.FORMAT, json.getInt("format"))
        assertTrue("the drawing travels with the backup", json.getJSONObject("ink").has("ink-77-1"))

        // Everything changes: the tiles go, the drawing goes.
        tiles().forEach { app.tiles.unpin(it.id) }
        app.ink.write("ink-77-1", emptyList())
        ShadowLooper.idleMainLooper()

        run { Backup.import(app, Uri.fromFile(file)) }
        until { tiles().any { it.content is TileContent.Routine } }
        val restored = tiles().map { it.content }
        val ink = restored.filterIsInstance<TileContent.Notes>().single { it.style == NoteStyle.INK }
        assertEquals("ink-77-1", ink.inkId)
        assertEquals(0xFF43A047.toInt(), ink.penColor)
        assertEquals(listOf(0xFF43A047.toInt()), app.ink.read("ink-77-1").map { it.color })
        assertEquals("by 9", restored.filterIsInstance<TileContent.Notes>().single { it.style == NoteStyle.NOTE }.text)
        assertEquals("vitamins", restored.filterIsInstance<TileContent.Routine>().single().name)
    }
}
