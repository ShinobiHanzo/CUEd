package dev.cued.core.tag

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A small, dependency-free ID3v2.3 writer for the MP3s the built-in
 * downloader produces. Every text frame is UTF-16 with a BOM (encoding 1),
 * which every Android version and desktop player reads. A 1 KB padding
 * block is left after the frames so small later edits need no rewrite.
 */
object Id3v2 {
    data class Picture(val mime: String, val bytes: ByteArray, val type: Int = 3 /* front cover */)

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val track: Int? = null,
        val year: String? = null,
        val genre: String? = null,
        val comment: String? = null,
        val lyrics: String? = null,
        val picture: Picture? = null,
    )

    private const val PADDING = 1024

    /** The complete tag: header + frames + padding. Prepend it to the audio with [prepend]. */
    fun build(t: Tags): ByteArray {
        val frames = ByteArrayOutputStream()
        fun text(id: String, v: String?) { if (!v.isNullOrBlank()) frame(frames, id, byteArrayOf(1) + utf16(v)) }
        text("TIT2", t.title); text("TPE1", t.artist); text("TALB", t.album); text("TPE2", t.albumArtist)
        t.track?.takeIf { it > 0 }?.let { text("TRCK", it.toString()) }
        text("TYER", t.year); text("TCON", t.genre)
        t.comment?.takeIf { it.isNotBlank() }?.let { frame(frames, "COMM", byteArrayOf(1) + "eng".toByteArray(Charsets.ISO_8859_1) + utf16("") + utf16(it)) }
        t.lyrics?.takeIf { it.isNotBlank() }?.let { frame(frames, "USLT", byteArrayOf(1) + "eng".toByteArray(Charsets.ISO_8859_1) + utf16("") + utf16(it)) }
        t.picture?.let { p ->
            frame(frames, "APIC", byteArrayOf(0) + p.mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, p.type.toByte(), 0) + p.bytes)
        }
        val body = frames.toByteArray()
        val size = body.size + PADDING
        val out = ByteArrayOutputStream(10 + size)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1)); out.write(3); out.write(0); out.write(0)
        out.write(syncsafe(size))
        out.write(body)
        out.write(ByteArray(PADDING))
        return out.toByteArray()
    }

    /** Bytes taken by an ID3v2 tag at the start of [head] (at least 10 bytes), or 0 if there is none. */
    fun existingTagSize(head: ByteArray): Int {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
        val footer = (head[5].toInt() and 0x10) != 0
        return 10 + size + if (footer) 10 else 0
    }

    /** Writes [tag] followed by the audio from [input] with any existing ID3v2 tag stripped. */
    fun prepend(tag: ByteArray, input: InputStream, output: OutputStream) {
        val head = ByteArray(10)
        var got = 0
        while (got < 10) { val n = input.read(head, got, 10 - got); if (n < 0) break; got += n }
        val skip = existingTagSize(head.copyOf(got))
        output.write(tag)
        if (skip == 0) output.write(head, 0, got)
        else { var left = skip - got; while (left > 0) { val n = input.skip(left.toLong()); if (n <= 0) break; left -= n.toInt() } }
        input.copyTo(output)
    }

    private fun frame(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val n = payload.size
        out.write(n ushr 24); out.write(n ushr 16); out.write(n ushr 8); out.write(n)
        out.write(0); out.write(0)
        out.write(payload)
    }

    private fun utf16(s: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)

    private fun syncsafe(n: Int) = byteArrayOf(((n ushr 21) and 0x7f).toByte(), ((n ushr 14) and 0x7f).toByte(), ((n ushr 7) and 0x7f).toByte(), (n and 0x7f).toByte())
}
