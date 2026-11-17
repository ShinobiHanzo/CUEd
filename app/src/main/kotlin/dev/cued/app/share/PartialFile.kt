package dev.cued.app.share

import android.content.Context
import dev.cued.core.crypto.toHex
import dev.cued.core.nfc.NfcFileHeader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * What has arrived so far of a file coming over Bluetooth or NFC, kept in the
 * cache directory under the file's hash. An attempt that breaks off leaves it
 * there, and the next attempt (over either radio) carries on from its length.
 * The running digest covers the bytes an earlier attempt left, so the final
 * check is always over the whole file.
 */
class PartialFile(context: Context, private val header: NfcFileHeader) : AutoCloseable {
    val file: File = File(File(context.cacheDir, "transfer").apply { mkdirs() }, header.sha256 + ".part")
    private val md = MessageDigest.getInstance("SHA-256")
    private val out: FileOutputStream
    var have: Long
        private set

    init {
        if (file.exists() && file.length() > header.size) file.delete()
        if (file.exists()) file.inputStream().use { s ->
            val b = ByteArray(64 * 1024)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        have = if (file.exists()) file.length() else 0L
        out = FileOutputStream(file, true)
    }

    val complete: Boolean get() = have >= header.size

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        out.write(bytes, 0, length)
        md.update(bytes, 0, length)
        have += length
    }

    /** Closes the file and says whether every byte matches the sender's hash. A mismatch deletes it, so the next attempt starts clean. */
    fun verify(): Boolean {
        close()
        val ok = have == header.size && md.digest().toHex() == header.sha256
        if (!ok) file.delete()
        return ok
    }

    fun delete() { close(); file.delete() }

    override fun close() { runCatching { out.close() } }
}
