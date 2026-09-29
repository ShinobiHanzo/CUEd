package dev.cued.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.cued.core.dsp.Spectrogram
import dev.cued.core.dsp.StreamingSpectrogram
import java.nio.ByteBuffer

/**
 * A pass-through [AudioProcessor] that mirrors decoded PCM into a spectrum
 * analyser. Sits in ExoPlayer's audio sink chain, so it sees exactly what we
 * play, needs no microphone permission and works with the two-deck engine.
 */
@UnstableApi
class SpectrumTapProcessor(private val deck: Int, private val bus: SpectrumBus) : BaseAudioProcessor() {
    private var channels = 2
    private var streaming: StreamingSpectrogram? = null
    private var mono = FloatArray(4096)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        val spec = Spectrogram(
            fftSize = 2048, hopSize = 1024, bands = bus.bands,
            sampleRate = inputAudioFormat.sampleRate, minHz = 30f, maxHz = 16_000f,
        )
        streaming = StreamingSpectrogram(spec) { frame -> bus.publish(deck, frame) }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        // Tap a copy for analysis.
        val tap = inputBuffer.asReadOnlyBuffer().order(inputBuffer.order())
        val frames = remaining / (2 * channels)
        if (mono.size < frames) mono = FloatArray(frames)
        val scale = 1f / 32768f / channels
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += tap.getShort().toInt()
            mono[f] = acc * scale
        }
        streaming?.push(mono, frames)
        // Pass the audio through untouched.
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() { streaming?.finish() }
    override fun onReset() { streaming = null }
}
