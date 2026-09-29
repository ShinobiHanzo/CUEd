package dev.cued.app.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes any audio MediaCodec understands to mono float PCM, decimated to a
 * low rate, streaming chunks to a callback so a whole track never has to sit
 * in memory. Uses only the platform codecs: no ffmpeg, no native code.
 */
class AudioDecoder(private val context: Context) {

    class Info(val sampleRate: Int, val channels: Int, val outputRate: Int, val durationUs: Long)

    /**
     * @param targetRate desired output rate; the actual rate is the source rate divided by an
     *        integer factor, reported in [Info.outputRate].
     * @param maxSeconds stop after this much audio (of the source) has been decoded.
     * @param onChunk receives mono float samples in -1..1; the array is reused between calls.
     */
    fun decode(uri: Uri, targetRate: Int, maxSeconds: Int, onChunk: (samples: FloatArray, count: Int) -> Unit): Info {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIndex = i; format = f; break }
            }
            require(trackIndex >= 0 && format != null) { "No audio track in $uri" }
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var factor = maxOf(1, sampleRate / targetRate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxFrames = maxSeconds.toLong() * sampleRate
            var framesIn = 0L
            var chunk = FloatArray(8192)
            var chunkCount = 0
            var acc = 0f
            var accCount = 0

            fun emit(sample: Float) {
                acc += sample; accCount++
                if (accCount == factor) {
                    if (chunkCount == chunk.size) { onChunk(chunk, chunkCount); chunkCount = 0 }
                    chunk[chunkCount++] = acc / factor
                    acc = 0f; accCount = 0
                }
            }

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || framesIn >= maxFrames) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        factor = maxOf(1, sampleRate / targetRate)
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        if (pcmFloat) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (fr in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += fb.get(fr * channels + c)
                                emit(s / channels)
                            }
                            framesIn += frames
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / channels
                            val scale = 1f / 32768f / channels
                            for (fr in 0 until frames) {
                                var s = 0
                                for (c in 0 until channels) s += sb.get(fr * channels + c)
                                emit(s * scale)
                            }
                            framesIn += frames
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (chunkCount > 0) onChunk(chunk, chunkCount)
            return Info(sampleRate, channels, sampleRate / factor, durationUs)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
