package dev.cued.core

import dev.cued.core.lyrics.Id3Lyrics
import dev.cued.core.tag.Id3v2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Id3v2Test {
    private val cover = ByteArray(300) { (it * 7).toByte() }
    private val tags = Id3v2.Tags(title = "Around the World", artist = "Daft Punk", album = "Homework", albumArtist = "Daft Punk", track = 7, year = "1997", genre = "house",
        comment = "https://open.spotify.com/track/x", lyrics = "[00:01.00]Around the world\n[00:03.00]Around the world", picture = Id3v2.Picture("image/jpeg", cover))

    /** Minimal frame walk so the test does not trust the writer to check itself. */
    private fun frames(tag: ByteArray): Map<String, ByteArray> {
        assertEquals("ID3", String(tag, 0, 3, Charsets.ISO_8859_1)); assertEquals(3, tag[3].toInt())
        val size = Id3v2.existingTagSize(tag) - 10
        val out = HashMap<String, ByteArray>()
        var p = 10
        while (p + 10 <= 10 + size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val n = ((tag[p + 4].toInt() and 0xff) shl 24) or ((tag[p + 5].toInt() and 0xff) shl 16) or ((tag[p + 6].toInt() and 0xff) shl 8) or (tag[p + 7].toInt() and 0xff)
            out[id] = tag.copyOfRange(p + 10, p + 10 + n)
            p += 10 + n
        }
        return out
    }

    private fun utf16(payload: ByteArray): String { // encoding byte, BOM, UTF-16LE, terminator
        assertEquals(1, payload[0].toInt()); assertEquals(0xFF, payload[1].toInt() and 0xff); assertEquals(0xFE, payload[2].toInt() and 0xff)
        return String(payload, 3, payload.size - 5, Charsets.UTF_16LE)
    }

    @Test fun writesTextFramesAndCover() {
        val f = frames(Id3v2.build(tags))
        assertEquals("Around the World", utf16(f.getValue("TIT2")))
        assertEquals("Daft Punk", utf16(f.getValue("TPE1")))
        assertEquals("Homework", utf16(f.getValue("TALB")))
        assertEquals("7", utf16(f.getValue("TRCK")))
        assertEquals("1997", utf16(f.getValue("TYER")))
        assertEquals("house", utf16(f.getValue("TCON")))
        val apic = f.getValue("APIC")
        assertEquals(0, apic[0].toInt())
        assertEquals("image/jpeg", String(apic, 1, 10, Charsets.ISO_8859_1))
        assertEquals(3, apic[12].toInt()) // picture type after mime NUL
        assertContentEquals(cover, apic.copyOfRange(apic.size - cover.size, apic.size))
    }

    @Test fun lyricsRoundTripThroughTheReader() {
        val tag = Id3v2.build(tags)
        val mp3 = tag + ByteArray(2000) { 0x55 }
        assertEquals(tags.lyrics, Id3Lyrics.read(ByteArrayInputStream(mp3)))
    }

    @Test fun prependStripsAnOldTag() {
        val old = Id3v2.build(Id3v2.Tags(title = "old"))
        val audio = ByteArray(5000) { (it % 251).toByte() }
        val fresh = Id3v2.build(Id3v2.Tags(title = "new"))
        val out = ByteArrayOutputStream()
        Id3v2.prepend(fresh, ByteArrayInputStream(old + audio), out)
        val bytes = out.toByteArray()
        assertEquals(fresh.size + audio.size, bytes.size)
        assertEquals("new", utf16(frames(bytes).getValue("TIT2")))
        assertContentEquals(audio, bytes.copyOfRange(fresh.size, bytes.size))
        // and with no old tag, nothing is lost
        val out2 = ByteArrayOutputStream(); Id3v2.prepend(fresh, ByteArrayInputStream(audio), out2)
        assertContentEquals(audio, out2.toByteArray().copyOfRange(fresh.size, out2.size()))
        assertTrue(Id3v2.existingTagSize(audio) == 0)
    }
}
