package dev.cued.core

import dev.cued.core.lyrics.Id3Lyrics
import dev.cued.core.lyrics.Lrc
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsTest {
    @Test
    fun `parses lrc with offset and repeated stamps`() {
        val lrc = """
            [ar:Someone]
            [offset:500]
            [00:12.00]First line
            [00:20.5][01:05.25]Chorus
            [00:30]Third
        """.trimIndent()
        assertTrue(Lrc.isSynced(lrc))
        val lines = Lrc.parse(lrc)
        assertEquals(listOf(11_500L, 20_000L, 29_500L, 64_750L), lines.map { it.timeMs })
        assertEquals("Chorus", lines[1].text)
        assertEquals("Chorus", lines[3].text)
        assertEquals(-1, Lrc.currentIndex(lines, 5_000))
        assertEquals(0, Lrc.currentIndex(lines, 15_000))
        assertEquals(2, Lrc.currentIndex(lines, 40_000))
        assertEquals(3, Lrc.currentIndex(lines, 90_000))
    }

    @Test
    fun `plain text becomes unsynced lines`() {
        val lines = Lrc.parse("\n\nHello\nworld\n\n")
        assertFalse(Lrc.isSynced("Hello\nworld"))
        assertEquals(listOf("Hello", "world"), lines.map { it.text })
        assertTrue(lines.all { it.timeMs == null })
        assertEquals(-1, Lrc.currentIndex(lines, 1_000))
    }

    private fun id3(major: Int, frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        for ((id, data) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            val n = data.size
            if (major == 4) body.write(byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()))
            else body.write(byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()))
            body.write(byteArrayOf(0, 0))
            body.write(data)
        }
        val b = body.toByteArray()
        val size = b.size
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0,
            ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte())
        return header + b + ByteArray(64) { 0x55 } // some "audio"
    }

    @Test
    fun `reads USLT from id3v2_4 utf8 and v2_3 utf16`() {
        val uslt4 = byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()) + "desc".toByteArray() + byteArrayOf(0) + "La la la\nsecond line".toByteArray(Charsets.UTF_8)
        val tag4 = id3(4, listOf("TIT2" to byteArrayOf(3) + "Title".toByteArray(), "USLT" to uslt4))
        assertEquals("La la la\nsecond line", Id3Lyrics.read(ByteArrayInputStream(tag4)))

        val text16 = "Ünïcode words".toByteArray(Charsets.UTF_16) // includes BOM
        val uslt3 = byteArrayOf(1, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()) + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + text16
        val tag3 = id3(3, listOf("USLT" to uslt3))
        assertEquals("Ünïcode words", Id3Lyrics.read(ByteArrayInputStream(tag3)))
    }

    @Test
    fun `no tag or no lyrics gives null`() {
        assertNull(Id3Lyrics.read(ByteArrayInputStream(ByteArray(100) { 1 })))
        assertNull(Id3Lyrics.read(ByteArrayInputStream(id3(4, listOf("TIT2" to byteArrayOf(3) + "x".toByteArray())))))
    }
}
