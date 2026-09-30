package dev.cued.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.cued.app.data.PlaybackSettings
import dev.cued.core.mix.Crossfade
import dev.cued.core.mix.CrossfadeCurve
import dev.cued.core.mix.TempoMatcher
import dev.cued.core.mix.TrackTempo
import dev.cued.core.mix.TransitionPlan
import dev.cued.core.mix.TransitionPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Supplies tempo analysis for a media id; the engine asks for it a little before a transition. */
fun interface TempoProvider {
    suspend fun tempo(mediaId: String, urgent: Boolean): TrackTempo?
}

/** Live info about a transition, for the UI. */
data class TransitionInfo(
    val progress: Float,
    val plan: TransitionPlan,
    val outgoingBpm: Float?,
    val incomingBpm: Float?,
)

/**
 * A [Player] built from two ExoPlayers ("decks") so consecutive tracks can
 * overlap. ExoPlayer itself is strictly one-item-at-a-time; crossfades need
 * two independent audio pipelines, so we run two and blend their volumes
 * and playback speeds ourselves.
 *
 * Exposed through [SimpleBasePlayer] so MediaSession, the notification,
 * Bluetooth/headset buttons and Android Auto all see one ordinary player.
 */
@UnstableApi
class CrossfadePlayer(
    private val context: Context,
    val spectrumBus: SpectrumBus,
    private val tempoProvider: TempoProvider,
    private val settings: () -> PlaybackSettings,
    private val onTrackFinished: (mediaId: String, playedFraction: Float) -> Unit,
    /** Called every few seconds while playing, and on pause, so long plays can remember where they are. */
    private val onProgress: (item: MediaItem, positionMs: Long) -> Unit = { _, _ -> },
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Decks -----------------------------------------------------------

    private inner class Deck(val index: Int) {
        val player: ExoPlayer
        val silenceSkip = SilenceSkipProcessor()
        var gain = 1f
        var speed = 1f
        var item: MediaItem? = null

        init {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context)
                        .setAudioProcessorChain(CuedAudioProcessorChain(silenceSkip, SpectrumTapProcessor(index, spectrumBus)))
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
            }
            player = ExoPlayer.Builder(context, renderers)
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
                .setHandleAudioBecomingNoisy(false)
                .build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && this@Deck === active && transition == null) onActiveEnded()
                    invalidateState()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { invalidateState() }
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "deck $index error", error)
                    dev.cued.app.util.DebugLog.e("player", "deck $index error on ${item?.mediaId}: ${error.errorCodeName}", error)
                    if (this@Deck === active) { lastError = error; skipBroken() } else cancelTransition()
                    invalidateState()
                }
            })
        }

        fun load(mediaItem: MediaItem, positionMs: Long) {
            item = mediaItem
            var start = if (positionMs == C.TIME_UNSET) 0L else positionMs
            // Long plays pick up where they were left; songs always start from the top.
            if (start == 0L && MediaItems.isLong(mediaItem)) start = MediaItems.resumeMs(mediaItem)
            if (!MediaItems.isLong(mediaItem)) userSpeed = 1f
            player.setMediaItem(mediaItem, start)
            if (prepared) player.prepare()
            player.playWhenReady = false
            applySpeed(userSpeed)
        }

        fun applyGain(g: Float) { gain = g; player.volume = (g * masterVolume).coerceIn(0f, 1f); spectrumBus.setGain(index, g) }
        fun applySpeed(s: Float) {
            if (kotlin.math.abs(speed - s) < 0.0005f) return
            speed = s
            player.playbackParameters = PlaybackParameters(s, 1f)
        }
        fun stopAndClear() {
            player.stop()
            player.clearMediaItems()
            item = null
            applySpeed(1f)
            applyGain(1f)
        }
    }

    private val decks = arrayOf(Deck(0), Deck(1))
    private var active: Deck = decks[0]
    private val other: Deck get() = decks[1 - active.index]

    // ---- Playlist state --------------------------------------------------

    private val playlist = ArrayList<MediaItem>()
    private val uids = ArrayList<Any>()
    private var originalOrder: List<MediaItem>? = null
    private var currentIndex = 0
    private var playWhenReady = false
    private var prepared = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var masterVolume = 1f
    /** Listener-chosen playback speed (podcast controls); music always resets to 1x. */
    private var userSpeed = 1f
    private var lastProgressAt = 0L
    private var lastError: PlaybackException? = null

    // ---- Transition state ------------------------------------------------

    private class Transition(val outgoing: Deck, val incoming: Deck, val plan: TransitionPlan, val nextIndex: Int, var midpointDone: Boolean = false, val outgoingBpm: Float?, val incomingBpm: Float?)
    private var transition: Transition? = null
    private var armedPlan: TransitionPlan? = null
    private var armedForIndex = -1
    private var armingJob: Job? = null
    private var armedTempos: Pair<Float?, Float?> = null to null

    private val _transitionInfo = MutableStateFlow<TransitionInfo?>(null)
    val transitionInfo: StateFlow<TransitionInfo?> = _transitionInfo
    private val _currentTempo = MutableStateFlow<TrackTempo?>(null)
    val currentTempo: StateFlow<TrackTempo?> = _currentTempo

    private val focus = AudioFocusHelper(context) { event ->
        when (event) {
            AudioFocusHelper.Event.GAIN -> { masterDuck(1f); if (resumeOnFocusGain) { resumeOnFocusGain = false; setPlayWhenReady(true) } }
            AudioFocusHelper.Event.LOSS -> { resumeOnFocusGain = false; setPlayWhenReady(false) }
            AudioFocusHelper.Event.LOSS_TRANSIENT -> { resumeOnFocusGain = playWhenReady; setPlayWhenReady(false) }
            AudioFocusHelper.Event.DUCK -> masterDuck(0.25f)
        }
    }
    private var resumeOnFocusGain = false
    private var duck = 1f
    private fun masterDuck(level: Float) { duck = level; decks.forEach { it.applyGain(it.gain) } }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) setPlayWhenReady(false)
        }
    }

    init {
        androidx.core.content.ContextCompat.registerReceiver(context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---- SimpleBasePlayer: state -------------------------------------------

    override fun getState(): State {
        val ap = active.player
        val playbackState = when {
            playlist.isEmpty() || !prepared -> Player.STATE_IDLE
            else -> when (ap.playbackState) {
                Player.STATE_IDLE -> if (lastError != null) Player.STATE_IDLE else Player.STATE_BUFFERING
                Player.STATE_BUFFERING -> Player.STATE_BUFFERING
                Player.STATE_READY -> Player.STATE_READY
                Player.STATE_ENDED -> if (transition != null) Player.STATE_READY else Player.STATE_ENDED
                else -> Player.STATE_IDLE
            }
        }
        val items = playlist.mapIndexed { i, item ->
            val known = MediaItems.durationMs(item)
            val durationMs = if (i == currentIndex && ap.duration != C.TIME_UNSET && ap.duration > 0) ap.duration else known
            MediaItemData.Builder(uids[i])
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(if (durationMs > 0) durationMs * 1000L else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(lastError)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else currentIndex.coerceIn(0, playlist.size - 1))
            .setContentPositionMs(PositionSupplier { activePositionMs() })
            .setContentBufferedPositionMs(PositionSupplier { if (transition != null) activePositionMs() else ap.bufferedPosition.coerceAtLeast(0L) })
            .setTotalBufferedDurationMs(PositionSupplier { ap.totalBufferedDuration.coerceAtLeast(0L) })
            .setIsLoading(ap.isLoading && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffle)
            .setVolume(masterVolume)
            .setAudioAttributes(AudioAttributes.DEFAULT)
            .setPlaybackParameters(PlaybackParameters(userSpeed, 1f))
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .setMaxSeekToPreviousPositionMs(3_000L)
            .build()
    }

    private fun activePositionMs(): Long {
        val p = active.player.currentPosition
        return if (p == C.TIME_UNSET) 0L else p.coerceAtLeast(0L)
    }

    // ---- SimpleBasePlayer: handlers ---------------------------------------

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            if (!focus.request()) { this.playWhenReady = false; return DONE }
            active.player.playWhenReady = true
            transition?.incoming?.player?.playWhenReady = true
            startTicking()
        } else {
            active.player.playWhenReady = false
            transition?.incoming?.player?.playWhenReady = false
            stopTicking()
            reportProgress(force = true)
            if (!resumeOnFocusGain) focus.abandon()
        }
        return DONE
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        lastError = null
        if (playlist.isNotEmpty()) {
            if (active.item == null) active.load(playlist[currentIndex], 0L)
            active.player.prepare()
            active.player.playWhenReady = playWhenReady
            transition?.incoming?.player?.prepare()
        }
        return DONE
    }

    override fun handleStop(): ListenableFuture<*> {
        cancelTransition()
        active.player.stop()
        prepared = false
        stopTicking()
        focus.abandon()
        return DONE
    }

    override fun handleRelease(): ListenableFuture<*> {
        stopTicking()
        cancelTransition()
        decks.forEach { it.player.release() }
        runCatching { context.unregisterReceiver(noisyReceiver) }
        focus.abandon()
        return DONE
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        disarm()
        return DONE
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffle == shuffleModeEnabled) return DONE
        shuffle = shuffleModeEnabled
        val current = playlist.getOrNull(currentIndex)
        if (shuffle) {
            originalOrder = playlist.toList()
            val rest = playlist.filterIndexed { i, _ -> i != currentIndex }.shuffled()
            replacePlaylist(listOfNotNull(current) + rest)
            currentIndex = 0
        } else {
            val orig = originalOrder ?: playlist.toList()
            originalOrder = null
            replacePlaylist(orig.filter { it in playlist })
            currentIndex = current?.let { playlist.indexOf(it) }?.coerceAtLeast(0) ?: 0
        }
        disarm()
        return DONE
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        masterVolume = volume.coerceIn(0f, 1f)
        decks.forEach { it.applyGain(it.gain) }
        return DONE
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        userSpeed = playbackParameters.speed.coerceIn(0.5f, 3f)
        if (transition == null) active.applySpeed(userSpeed)
        return DONE
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        cancelTransition()
        finishCurrentAsSkipped()
        originalOrder = null
        replacePlaylist(mediaItems)
        currentIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        if (shuffle && playlist.size > 1) {
            val current = playlist[currentIndex]
            originalOrder = playlist.toList()
            replacePlaylist(listOf(current) + playlist.filterIndexed { i, _ -> i != currentIndex }.shuffled())
            currentIndex = 0
        }
        lastError = null
        if (playlist.isEmpty()) { active.stopAndClear(); return DONE }
        active.stopAndClear()
        active.load(playlist[currentIndex], startPositionMs)
        active.player.playWhenReady = playWhenReady
        disarm()
        return DONE
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val at = index.coerceIn(0, playlist.size)
        playlist.addAll(at, mediaItems)
        uids.addAll(at, mediaItems.map { Any() })
        if (at <= currentIndex && playlist.size > mediaItems.size) currentIndex += mediaItems.size
        if (active.item == null && playlist.isNotEmpty()) {
            currentIndex = currentIndex.coerceIn(0, playlist.size - 1)
            active.load(playlist[currentIndex], 0L)
            active.player.playWhenReady = playWhenReady
        }
        disarm()
        return DONE
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val to = toIndex.coerceAtMost(playlist.size)
        if (fromIndex >= to) return DONE
        val removingCurrent = currentIndex in fromIndex until to
        for (i in (to - 1) downTo fromIndex) { playlist.removeAt(i); uids.removeAt(i) }
        when {
            playlist.isEmpty() -> { cancelTransition(); active.stopAndClear(); currentIndex = 0 }
            removingCurrent -> {
                cancelTransition()
                currentIndex = fromIndex.coerceIn(0, playlist.size - 1)
                active.stopAndClear()
                active.load(playlist[currentIndex], 0L)
                active.player.playWhenReady = playWhenReady
            }
            currentIndex >= to -> currentIndex -= (to - fromIndex)
        }
        disarm()
        return DONE
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val moving = playlist.subList(fromIndex, toIndex).toList()
        val movingUids = uids.subList(fromIndex, toIndex).toList()
        val currentItem = playlist.getOrNull(currentIndex)
        for (i in (toIndex - 1) downTo fromIndex) { playlist.removeAt(i); uids.removeAt(i) }
        val insertAt = newIndex.coerceIn(0, playlist.size)
        playlist.addAll(insertAt, moving)
        uids.addAll(insertAt, movingUids)
        currentIndex = currentItem?.let { playlist.indexOf(it) }?.coerceAtLeast(0) ?: 0
        disarm()
        return DONE
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        handleRemoveMediaItems(fromIndex, toIndex)
        handleAddMediaItems(fromIndex, mediaItems)
        return DONE
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (playlist.isEmpty()) return DONE
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> nextIndex() ?: return DONE
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                if (activePositionMs() > 3_000L && seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS) currentIndex else previousIndex()
            }
            else -> if (mediaItemIndex == C.INDEX_UNSET) currentIndex else mediaItemIndex.coerceIn(0, playlist.size - 1)
        }
        val pos = if (positionMs == C.TIME_UNSET) 0L else positionMs
        if (transition != null) {
            val t = transition!!
            // Seeking during a blend: settle on the incoming track immediately.
            if (target == t.nextIndex && (seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
                completeTransitionNow(); return DONE
            }
            cancelTransition()
        }
        if (target != currentIndex) {
            finishCurrentAsSkipped()
            currentIndex = target
            active.stopAndClear()
            active.load(playlist[currentIndex], pos)
            active.player.playWhenReady = playWhenReady
            lastError = null
        } else {
            active.applySpeed(1f); active.applyGain(1f)
            if (active.player.playbackState == Player.STATE_IDLE && prepared) active.player.prepare()
            active.player.seekTo(pos)
        }
        disarm()
        return DONE
    }

    // ---- Playlist helpers ------------------------------------------------

    private fun replacePlaylist(items: List<MediaItem>) {
        playlist.clear(); uids.clear()
        playlist.addAll(items)
        repeat(items.size) { uids.add(Any()) }
    }

    private fun nextIndex(): Int? = when {
        playlist.isEmpty() -> null
        repeatMode == Player.REPEAT_MODE_ONE -> currentIndex
        currentIndex + 1 < playlist.size -> currentIndex + 1
        repeatMode == Player.REPEAT_MODE_ALL -> 0
        else -> null
    }

    private fun previousIndex(): Int = when {
        currentIndex > 0 -> currentIndex - 1
        repeatMode == Player.REPEAT_MODE_ALL -> playlist.size - 1
        else -> 0
    }

    private fun finishCurrentAsSkipped() {
        val item = active.item ?: return
        val dur = active.player.duration
        val fraction = if (dur > 0) (activePositionMs().toFloat() / dur).coerceIn(0f, 1f) else 0f
        onTrackFinished(item.mediaId, fraction)
    }

    private fun onActiveEnded() {
        active.item?.let { onTrackFinished(it.mediaId, 1f) }
        val next = nextIndex()
        if (next == null) { stopTicking(); invalidateState(); return }
        currentIndex = next
        active.stopAndClear()
        active.load(playlist[currentIndex], 0L)
        active.player.playWhenReady = playWhenReady
        disarm()
        invalidateState()
    }

    private fun skipBroken() {
        val next = nextIndex()
        if (next == null || next == currentIndex) { stopTicking(); return }
        handler.post {
            currentIndex = next
            active.stopAndClear()
            active.load(playlist[currentIndex], 0L)
            active.player.playWhenReady = playWhenReady
            lastError = null
            invalidateState()
        }
    }

    // ---- Tick loop: arms and runs transitions -------------------------------

    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            runCatching { onTick() }.onFailure { Log.w(TAG, "tick", it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicking() { if (!ticking) { ticking = true; handler.post(tick) } }
    private fun stopTicking() { ticking = false; handler.removeCallbacks(tick) }

    private fun reportProgress(force: Boolean = false) {
        val item = active.item ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastProgressAt < PROGRESS_EVERY_MS) return
        lastProgressAt = now
        onProgress(item, activePositionMs())
    }

    private fun onTick() {
        reportProgress()
        val t = transition
        if (t != null) { runTransition(t); return }
        val s = settings()
        if (!s.crossfadeEnabled && !s.tempoMatch) return
        // Podcasts and audiobooks are never blended into or out of.
        val next0 = nextIndex() ?: return
        if (MediaItems.isLong(playlist[currentIndex]) || MediaItems.isLong(playlist[next0])) return
        val ap = active.player
        if (ap.playbackState != Player.STATE_READY) return
        val duration = ap.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = activePositionMs()
        val remaining = duration - position
        val next = nextIndex() ?: return
        if (armedForIndex != currentIndex && remaining < ARM_BEFORE_END_MS) arm(next, s, duration)
        val plan = armedPlan ?: return
        if (position >= plan.startAtOutgoingMs) beginTransition(plan, next)
    }

    private fun disarm() {
        armingJob?.cancel(); armingJob = null
        armedPlan = null; armedForIndex = -1
        _currentTempo.value = null
    }

    private fun arm(next: Int, s: PlaybackSettings, durationMs: Long) {
        armedForIndex = currentIndex
        val currentId = playlist[currentIndex].mediaId
        val nextId = playlist[next].mediaId
        armingJob?.cancel()
        armingJob = scope.launch {
            val outgoing = runCatching { tempoProvider.tempo(currentId, urgent = false) }.getOrNull()
            val incoming = runCatching { tempoProvider.tempo(nextId, urgent = true) }.getOrNull()
            _currentTempo.value = outgoing
            val out = outgoing?.copy(durationMs = durationMs) ?: TrackTempo(durationMs, 0f, 0f, 0f)
            // Tempo-match is its own mode with its own duration. If it is on and the
            // two tracks relate by a simple ratio, use it; otherwise fall back to the
            // standard crossfade when that is on, or to a gapless cut.
            var plan: TransitionPlan? = null
            if (s.tempoMatch) {
                val matched = TransitionPlanner.plan(
                    outgoing = out, incoming = incoming, crossfadeMs = s.tempoMatchMs, curve = CrossfadeCurve.SMOOTH_STEP,
                    tempoMatch = true, minConfidence = s.minBpmConfidence, maxStretch = s.maxStretchPercent / 100f,
                )
                if (matched.tempo.matched) plan = matched
            }
            if (plan == null && s.crossfadeEnabled) {
                plan = TransitionPlanner.plan(outgoing = out, incoming = incoming, crossfadeMs = s.crossfadeMs, curve = s.curve, tempoMatch = false)
            }
            armedTempos = outgoing?.bpm to incoming?.bpm
            if (armedForIndex == currentIndex) armedPlan = plan
        }
    }

    private fun beginTransition(plan: TransitionPlan, nextIdx: Int) {
        val incoming = other
        val outgoing = active
        incoming.stopAndClear()
        incoming.applyGain(0f)
        incoming.applySpeed(plan.tempo.incomingStartSpeed)
        incoming.load(playlist[nextIdx], plan.incomingStartMs)
        incoming.player.prepare()
        incoming.player.playWhenReady = playWhenReady
        transition = Transition(outgoing, incoming, plan, nextIdx, outgoingBpm = armedTempos.first, incomingBpm = armedTempos.second)
        armedPlan = null
        Log.i(TAG, "transition: ${plan.durationMs}ms curve=${plan.curve} tempo=${plan.tempo}")
    }

    private fun runTransition(t: Transition) {
        val outPos = t.outgoing.player.currentPosition.let { if (it == C.TIME_UNSET) t.plan.startAtOutgoingMs else it }
        val progress = if (t.plan.durationMs <= 0) 1f else ((outPos - t.plan.startAtOutgoingMs).toFloat() / t.plan.durationMs).coerceIn(0f, 1f)
        val g = Crossfade.gains(progress, t.plan.curve)
        t.outgoing.applyGain(g.outgoing)
        t.incoming.applyGain(g.incoming)
        val (outSpeed, inSpeed) = Crossfade.speeds(progress, t.plan.tempo)
        t.outgoing.applySpeed(outSpeed)
        t.incoming.applySpeed(inSpeed)
        _transitionInfo.value = TransitionInfo(progress, t.plan, t.outgoingBpm, t.incomingBpm)
        if (!t.midpointDone && progress >= 0.5f) {
            t.midpointDone = true
            active = t.incoming
            currentIndex = t.nextIndex
            t.outgoing.item?.let { onTrackFinished(it.mediaId, 1f) }
            invalidateState()
        }
        val outgoingEnded = t.outgoing.player.playbackState == Player.STATE_ENDED
        if (progress >= 1f || outgoingEnded) completeTransitionNow()
    }

    private fun completeTransitionNow() {
        val t = transition ?: return
        if (!t.midpointDone) {
            active = t.incoming
            currentIndex = t.nextIndex
            t.outgoing.item?.let { onTrackFinished(it.mediaId, 1f) }
        }
        t.outgoing.stopAndClear()
        t.incoming.applyGain(1f)
        t.incoming.applySpeed(userSpeed)
        transition = null
        _transitionInfo.value = null
        armedForIndex = -1; armedPlan = null
        invalidateState()
    }

    private fun cancelTransition() {
        val t = transition ?: return
        if (t.midpointDone) {
            // Already on the new track: just drop the old one.
            t.outgoing.stopAndClear()
        } else {
            t.incoming.stopAndClear()
            t.outgoing.applyGain(1f)
            t.outgoing.applySpeed(1f)
        }
        transition = null
        _transitionInfo.value = null
    }

    /** Pushes live-tunable settings (silence skipping) into both decks. Safe to call from any thread. */
    fun applySettings(s: PlaybackSettings) {
        for (d in decks) {
            d.silenceSkip.enabled = s.skipSilence
            d.silenceSkip.thresholdDb = s.silenceThresholdDb
            d.silenceSkip.toleranceMs = s.silenceToleranceMs
        }
    }

    /** Public helper for the UI: force the next track to start blending now. */
    fun blendNow() {
        if (transition != null) return
        val next = nextIndex() ?: return
        val s = settings()
        val dur = active.player.duration.takeIf { it > 0 } ?: return
        val pos = activePositionMs()
        val plan = armedPlan?.copy(startAtOutgoingMs = pos)
            ?: TransitionPlan(pos, 0L, (if (s.tempoMatch) s.tempoMatchMs else s.crossfadeMs).coerceAtLeast(1_000L).coerceAtMost(dur - pos), s.curve, TempoMatcher.NONE)
        beginTransition(plan, next)
        startTicking()
    }

    companion object {
        private const val TAG = "CrossfadePlayer"
        private const val TICK_MS = 50L
        private const val PROGRESS_EVERY_MS = 5_000L
        private const val ARM_BEFORE_END_MS = 40_000L
        private val DONE: ListenableFuture<*> = Futures.immediateVoidFuture()

        private val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
            Player.COMMAND_RELEASE,
        ).build()
    }
}
