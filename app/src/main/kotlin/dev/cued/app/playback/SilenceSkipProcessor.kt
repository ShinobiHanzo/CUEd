package dev.cued.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow

/**
 * Drops silence longer than a tolerance. The first [toleranceMs] of any quiet
 * stretch is kept (so pauses between phrases still breathe), everything
 * beyond it is cut until sound returns. Dropped frames are counted so the
 * audio sink can keep the reported position in sync with the media.
 *
 * Always active as a pass-through; [enabled], [thresholdDb] and
 * [toleranceMs] can be changed at any time without reconfiguring the player.
 */
@UnstableApi
class SilenceSkipProcessor : BaseAudioProcessor() {
    @Volatile var enabled: Boolean = false
    /** Peak level below which a frame counts as silent, in dBFS (e.g. -50). */
    @Volatile var thresholdDb: Float = -50f
        set(value) { field = value; threshold = (32768.0 * 10.0.pow(value / 20.0)).toInt().coerceIn(1, 32767) }
    @Volatile var toleranceMs: Int = 700

    private var threshold = (32768.0 * 10.0.pow(-50.0 / 20.0)).toInt()
    private var channels = 2
    private var sampleRate = 44_100
    private var silentRun = 0L

    /** Total frames removed since the last reset; read by the processor chain. */
    @Volatile var skippedFrames: Long = 0L
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (!enabled) {
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }
        val frameBytes = 2 * channels
        val frames = remaining / frameBytes
        val toleranceFrames = toleranceMs.toLong() * sampleRate / 1000L
        val out = replaceOutputBuffer(remaining)
        val order = inputBuffer.order()
        var pos = inputBuffer.position()
        var kept = 0
        for (f in 0 until frames) {
            var peak = 0
            for (c in 0 until channels) {
                val s = abs(inputBuffer.getShort(pos + c * 2).toInt())
                if (s > peak) peak = s
            }
            val silent = peak < threshold
            val drop = silent && silentRun >= toleranceFrames
            if (silent) silentRun++ else silentRun = 0
            if (!drop) {
                for (b in 0 until frameBytes) out.put(inputBuffer.get(pos + b))
                kept++
            }
            pos += frameBytes
        }
        skippedFrames += (frames - kept)
        inputBuffer.position(pos)
        out.order(order)
        out.flip()
    }

    override fun onFlush() { silentRun = 0 }
    override fun onReset() { silentRun = 0; skippedFrames = 0 }
}
