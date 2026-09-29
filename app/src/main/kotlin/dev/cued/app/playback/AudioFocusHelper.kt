package dev.cued.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** One audio-focus request for the whole two-deck engine. */
class AudioFocusHelper(context: Context, private val listener: (Event) -> Unit) {
    enum class Event { GAIN, LOSS, LOSS_TRANSIENT, DUCK }

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var held = false
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> listener(Event.GAIN)
                AudioManager.AUDIOFOCUS_LOSS -> { held = false; listener(Event.LOSS) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener(Event.LOSS_TRANSIENT)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener(Event.DUCK)
            }
        }
        .build()

    fun request(): Boolean {
        if (held) return true
        held = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun abandon() {
        if (!held) return
        am.abandonAudioFocusRequest(request)
        held = false
    }
}
