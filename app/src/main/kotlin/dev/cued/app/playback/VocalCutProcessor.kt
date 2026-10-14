package dev.cued.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.cued.core.dsp.VocalCut
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Karaoke in the deck's pipeline: [VocalCut] on 16-bit stereo. Always present
 * as a pass-through; [enabled] and [strength] change live, like the silence
 * skipper, without rebuilding the player.
 */
@UnstableApi
class VocalCutProcessor : BaseAudioProcessor() {
    @Volatile var enabled: Boolean = false
    var strength: Float
        get() = cut.strength
        set(value) { cut.strength = value }

    private val cut = VocalCut(44_100)
    private var channels = 2
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        channels = inputAudioFormat.channelCount
        cut.sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (!enabled || channels != 2) {
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }
        val samples = remaining / 2
        if (scratch.size < samples) scratch = ShortArray(samples)
        val order = inputBuffer.order()
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(scratch, 0, samples)
        inputBuffer.position(inputBuffer.position() + samples * 2)
        inputBuffer.order(order)
        cut.process(scratch, samples / 2)
        val out = replaceOutputBuffer(samples * 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().put(scratch, 0, samples)
        out.position(out.position() + samples * 2)
        out.flip()
    }

    override fun onFlush() { cut.reset() }
    override fun onReset() { cut.reset() }
}
