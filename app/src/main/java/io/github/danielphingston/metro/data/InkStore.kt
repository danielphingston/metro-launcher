package io.github.danielphingston.metro.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.roundToInt

/** One point of handwriting: [x] and [y] as fractions of the page's width, [pressure] 0..1. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float)

/** A line drawn without lifting the pen, in [color] (ARGB; 0 is the plain ink, see [InkStroke.PLAIN]). */
data class InkStroke(val points: List<InkPoint>, val color: Int = PLAIN) {
    companion object {
        /** The plain ink: white on a tile, the text colour on the open page. Free; other colours are Metro Plus. */
        const val PLAIN = 0
    }
}

/**
 * Handwriting as bytes, compressed. Per stroke: its point count and colour, its first point in
 * full (x and y to 1/[SCALE] of the page's width, pressure to 1/255), then each later point as
 * its difference from the one before, in a variable number of bytes: pen movement between two
 * samples is small, so most points take 3 bytes, and those small numbers compress well. A page
 * of a few lines is about 5 to 15 KB stored; a note keeps at most [MAX_POINTS] points. Anything
 * unreadable decodes as no strokes.
 */
object InkCodec {
    private const val SCALE = 4000f
    private const val VERSION: Byte = 2

    /** The most points a note keeps; strokes past it are dropped. */
    const val MAX_POINTS = 12_000

    /** The largest a drawing can be once inflated (every value at its longest); a file claiming more is damaged. */
    private const val MAX_RAW = 1 + MAX_POINTS * (3 + 3 + 2 + 3 + 4)

    fun encode(strokes: List<InkStroke>): ByteArray {
        val kept = capped(strokes)
        if (kept.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(kept.sumOf { 8 + it.points.size * 3 })
        out.write(VERSION.toInt())
        kept.forEach { stroke ->
            writeVarint(out, stroke.points.size)
            for (shift in intArrayOf(24, 16, 8, 0)) out.write(stroke.color ushr shift and 0xFF)
            var px = 0
            var py = 0
            var pp = 0
            stroke.points.forEach {
                val x = (it.x * SCALE).roundToInt().coerceIn(0, 65_535)
                val y = (it.y * SCALE).roundToInt().coerceIn(0, 65_535)
                val p = (it.pressure * 255).roundToInt().coerceIn(0, 255)
                writeVarint(out, zigzag(x - px))
                writeVarint(out, zigzag(y - py))
                writeVarint(out, zigzag(p - pp))
                px = x
                py = y
                pp = p
            }
        }
        return deflate(out.toByteArray())
    }

    fun decode(bytes: ByteArray): List<InkStroke> {
        if (bytes.isEmpty()) return emptyList()
        return runCatching {
            val buffer = ByteBuffer.wrap(inflate(bytes) ?: return emptyList())
            if (buffer.get() != VERSION) return emptyList()
            val strokes = ArrayList<InkStroke>()
            var total = 0
            while (buffer.hasRemaining()) {
                val count = readVarint(buffer)
                total += count
                if (count <= 0 || total > MAX_POINTS || buffer.remaining() < 4 + count * 3) break
                val color = buffer.getInt()
                var x = 0
                var y = 0
                var p = 0
                strokes += InkStroke(
                    List(count) {
                        x += unzigzag(readVarint(buffer))
                        y += unzigzag(readVarint(buffer))
                        p += unzigzag(readVarint(buffer))
                        InkPoint(x.coerceIn(0, 65_535) / SCALE, y.coerceIn(0, 65_535) / SCALE, p.coerceIn(0, 255) / 255f)
                    },
                    color,
                )
            }
            strokes
        }.getOrDefault(emptyList())
    }

    private fun zigzag(n: Int) = (n shl 1) xor (n shr 31)

    private fun unzigzag(n: Int) = (n ushr 1) xor -(n and 1)

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write(v and 0x7F or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    /** A variable-length number; throws (caught by [decode]) when the bytes end mid-number or run past five bytes. */
    private fun readVarint(buffer: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buffer.get().toInt()
            result = result or (b and 0x7F shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 28) throw IllegalStateException("varint too long")
        }
    }

    /** Ink from builds that kept it in the tile, as Base64 of version-1 bytes (no colour, not compressed). */
    fun decodeLegacy(encoded: String): List<InkStroke> {
        if (encoded.isEmpty()) return emptyList()
        return runCatching {
            val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded))
            if (buffer.get() != 1.toByte()) return emptyList()
            val strokes = ArrayList<InkStroke>()
            while (buffer.remaining() >= 2) {
                val count = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < count * 5) break
                strokes += InkStroke(List(count) { readPoint(buffer) })
            }
            strokes
        }.getOrDefault(emptyList())
    }

    /** [strokes] up to [MAX_POINTS] points in all; empty strokes go. */
    fun capped(strokes: List<InkStroke>): List<InkStroke> {
        var total = 0
        return strokes.filter { it.points.isNotEmpty() }.map { it.copy(points = it.points.take(65_535)) }
            .takeWhile { stroke -> (total + stroke.points.size <= MAX_POINTS).also { if (it) total += stroke.points.size } }
    }

    /** [stroke] without points closer than [minDistance] to the one kept before them (the last always stays). */
    fun simplify(stroke: List<InkPoint>, minDistance: Float = 0.002f): List<InkPoint> {
        if (stroke.size < 3) return stroke
        val out = ArrayList<InkPoint>(stroke.size)
        out += stroke.first()
        for (i in 1 until stroke.size - 1) {
            val p = stroke[i]
            val q = out.last()
            if (abs(p.x - q.x) + abs(p.y - q.y) >= minDistance) out += p
        }
        out += stroke.last()
        return out
    }

    /** Whether [bytes] is a drawing this build can read: a backup's ink is checked with it before it is written. */
    fun isReadable(bytes: ByteArray): Boolean = bytes.isEmpty() || inflate(bytes)?.firstOrNull() == VERSION

    private fun readPoint(buffer: ByteBuffer) = InkPoint(
        (buffer.getShort().toInt() and 0xFFFF) / SCALE,
        (buffer.getShort().toInt() and 0xFFFF) / SCALE,
        (buffer.get().toInt() and 0xFF) / 255f,
    )

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 2 + 64)
            val chunk = ByteArray(8192)
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** The inflated bytes, or null when they are damaged or larger than any drawing can be. */
    private fun inflate(bytes: ByteArray): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 3)
            val chunk = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(chunk, 0, n)
                if (out.size() > MAX_RAW) return null
            }
            return out.toByteArray()
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}

