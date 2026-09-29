package dev.cued.core.lyrics

import java.io.InputStream

/**
 * Pulls unsynchronised lyrics (USLT) out of an ID3v2.3/2.4 tag without
 * loading the audio. spotdl embeds lyrics this way, so a downloaded track
 * often carries its own words with no network needed.
 */
object Id3Lyrics {

    /** Returns the USLT text, or null if the stream has no ID3v2 tag or no lyrics frame. */
    fun read(input: InputStream): String? {
        val header = ByteArray(10)
        if (readFully(input, header) < 10) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val major = header[3].toInt()
        if (major < 3 || major > 4) return null
        val flags = header[5].toInt()
        val tagSize = syncsafe(header, 6)
        if (tagSize <= 0 || tagSize > 16 * 1024 * 1024) return null
        val tag = ByteArray(tagSize)
        val got = readFully(input, tag)
        if (got <= 0) return null
        var pos = 0
        if (flags and 0x40 != 0) { // extended header
            val ext = if (major == 4) syncsafe(tag, 0) else be32(tag, 0) + 4
            pos += ext
        }
        val unsyncAll = major == 3 && (flags and 0x80 != 0)
        while (pos + 10 <= got) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (major == 4) syncsafe(tag, pos + 4) else be32(tag, pos + 4)
            val frameFlags = tag[pos + 9].toInt()
            pos += 10
            if (size <= 0 || pos + size > got) break
            if (id == "USLT") {
                var body = tag.copyOfRange(pos, pos + size)
                if (unsyncAll || (major == 4 && frameFlags and 0x02 != 0)) body = unsync(body)
                if (major == 4 && frameFlags and 0x01 != 0) body = body.copyOfRange(4, body.size) // data length indicator
                decodeUslt(body)?.let { return it }
            }
            pos += size
        }
        return null
    }

    private fun decodeUslt(b: ByteArray): String? {
        if (b.size < 4) return null
        val enc = b[0].toInt()
        var p = 4 // encoding + 3-byte language
        // content descriptor, terminated
        val (descEnd, after) = terminator(b, p, enc) ?: return null
        p = after
        val text = decode(b, p, b.size - p, enc).trimEnd('\u0000').trim()
        return text.takeIf { it.isNotBlank() }.also { if (descEnd < 0) return null }
    }

    private fun terminator(b: ByteArray, from: Int, enc: Int): Pair<Int, Int>? {
        var i = from
        if (enc == 0 || enc == 3) {
            while (i < b.size) { if (b[i].toInt() == 0) return i to i + 1; i++ }
        } else {
            while (i + 1 < b.size) { if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i to i + 2; i += 2 }
        }
        return null
    }

    private fun decode(b: ByteArray, off: Int, len: Int, enc: Int): String = when (enc) {
        1 -> String(b, off, len, Charsets.UTF_16)     // with BOM
        2 -> String(b, off, len, Charsets.UTF_16BE)
        3 -> String(b, off, len, Charsets.UTF_8)
        else -> String(b, off, len, Charsets.ISO_8859_1)
    }

    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i].toInt() == 0xFF.toByte().toInt() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
