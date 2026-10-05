package dev.cued.app.tagging

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.cued.app.util.DebugLog
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies the AAC track of a fragmented (DASH) MP4, which is what YouTube
 * serves, into a plain MP4 with the platform muxer. No re-encoding: the
 * samples are written as they are, so quality is identical and the result
 * is a file every tagger and player understands.
 */
object Remux {
    fun toPlainMp4(input: File, output: File) {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(input.absolutePath)
            var track = -1; var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { track = i; format = f; break }
            }
            require(track >= 0 && format != null) { "no audio track in ${input.name}" }
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            require(mime == MediaFormat.MIMETYPE_AUDIO_AAC) { "MP4 remux only carries AAC, this is $mime" }
            ex.selectTrack(track)
            mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dst = mux.addTrack(format)
            mux.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var samples = 0L
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0; info.size = n; info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mux.writeSampleData(dst, buf, info)
                samples++
                ex.advance()
            }
            mux.stop()
            DebugLog.d("remux", "${input.name}: $samples AAC samples → ${output.length()} bytes")
        } finally {
            runCatching { mux?.release() }
            runCatching { ex.release() }
        }
        check(output.length() > 10_000) { "remux produced ${output.length()} bytes" }
    }
}
