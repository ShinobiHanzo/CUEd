package dev.cued.core.tag

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Writes iTunes-style metadata (`moov/udta/meta/ilst`) into a plain MP4/M4A
 * without any library: the file's other boxes are copied byte for byte, the
 * `moov` box gets a fresh `udta`, and if `moov` sits before `mdat` every
 * chunk offset (`stco`/`co64`) is shifted by the growth. Fragmented files
 * (`moof` / `mvex`, which is what YouTube serves) are refused: remux them to
 * a plain MP4 first.
 */
object Mp4Tags {
    data class Tags(
        val title: String? = null, val artist: String? = null, val album: String? = null, val albumArtist: String? = null,
        val track: Int? = null, val totalTracks: Int? = null, val year: String? = null, val genre: String? = null,
        val comment: String? = null, val lyrics: String? = null, val cover: ByteArray? = null,
    )

    data class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int) { val end: Long get() = offset + size }

    class UnsupportedLayout(msg: String) : IllegalStateException(msg)

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf")

    /** Top-level boxes of a file. Handles 64-bit sizes and a final size-0 box. */
    fun topLevel(raf: RandomAccessFile): List<Box> {
        val out = ArrayList<Box>()
        var pos = 0L
        val len = raf.length()
        val head = ByteArray(16)
        while (pos + 8 <= len) {
            raf.seek(pos); raf.readFully(head, 0, 8)
            var size = u32(head, 0)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var hs = 8
            if (size == 1L) { raf.readFully(head, 8, 8); size = ByteBuffer.wrap(head, 8, 8).long; hs = 16 }
            else if (size == 0L) size = len - pos
            if (size < hs || pos + size > len) throw UnsupportedLayout("bad box '$type' at $pos (size $size)")
            out += Box(type, pos, size, hs)
            pos += size
        }
        return out
    }

    fun children(data: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(data, p); val type = String(data, p + 4, 4, Charsets.ISO_8859_1); var hs = 8
            if (size == 1L) { size = ByteBuffer.wrap(data, p + 8, 8).long; hs = 16 } else if (size == 0L) size = (to - p).toLong()
            if (size < hs || p + size > to) break
            out += Box(type, p.toLong(), size, hs)
            p += size.toInt()
        }
        return out
    }

    fun isFragmented(file: File): Boolean = RandomAccessFile(file, "r").use { raf ->
        val top = topLevel(raf)
        if (top.any { it.type == "moof" }) return true
        val moov = top.firstOrNull { it.type == "moov" } ?: return false
        val bytes = ByteArray(moov.size.toInt().coerceAtMost(8 shl 20)); raf.seek(moov.offset); raf.readFully(bytes)
        children(bytes, moov.headerSize, bytes.size).any { it.type == "mvex" }
    }

    /** Rewrites [input] into [output] with [tags]; the audio is untouched. */
    fun write(input: File, output: File, tags: Tags) {
        RandomAccessFile(input, "r").use { raf ->
            val top = topLevel(raf)
            if (top.any { it.type == "moof" }) throw UnsupportedLayout("fragmented MP4 (moof); remux first")
            val moov = top.firstOrNull { it.type == "moov" } ?: throw UnsupportedLayout("no moov box")
            if (moov.size > 64L shl 20) throw UnsupportedLayout("moov too large (${moov.size})")
            val old = ByteArray(moov.size.toInt()); raf.seek(moov.offset); raf.readFully(old)
            val kids = children(old, moov.headerSize, old.size)
            if (kids.any { it.type == "mvex" }) throw UnsupportedLayout("fragmented MP4 (mvex); remux first")

            // New moov = old children minus udta, plus our udta.
            val body = java.io.ByteArrayOutputStream()
            for (k in kids) if (k.type != "udta") body.write(old, k.offset.toInt(), k.size.toInt())
            body.write(udta(tags))
            val newMoov = box("moov", body.toByteArray())
            val delta = newMoov.size.toLong() - moov.size

            // Chunk offsets point into mdat by absolute position: shift them if mdat comes after moov.
            val mdatAfter = top.any { it.type == "mdat" && it.offset > moov.offset }
            if (delta != 0L && mdatAfter) shiftOffsets(newMoov, 8, newMoov.size, delta)

            RandomAccessFile(output, "rw").use { out ->
                out.setLength(0)
                val buf = ByteArray(1 shl 20)
                for (b in top) {
                    if (b === moov) { out.write(newMoov); continue }
                    raf.seek(b.offset)
                    var left = b.size
                    while (left > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n <= 0) break; out.write(buf, 0, n); left -= n }
                }
            }
        }
    }

    private fun shiftOffsets(data: ByteArray, from: Int, to: Int, delta: Long) {
        for (b in children(data, from, to)) {
            val o = b.offset.toInt()
            when (b.type) {
                "stco" -> {
                    val n = u32(data, o + b.headerSize + 4).toInt()
                    var p = o + b.headerSize + 8
                    repeat(n) {
                        val v = u32(data, p) + delta
                        if (v < 0 || v > 0xFFFFFFFFL) throw UnsupportedLayout("chunk offset overflow")
                        putU32(data, p, v); p += 4
                    }
                }
                "co64" -> {
                    val n = u32(data, o + b.headerSize + 4).toInt()
                    var p = o + b.headerSize + 8
                    repeat(n) { ByteBuffer.wrap(data, p, 8).putLong(ByteBuffer.wrap(data, p, 8).long + delta); p += 8 }
                }
                in CONTAINERS -> shiftOffsets(data, o + b.headerSize, (b.offset + b.size).toInt(), delta)
            }
        }
    }

    // ---- building boxes ----

    private fun udta(t: Tags): ByteArray {
        val ilst = java.io.ByteArrayOutputStream()
        fun text(name: String, v: String?) { if (!v.isNullOrBlank()) ilst.write(item(name, data(1, v.toByteArray(Charsets.UTF_8)))) }
        text("©nam", t.title); text("©ART", t.artist); text("©alb", t.album); text("aART", t.albumArtist)
        text("©day", t.year); text("©gen", t.genre); text("©cmt", t.comment); text("©lyr", t.lyrics)
        t.track?.takeIf { it > 0 }?.let { n ->
            val p = ByteArray(8); p[2] = (n ushr 8).toByte(); p[3] = n.toByte()
            t.totalTracks?.let { tt -> p[4] = (tt ushr 8).toByte(); p[5] = tt.toByte() }
            ilst.write(item("trkn", data(0, p)))
        }
        t.cover?.let { c -> ilst.write(item("covr", data(if (c.size > 4 && c[1] == 'P'.code.toByte() && c[2] == 'N'.code.toByte()) 14 else 13, c))) }
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + "mdir".toByteArray(Charsets.ISO_8859_1) + "appl".toByteArray(Charsets.ISO_8859_1) + ByteArray(9))
        val meta = box("meta", ByteArray(4) + hdlr + box("ilst", ilst.toByteArray()))
        return box("udta", meta)
    }

    private fun item(name: String, dataBox: ByteArray) = box(name, dataBox)
    private fun data(kind: Int, payload: ByteArray) = box("data", byteArrayOf(0, 0, 0, kind.toByte(), 0, 0, 0, 0) + payload)

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        putU32(out, 0, size.toLong())
        val t = type.toByteArray(Charsets.ISO_8859_1); System.arraycopy(t, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    private fun u32(b: ByteArray, p: Int): Long = ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)
    private fun putU32(b: ByteArray, p: Int, v: Long) { b[p] = (v ushr 24).toByte(); b[p + 1] = (v ushr 16).toByte(); b[p + 2] = (v ushr 8).toByte(); b[p + 3] = v.toByte() }
}
