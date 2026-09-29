package dev.cued.app.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.cued.core.voice.VoiceCommands
import dev.cued.core.voice.VoiceCommands.Command

/**
 * In-app voice control. The system speech recogniser does the listening
 * (Google's, or any other RecognitionService the user installed; offline
 * packs work); [VoiceCommands] does the understanding with plain rules.
 */
class VoiceLauncher(private val launch: (Intent) -> Unit) {
    fun listen(prompt: String = "Say a command: play, pause, next, play <song / artist / playlist / genre>…") {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        launch(intent)
    }

    companion object {
        fun isAvailable(context: Context): Boolean =
            context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
}

@Composable
fun rememberVoiceLauncher(onText: (String) -> Unit): VoiceLauncher {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val texts = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        texts?.firstOrNull()?.let(onText)
    }
    return remember { VoiceLauncher { launcher.launch(it) } }
}

/** Tiny wrapper over the system TTS for spoken feedback in car mode. */
class Speaker(context: Context) {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }
    fun say(text: String) { if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cued") }
    fun shutdown() = tts.shutdown()
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}

/** Applies a parsed command to the player. Returns a short line to show or speak. */
@UnstableApi
suspend fun executeVoice(
    text: String,
    pvm: PlayerViewModel,
    context: Context,
    setCarMode: (Boolean) -> Unit,
): String {
    return when (val cmd = VoiceCommands.parse(text)) {
        Command.Pause -> { pvm.connection.player?.pause(); "Paused" }
        Command.Resume -> { pvm.togglePlayIfPaused(); "Playing" }
        Command.Next -> { pvm.next(); "Next track" }
        Command.Previous -> { pvm.previous(); "Previous track" }
        is Command.Shuffle -> { pvm.setShuffle(cmd.on ?: !pvm.state.value.shuffle); if (pvm.state.value.shuffle) "Shuffle on" else "Shuffle off" }
        is Command.Repeat -> {
            pvm.setRepeat(when (cmd.mode) { "one" -> Player.REPEAT_MODE_ONE; "all" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }); "Repeat ${cmd.mode}"
        }
        is Command.CarMode -> { setCarMode(cmd.on); if (cmd.on) "Car mode on" else "Car mode off" }
        is Command.Volume -> {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (cmd.up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            if (cmd.up) "Louder" else "Quieter"
        }
        Command.WhatsPlaying -> pvm.state.value.let { if (it.trackId == null) "Nothing is playing" else "${it.title} by ${it.artist}" }
        is Command.Play -> {
            val r = pvm.playTarget(cmd.target)
            if (r.tracks.isEmpty()) r.label else "Playing ${r.label}"
        }
        is Command.Unknown -> "Didn't catch that: \"${cmd.text}\""
    }
}

