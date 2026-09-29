package dev.cued.app.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * One deck's audio pipeline: silence skipping → spectrum tap → time-stretch.
 * Replaces ExoPlayer's default chain so the sink learns how many frames the
 * silence skipper removed and keeps the playback position accurate.
 */
@UnstableApi
class CuedAudioProcessorChain(
    val silenceSkip: SilenceSkipProcessor,
    private val tap: SpectrumTapProcessor,
) : AudioProcessorChain {
    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(silenceSkip, tap, sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    /** Skipping is driven from CUEd's own settings, not ExoPlayer's flag. */
    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long = sonic.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = silenceSkip.skippedFrames
}
