package dev.cued.app.analysis

import android.content.Context
import dev.cued.core.dsp.SpectrogramImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persists static spectrograms as compact byte images in the cache dir.
 * ~32 KB per track at 512 columns x 64 bands; cheap enough to keep for a
 * whole library and regenerable if the cache is cleared.
 */
class SpectrogramStore(context: Context) {
    private val dir = File(context.cacheDir, "spectrograms").apply { mkdirs() }

    fun fileFor(trackId: Long) = File(dir, "$trackId.spg")

    fun save(trackId: Long, image: SpectrogramImage): File {
        val f = fileFor(trackId)
        DataOutputStream(f.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(image.frames)
            out.writeInt(image.bands)
            val bytes = ByteArray(image.data.size) { (image.data[it].coerceIn(0f, 1f) * 255f).toInt().toByte() }
            out.write(bytes)
        }
        return f
    }

    fun load(path: String): SpectrogramImage? {
        val f = File(path)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC) return null
                val frames = inp.readInt(); val bands = inp.readInt()
                val bytes = ByteArray(frames * bands)
                inp.readFully(bytes)
                SpectrogramImage(frames, bands, FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f })
            }
        }.getOrNull()
    }

    companion object { private const val MAGIC = 0x53504731 } // "SPG1"
}
