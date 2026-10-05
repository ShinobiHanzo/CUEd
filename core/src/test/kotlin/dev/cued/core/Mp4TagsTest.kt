package dev.cued.core

import dev.cued.core.tag.Mp4Tags
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Mp4TagsTest {
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { a, b -> a + b }
        val size = 8 + payload.size
        return ByteBuffer.allocate(4).putInt(size).array() + type.toByteArray(Charsets.ISO_8859_1) + payload
    }
    private fun stco(vararg offsets: Int) = box("stco", ByteArray(4), ByteBuffer.allocate(4).putInt(offsets.size).array(), offsets.fold(ByteArray(0)) { a, o -> a + ByteBuffer.allocate(4).putInt(o).array() })
    private fun moov(stcoBox: ByteArray, extra: ByteArray = ByteArray(0)) =
        box("moov", box("mvhd", ByteArray(100)), box("trak", box("mdia", box("minf", box("stbl", stcoBox)))), extra)

    private fun u32(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).int.toLong() and 0xffffffffL
    private fun find(b: ByteArray, from: Int, to: Int, path: List<String>): Pair<Int, Int>? {
        var (f, t) = from to to
        for (name in path) {
            var p = f; var found: Pair<Int, Int>? = null
            while (p + 8 <= t) {
                val size = u32(b, p).toInt(); val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
                if (type == name) { found = (p + 8 + if (name == "meta") 4 else 0) to (p + size); break }
                p += size
            }
            found ?: return null
            f = found.first; t = found.second
        }
        return f to t
    }

    @Test fun moovBeforeMdatShiftsChunkOffsets() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4), "M4A mp42isom".toByteArray())
        val mv = moov(stco(0, 0)) // same shape as the real one, to measure where mdat lands
        val mdatPayload = ByteArray(500) { (it % 7).toByte() }
        val mdatOffset = ftyp.size + mv.size
        val realMoov = moov(stco(mdatOffset + 8, mdatOffset + 8 + 100))
        val input = File.createTempFile("mp4in", ".m4a"); input.writeBytes(ftyp + realMoov + box("mdat", mdatPayload))
        val output = File.createTempFile("mp4out", ".m4a")
        Mp4Tags.write(input, output, Mp4Tags.Tags(title = "How You Remind Me", artist = "Nickelback", album = "Silver Side Up", track = 2, year = "2001", cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)))
        val out = output.readBytes()
        RandomAccessFile(output, "r").use { raf ->
            val top = Mp4Tags.topLevel(raf)
            assertEquals(listOf("ftyp", "moov", "mdat"), top.map { it.type })
            val mdat = top.first { it.type == "mdat" }
            val stcoRange = find(out, top[1].offset.toInt() + 8, top[1].end.toInt(), listOf("trak", "mdia", "minf", "stbl", "stco"))!!
            val first = u32(out, stcoRange.first + 8); val second = u32(out, stcoRange.first + 12)
            assertEquals(mdat.offset + 8, first); assertEquals(mdat.offset + 8 + 100, second)
            // audio untouched
            assertTrue(out.copyOfRange(mdat.offset.toInt() + 8, mdat.end.toInt()).contentEquals(mdatPayload))
            // tags present
            val ilst = find(out, top[1].offset.toInt() + 8, top[1].end.toInt(), listOf("udta", "meta", "ilst"))!!
            val nam = find(out, ilst.first, ilst.second, listOf("©nam", "data"))!!
            assertEquals("How You Remind Me", String(out, nam.first + 8, nam.second - nam.first - 8, Charsets.UTF_8))
            val trkn = find(out, ilst.first, ilst.second, listOf("trkn", "data"))!!
            assertEquals(2, out[trkn.first + 8 + 3].toInt())
            val covr = find(out, ilst.first, ilst.second, listOf("covr", "data"))!!
            assertEquals(13, out[covr.first + 3].toInt()) // jpeg
        }
    }

    @Test fun moovAfterMdatLeavesOffsetsAlone() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4))
        val mdat = box("mdat", ByteArray(300))
        val mv = moov(stco(ftyp.size + 8), box("udta", box("meta", ByteArray(4)))) // existing udta is replaced
        val input = File.createTempFile("mp4in", ".m4a"); input.writeBytes(ftyp + mdat + mv)
        val output = File.createTempFile("mp4out", ".m4a")
        Mp4Tags.write(input, output, Mp4Tags.Tags(title = "x"))
        val out = output.readBytes()
        RandomAccessFile(output, "r").use { raf ->
            val top = Mp4Tags.topLevel(raf)
            assertEquals(listOf("ftyp", "mdat", "moov"), top.map { it.type })
            val stcoRange = find(out, top[2].offset.toInt() + 8, top[2].end.toInt(), listOf("trak", "mdia", "minf", "stbl", "stco"))!!
            assertEquals((ftyp.size + 8).toLong(), u32(out, stcoRange.first + 8))
            val udtas = Regex("udta").findAll(String(out, Charsets.ISO_8859_1)).count()
            assertEquals(1, udtas)
        }
    }

    @Test fun fragmentedIsRefused() {
        val f = File.createTempFile("mp4frag", ".m4a")
        f.writeBytes(box("ftyp", ByteArray(8)) + box("moov", box("mvhd", ByteArray(20)), box("mvex", ByteArray(8))) + box("moof", ByteArray(8)) + box("mdat", ByteArray(8)))
        assertTrue(Mp4Tags.isFragmented(f))
        assertFailsWith<Mp4Tags.UnsupportedLayout> { Mp4Tags.write(f, File.createTempFile("mp4o", ".m4a"), Mp4Tags.Tags(title = "x")) }
    }
}
