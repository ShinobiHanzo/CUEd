package dev.cued.app.download.native

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.cued.app.util.DebugLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM WAV → AAC in a plain MP4, with the platform encoder and muxer. Used
 * when the user wants m4a but YouTube only offered an Opus stream, so the
 * file still ends up as a real m4a that every tagger and player reads.
 */
object AacEncoder {
    fun encode(wav: File, output: File, kbps: Int = 192, onProgress: (Float) -> Unit) {
        val (sampleRate, channels, dataOffset, dataLen) = readWavHeader(wav)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels
        RandomAccessFile(wav, "r").use { inp ->
            inp.seek(dataOffset)
            var remaining = dataLen
            var ptsUs = 0L
            var inputDone = false
            var outputDone = false
            val chunk = ByteArray(16 * 1024)
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            val want = minOf(chunk.size.toLong(), remaining, buf.capacity().toLong()).toInt()
                            val n = if (want > 0) inp.read(chunk, 0, want) else -1
                            if (n <= 0) { codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else {
                                buf.clear(); buf.put(chunk, 0, n)
                                codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                                ptsUs += n / bytesPerFrame * 1_000_000L / sampleRate
                                remaining -= n
                                onProgress(((dataLen - remaining).toFloat() / dataLen).coerceIn(0f, 0.99f))
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(codec.outputFormat); mux.start() }
                        out >= 0 -> {
                            val ob = codec.getOutputBuffer(out)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) mux.writeSampleData(track, ob, info)
                            codec.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                if (track >= 0) mux.stop()
            } finally {
                runCatching { mux.release() }; runCatching { codec.stop() }; runCatching { codec.release() }
            }
        }
        check(output.length() > 10_000) { "AAC encoder produced ${output.length()} bytes" }
        DebugLog.d("aac", "encoded ${output.length()} bytes at $kbps kbps, ${sampleRate} Hz ${channels}ch")
        onProgress(1f)
    }

    private data class Wav(val sampleRate: Int, val channels: Int, val dataOffset: Long, val dataLen: Long)

    private fun readWavHeader(f: File): Wav = RandomAccessFile(f, "r").use { r ->
        val head = ByteArray(12); r.readFully(head)
        require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "not a WAV file" }
        var sampleRate = 44100; var channels = 2
        var pos = 12L
        while (pos + 8 <= r.length()) {
            r.seek(pos); val h = ByteArray(8); r.readFully(h)
            val id = String(h, 0, 4); val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (id == "fmt ") {
                val fmt = ByteArray(16); r.readFully(fmt)
                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                bb.short; channels = bb.short.toInt(); sampleRate = bb.int
            } else if (id == "data") {
                val len = if (size == 0L || pos + 8 + size > r.length()) r.length() - pos - 8 else size
                return Wav(sampleRate, channels, pos + 8, len)
            }
            pos += 8 + size + (size and 1)
        }
        error("WAV has no data chunk")
    }
}
