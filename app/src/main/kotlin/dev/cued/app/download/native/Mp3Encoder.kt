package dev.cued.app.download.native

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.cued.app.util.DebugLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * m4a → mp3 without ffmpeg: the platform decoder turns AAC into 16-bit PCM
 * (a temporary WAV), then jump3r, a pure-Java port of LAME, encodes it.
 * Slower than native LAME (roughly 2-4x faster than realtime on a mid phone)
 * but needs nothing installed.
 */
object Mp3Encoder {
    fun encode(input: File, output: File, kbps: Int = 192, onProgress: (Float) -> Unit) {
        val wav = File(output.parentFile, output.nameWithoutExtension + ".wav")
        try {
            decodeToWav(input, wav) { onProgress(it * 0.4f) }
            DebugLog.d("mp3", "wav ready ${wav.length()} bytes, encoding at $kbps kbps")
            val rc = de.sciss.jump3r.Main().run(arrayOf("--quiet", "-b", kbps.toString(), "-q", "5", "--id3v2-only", wav.absolutePath, output.absolutePath))
            check(rc == 0 && output.length() > 10_000) { "mp3 encoder returned $rc (${output.length()} bytes)" }
            onProgress(1f)
        } finally { wav.delete() }
    }

    /** Decodes any platform-supported audio to 16-bit little-endian PCM WAV at the source rate/channels. */
    fun decodeToWav(input: File, wav: File, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        RandomAccessFile(wav, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(44)) // header placeholder
            try {
                extractor.setDataSource(input.absolutePath)
                var trackIndex = -1; var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIndex = i; format = f; break }
                }
                require(trackIndex >= 0 && format != null) { "no audio track" }
                extractor.selectTrack(trackIndex)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var pcmFloat = false
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
                val info = MediaCodec.BufferInfo()
                var inputDone = false; var outputDone = false
                val conv = ByteArray(1 shl 16)
                while (!outputDone) {
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) { codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val oidx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                        oidx >= 0 -> {
                            val buf = codec.getOutputBuffer(oidx)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size); buf.order(ByteOrder.nativeOrder())
                            if (pcmFloat) {
                                val fb = buf.asFloatBuffer(); val n = fb.remaining()
                                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                                for (i in 0 until n) bb.putShort((fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                                out.write(bb.array())
                            } else if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                                while (buf.hasRemaining()) { val n = minOf(conv.size, buf.remaining()); buf.get(conv, 0, n); out.write(conv, 0, n) }
                            } else {
                                val sb = buf.asShortBuffer(); val n = sb.remaining()
                                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                                for (i in 0 until n) bb.putShort(sb.get(i))
                                out.write(bb.array())
                            }
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            codec.releaseOutputBuffer(oidx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                val dataLen = out.length() - 44
                out.seek(0)
                out.write(wavHeader(dataLen, sampleRate, channels))
            } finally {
                runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
            }
        }
    }

    private fun wavHeader(dataLen: Long, rate: Int, channels: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort()); b.putInt(rate)
        b.putInt(rate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }
}