/**
 * Drawings from builds that kept ink inside the tile, waiting to move to their own file: the
 * layout decoder hands them over (see ToolJson) and [InkStore] writes each one the first time
 * it is asked for it.
 */
object InkLegacy {
    private val pending = ConcurrentHashMap<String, String>()

    fun adopt(id: String, base64: String) {
        pending.putIfAbsent(id, base64)
    }

    internal fun take(id: String): List<InkStroke>? = pending.remove(id)?.let(InkCodec::decodeLegacy)

    internal fun peek(id: String): List<InkStroke>? = pending[id]?.let(InkCodec::decodeLegacy)
}

/**
 * Handwriting, one compressed file per drawing in [dir], outside the settings store: ticking a
 * routine or moving a tile never rewrites a drawing, and the layout stays small. Reads and writes
 * are blocking and belong off the main thread. Decoded drawings are cached; [changes] counts
 * writes so faces showing a drawing can read it again.
 */
class InkStore(private val dir: File) {
    private val cache = ConcurrentHashMap<String, List<InkStroke>>()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** The drawing [id], empty when there is none. */
    fun read(id: String): List<InkStroke> {
        if (!isValidId(id)) return emptyList()
        cache[id]?.let { return it }
        val file = fileBytes(id)
        // A file is always newer than ink still waiting in an old layout (the layout loses it on its next save).
        if (file != null) {
            InkLegacy.take(id)
        } else {
            InkLegacy.take(id)?.let { legacy ->
                write(id, legacy)
                return legacy
            }
        }
        val strokes = file?.let(InkCodec::decode).orEmpty()
        cache[id] = strokes
        return strokes
    }

    /** Replaces drawing [id] with [strokes] (an empty drawing removes its file). */
    fun write(id: String, strokes: List<InkStroke>) {
        if (!isValidId(id)) return
        val kept = InkCodec.capped(strokes)
        writeBytes(id, InkCodec.encode(kept))
        cache[id] = kept
        _changes.update { it + 1 }
    }

    /** The stored bytes of drawing [id] (compressed), for a backup; null when there is none. */
    fun readBytes(id: String): ByteArray? {
        if (!isValidId(id)) return null
        return fileBytes(id) ?: InkLegacy.peek(id)?.let(InkCodec::encode)
    }

    private fun fileBytes(id: String): ByteArray? = try {
        File(dir, id).takeIf { it.isFile }?.readBytes()
    } catch (_: IOException) {
        null
    }

    /** Writes [bytes] as drawing [id] atomically (a temporary file renamed over it). */
    fun writeBytes(id: String, bytes: ByteArray) {
        if (!isValidId(id)) return
        cache.remove(id)
        val file = File(dir, id)
        if (bytes.isEmpty()) {
            file.delete()
            return
        }
        try {
            dir.mkdirs()
            val tmp = File(dir, "$id.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: IOException) {
            // Disk full or unwritable: the drawing in memory stays, and the next write tries again.
        }
    }

    /** Forgets what is cached and tells every face to read again, e.g. after a backup was restored. */
    fun reloadAll() {
        cache.clear()
        _changes.update { it + 1 }
    }

    /**
     * Deletes the drawings none of [referenced] names, except those written since [keepSince]
     * (a tile added moments ago may not be in the saved layout yet); returns how many went.
     */
    fun deleteAllBut(referenced: Set<String>, keepSince: Long): Int {
        val files = dir.listFiles().orEmpty()
        var deleted = 0
        for (file in files) {
            val id = file.name.removeSuffix(".tmp")
            if (file.lastModified() >= keepSince) continue
            if (file.name.endsWith(".tmp") || id !in referenced) {
                cache.remove(id)
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidId(id: String) = ID.matches(id)

        /** A new drawing's id. */
        fun newId(now: Long): String = "ink-$now-${(0..9999).random()}"

        private val ID_IN_JSON = Regex("\"inkId\"\\s*:\\s*\"([A-Za-z0-9_-]{1,64})\"")

        /**
         * The drawings named in [stored] (layouts as the settings store keeps them: the live
         * tiles, the saved layouts, the backup copy). Read from the text itself, so entries this
         * build can't decode still keep their drawing.
         */
        fun referencedIn(stored: Collection<String>): Set<String> =
            stored.flatMapTo(HashSet()) { text -> ID_IN_JSON.findAll(text).map { it.groupValues[1] } }

        /** The drawings [tiles] (and the children of their folders) use. */
        fun referencedBy(tiles: List<TileSpec>): Set<String> {
            val ids = HashSet<String>()
            fun visit(list: List<TileSpec>): Unit = list.forEach { spec ->
                when (val c = spec.content) {
                    is TileContent.Notes -> if (c.inkId.isNotEmpty()) ids += c.inkId
                    is TileContent.Folder -> visit(c.children)
                    else -> Unit
                }
            }
            visit(tiles)
            return ids
        }
    }
}
