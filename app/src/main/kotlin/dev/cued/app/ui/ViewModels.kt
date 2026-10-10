package dev.cued.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.app.data.DownloadBackend
import dev.cued.app.data.DownloadSettings
import dev.cued.app.data.PlaybackSettings
import dev.cued.app.data.ScrubberMode
import dev.cued.app.data.SmartList
import dev.cued.app.data.UiSettings
import dev.cued.app.data.db.DownloadJobEntity
import dev.cued.app.data.db.PlaylistEntity
import dev.cued.app.data.db.TrackEntity
import dev.cued.core.library.Discography
import kotlinx.coroutines.flow.flowOn
import dev.cued.app.download.CompanionDownloader
import dev.cued.app.download.TermuxDownloader
import dev.cued.app.playback.MediaItems
import dev.cued.app.playback.PlayerConnection
import dev.cued.app.playback.SpectrumBus
import dev.cued.app.playback.TransitionInfo
import dev.cued.app.playback.VoiceResolver
import dev.cued.core.voice.VoiceCommands
import dev.cued.app.data.CarSettings
import dev.cued.app.share.QrCodes
import dev.cued.app.data.db.LyricsEntity
import dev.cued.app.lyrics.LyricsRepository
import dev.cued.app.data.LyricsSettings
import dev.cued.app.data.GenreSettings
import dev.cued.app.genre.GenreRepository
import dev.cued.app.update.UpdateManager
import dev.cued.app.data.UpdateSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import dev.cued.core.mix.CrossfadeCurve
import dev.cued.core.mix.TrackTempo
import dev.cued.core.share.SharePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

val LocalGraph = staticCompositionLocalOf<Graph> { error("Graph not provided") }

@Suppress("UNCHECKED_CAST")
class CuedVmFactory(private val graph: Graph) : ViewModelProvider.Factory {
    @OptIn(UnstableApi::class)
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel(graph) as T
        modelClass.isAssignableFrom(PlayerViewModel::class.java) -> PlayerViewModel(graph) as T
        modelClass.isAssignableFrom(DownloadViewModel::class.java) -> DownloadViewModel(graph) as T
        modelClass.isAssignableFrom(ShareViewModel::class.java) -> ShareViewModel(graph) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(graph) as T
        modelClass.isAssignableFrom(StationViewModel::class.java) -> StationViewModel(graph) as T
        modelClass.isAssignableFrom(DesktopViewModel::class.java) -> DesktopViewModel(graph) as T
        else -> error("Unknown ViewModel $modelClass")
    }
}

// ---------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val graph: Graph) : ViewModel() {
    private val lib = graph.library
    val query = MutableStateFlow("")
    val tracks: StateFlow<List<TrackEntity>> = query.flatMapLatest { lib.search(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val genreMap: StateFlow<Map<Long, List<String>>> = lib.genreMap.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val genres: StateFlow<List<String>> = lib.genres.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Artists → albums → tracks, rebuilt whenever the library changes. */
    val discography: StateFlow<Discography.Index<TrackEntity>> = lib.tracks
        // Play counts and favourites change the rows constantly; only rebuild when something the shelf shows changes.
        .distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { i -> val x = a[i]; val y = b[i]; x.id == y.id && x.title == y.title && x.artist == y.artist && x.albumArtist == y.albumArtist && x.album == y.album && x.albumId == y.albumId && x.trackNo == y.trackNo && x.discNo == y.discNo && x.year == y.year && x.durationMs == y.durationMs && x.uri == y.uri } }
        .map { ts -> Discography.build(ts) { t -> Discography.Fields(t.title, t.artist, t.albumArtist, t.album, t.trackNo, t.discNo, t.year, t.durationMs) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Discography.Index.empty())
    val playlists: StateFlow<List<PlaylistEntity>> = lib.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val longPlays: StateFlow<List<TrackEntity>> = lib.longPlays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unlabelled: StateFlow<List<TrackEntity>> = graph.genres.unlabelled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unlabelledCount: StateFlow<Int> = graph.genres.unlabelledCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun unlockGenres(trackId: Long) = viewModelScope.launch { lib.unlockGenres(trackId) }
    fun setKind(trackId: Long, kind: String) = viewModelScope.launch { lib.setKind(trackId, kind) }
    suspend fun deleteFromDevice(ids: List<Long>) = lib.deleteFromDevice(ids)
    suspend fun confirmDeleted(ids: List<Long>) = lib.confirmDeleted(ids)
    val smartLists: StateFlow<Map<SmartList, List<TrackEntity>>> = lib.smartLists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val scanning: StateFlow<Boolean> = lib.scanning
    val analysisPending: StateFlow<Int> = graph.analysis.pending
    val analysisCurrent: StateFlow<Long?> = graph.analysis.current

    fun byGenre(genre: String): Flow<List<TrackEntity>> = lib.byGenre(genre)
    fun playlistTracks(id: Long): Flow<List<TrackEntity>> = lib.playlistTracks(id)
    fun playlist(id: Long): Flow<PlaylistEntity?> = lib.observePlaylist(id)
    fun track(id: Long): Flow<TrackEntity?> = lib.observeTrack(id)

    fun rescan() = lib.rescanAsync()
    fun analyseAll() = graph.analysis.sweep()
    fun analyse(trackId: Long) = graph.analysis.request(trackId, urgent = true)
    fun toggleFavourite(t: TrackEntity) = viewModelScope.launch { lib.setFavourite(t.id, !t.favourite) }
    fun setGenres(trackId: Long, genres: List<String>) = viewModelScope.launch { lib.setGenres(trackId, genres) }
    fun addGenre(trackId: Long, genre: String) = viewModelScope.launch { lib.addGenre(trackId, genre) }
    fun removeGenre(trackId: Long, genre: String) = viewModelScope.launch { lib.removeGenre(trackId, genre) }
    fun setSourceLink(trackId: Long, link: String?) = viewModelScope.launch { lib.setSourceLink(trackId, link?.takeIf { it.isNotBlank() }) }

    fun createPlaylist(name: String, then: (Long) -> Unit = {}) = viewModelScope.launch { then(lib.createPlaylist(name)) }
    fun renamePlaylist(id: Long, name: String, description: String) = viewModelScope.launch { lib.renamePlaylist(id, name, description) }
    fun deletePlaylist(id: Long) = viewModelScope.launch { lib.deletePlaylist(id) }
    fun addToPlaylist(playlistId: Long, trackId: Long) = viewModelScope.launch { lib.addToPlaylist(playlistId, trackId) }
    fun removeFromPlaylist(playlistId: Long, trackId: Long) = viewModelScope.launch { lib.removeFromPlaylist(playlistId, trackId) }
    fun reorderPlaylist(playlistId: Long, ids: List<Long>) = viewModelScope.launch { lib.reorderPlaylist(playlistId, ids) }
    fun saveAsPlaylist(name: String, ids: List<Long>, then: (Long) -> Unit = {}) = viewModelScope.launch { then(lib.saveAsPlaylist(name, ids)) }

    /** Plays a whole playlist; used by car mode's quick tiles. */
    @OptIn(UnstableApi::class)
    fun viewModelScopePlay(playlistId: Long, pvm: PlayerViewModel) = viewModelScope.launch { pvm.play(lib.playlistTracksNow(playlistId)) }

    suspend fun similarTo(trackId: Long) = lib.similarTo(trackId)
    suspend fun playlistsContaining(trackId: Long) = lib.playlistsContaining(trackId)
}

// ---------------------------------------------------------------------------

data class PlayerUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: android.net.Uri? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<MediaItem> = emptyList(),
    val queueIndex: Int = -1,
    val speed: Float = 1f,
)

@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
class PlayerViewModel(private val graph: Graph) : ViewModel() {
    val connection = PlayerConnection(graph.app)
    /** Set by the hosting screen while it is started; gates the 10 Hz position poll. */
    val uiVisible = MutableStateFlow(false)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    val transition: StateFlow<TransitionInfo?> = graph.player.player.transitionInfo
    val currentTempo: StateFlow<TrackTempo?> = graph.player.player.currentTempo
    val spectrumBus: SpectrumBus get() = graph.player.spectrumBus
    val uiSettings: StateFlow<UiSettings> = graph.settings.ui.stateIn(viewModelScope, SharingStarted.Eagerly, UiSettings(ScrubberMode.REACTIVE_SPECTROGRAM, 120, 48))
    val currentTrack: StateFlow<TrackEntity?> = _state.map { it.trackId }.flatMapLatest { id -> if (id == null) MutableStateFlow(null) else graph.library.observeTrack(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh(player) }
    }

    init {
        connection.connect()
        viewModelScope.launch {
            connection.controller.collect { c -> c?.addListener(listener); c?.let { refresh(it) } }
        }
        // Position ticks only while some UI is on screen; the service keeps playing regardless.
        viewModelScope.launch {
            while (isActive) {
                if (!uiVisible.value) uiVisible.first { it }
                connection.player?.let { p -> if (p.isPlaying) _state.value = _state.value.copy(positionMs = p.currentPosition.coerceAtLeast(0L)) }
                delay(100)
            }
        }
    }

    private fun refresh(p: Player) {
        val item = p.currentMediaItem
        _state.value = PlayerUiState(
            connected = true,
            isPlaying = p.isPlaying,
            playbackState = p.playbackState,
            trackId = item?.let { MediaItems.trackId(it) },
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            artworkUri = item?.mediaMetadata?.artworkUri,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = p.duration.takeIf { it > 0 } ?: item?.let { MediaItems.durationMs(it) } ?: 0L,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) },
            queueIndex = p.currentMediaItemIndex,
            speed = p.playbackParameters.speed,
        )
    }

    fun play(tracks: List<TrackEntity>, index: Int = 0) = connection.play(tracks, index)
    fun playNext(t: TrackEntity) = connection.playNext(t)
    fun enqueue(ts: List<TrackEntity>) = connection.enqueue(ts)
    fun togglePlay() { connection.player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun next() = connection.player?.seekToNext()
    fun previous() = connection.player?.seekToPrevious()
    fun seekTo(ms: Long) = connection.player?.seekTo(ms)
    fun seekBy(deltaMs: Long) { connection.player?.let { it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) } }
    fun setSpeed(speed: Float) { connection.player?.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 3f), 1f) }
    fun seekToQueueItem(index: Int) = connection.player?.seekTo(index, 0L)
    fun removeQueueItem(index: Int) = connection.player?.removeMediaItem(index)
    /** Drops every queue entry for a track (used after deleting it from the device). */
    fun removeFromQueue(trackId: Long) { connection.player?.let { p -> for (i in p.mediaItemCount - 1 downTo 0) if (p.getMediaItemAt(i).mediaId == trackId.toString()) p.removeMediaItem(i) } }
    fun toggleShuffle() { connection.player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        connection.player?.let {
            it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        }
    }
    fun blendNow() = graph.player.player.blendNow()
    fun setScrubberMode(mode: ScrubberMode) = viewModelScope.launch { graph.settings.setScrubberMode(mode) }
    fun togglePlayIfPaused() { connection.player?.let { if (!it.isPlaying) { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun setShuffle(on: Boolean) { connection.player?.shuffleModeEnabled = on }
    fun setRepeat(mode: Int) { connection.player?.repeatMode = mode }

    // ---- Voice ----
    private val resolver = VoiceResolver(graph.library)
    /** Resolves a voice target against the library and plays it. */
    suspend fun playTarget(target: VoiceCommands.Target): VoiceResolver.Resolution {
        val r = resolver.resolve(target)
        if (r.tracks.isNotEmpty()) play(r.tracks)
        return r
    }
    suspend fun playQuery(query: String): VoiceResolver.Resolution {
        val r = resolver.resolveQuery(query)
        if (r.tracks.isNotEmpty()) play(r.tracks)
        return r
    }

    // ---- Car mode ----
    private val carOverride = MutableStateFlow<Boolean?>(null)
    val carSettings: StateFlow<CarSettings> = graph.settings.car.stateIn(viewModelScope, SharingStarted.Eagerly, CarSettings(false, true))
    /** Effective car mode: a session override (exit button, voice) beats the stored toggle, which beats auto-detection. */
    val carMode: StateFlow<Boolean> = combine(carOverride, carSettings, graph.carDetector.inCar) { override, s, detected ->
        override ?: (s.carMode || (s.autoCarMode && detected))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setCarMode(on: Boolean) { carOverride.value = null; viewModelScope.launch { graph.settings.setCarMode(on) } }
    fun setAutoCarMode(on: Boolean) = viewModelScope.launch { graph.settings.setAutoCarMode(on) }
    fun exitCarModeForNow() { carOverride.value = false; viewModelScope.launch { graph.settings.setCarMode(false) } }

    // ---- Lyrics ----
    val showLyrics = MutableStateFlow(false)
    val lyricsSettings: StateFlow<LyricsSettings> = graph.settings.lyrics.stateIn(viewModelScope, SharingStarted.Eagerly, LyricsSettings(true, true))
    val lyrics: StateFlow<LyricsEntity?> = _state.map { it.trackId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else graph.lyrics.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _lyricsBusy = MutableStateFlow(false)
    val lyricsBusy: StateFlow<Boolean> = _lyricsBusy
    init {
        // Auto-lookup when the track changes, if allowed.
        viewModelScope.launch {
            _state.map { it.trackId }.distinctUntilChanged().collect { id ->
                if (id == null) return@collect
                val s = graph.settings.lyricsNow()
                // Podcasts and audiobooks: only look locally (embedded / sidecar), never online.
                val long = graph.library.track(id)?.isLong == true
                if (s.autoFetch) launch(Dispatchers.IO) { runCatching { graph.lyrics.ensure(id, allowOnline = s.fetchOnline && !long) } }
            }
        }
    }
    fun fetchLyricsNow() {
        val id = _state.value.trackId ?: return
        viewModelScope.launch {
            _lyricsBusy.value = true
            runCatching { graph.lyrics.refresh(id) }
            _lyricsBusy.value = false
        }
    }

    override fun onCleared() { connection.player?.removeListener(listener); connection.disconnect() }
}

// ---------------------------------------------------------------------------

class DownloadViewModel(private val graph: Graph) : ViewModel() {
    val jobs: StateFlow<List<DownloadJobEntity>> = graph.downloads.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Queued + running jobs, for the badge in the drawer and top bar. */
    val activeCount: StateFlow<Int> = graph.downloads.jobs.map { js -> js.count { it.status == dev.cued.app.download.DownloadManager.STATUS_QUEUED || it.status == dev.cued.app.download.DownloadManager.STATUS_RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val settings: StateFlow<DownloadSettings> = graph.settings.download.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSettings(DownloadBackend.BUILT_IN, "", "mp3", false, true, null, null))
    fun setSpotifyKeys(id: String, secret: String) = viewModelScope.launch { graph.settings.setSpotifyKeys(id, secret) }
    fun setEnrichOnline(on: Boolean) = viewModelScope.launch { graph.settings.setEnrichOnline(on) }
    val termux = TermuxDownloader(graph.app)

    fun enqueue(source: String) = graph.downloads.enqueue(source)
    fun retry(id: Long) = graph.downloads.retry(id)
    fun remove(id: Long) = graph.downloads.remove(id)
    fun clearFinished() = graph.downloads.clearFinished()
    fun setBackend(b: DownloadBackend) = viewModelScope.launch { graph.settings.setDownloadBackend(b) }
    fun setCompanionUrl(url: String) = viewModelScope.launch { graph.settings.setCompanionUrl(url) }
    fun setFormat(f: String) = viewModelScope.launch { graph.settings.setDownloadFormat(f) }
    fun setGenerateLrc(on: Boolean) = viewModelScope.launch { graph.settings.setGenerateLrc(on) }
    fun setFetchLyricsAfter(on: Boolean) = viewModelScope.launch { graph.settings.setFetchLyricsAfter(on) }
    fun rescanFolder() = viewModelScope.launch { graph.downloads.scanDownloadFolder(); graph.library.rescan() }
    suspend fun pingCompanion(): Result<String> = CompanionDownloader(graph.app, settings.value.companionUrl, settings.value.format).ping()
    fun enqueueShared(text: String) = graph.downloads.enqueue(text)
    val termuxTest: StateFlow<String?> = graph.downloads.termuxTest
    val nativeTest: StateFlow<String?> = graph.downloads.nativeTest
    fun testNative() = graph.downloads.testNative()
    fun testTermux() = graph.downloads.testTermux()
    fun repairTermux() = graph.downloads.repairTermux()
}

// ---------------------------------------------------------------------------

data class ShareUiState(
    val serverUrl: String? = null,
    val payload: String? = null,
    val qr: Bitmap? = null,
    val includeFile: Boolean = true,
    val includeApk: Boolean = false, // off by default: the share carries only the track unless asked
    val includeFriend: Boolean = false, // adds this account's public key as a friend request (protocol §9)
    val error: String? = null,
)

class ShareViewModel(private val graph: Graph) : ViewModel() {
    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state
    private var trackId: Long? = null

    fun startSharing(trackId: Long) {
        this.trackId = trackId
        viewModelScope.launch {
            val port = graph.settings.sharePort.first()
            val url = withContext(Dispatchers.IO) { graph.shareServer.start(port) }
            graph.shareServer.offer(trackId)
            _state.value = _state.value.copy(serverUrl = url, error = if (url == null) "No Wi-Fi/hotspot address: only the link can be shared" else null)
            rebuild()
        }
    }

    fun setIncludeFile(v: Boolean) { _state.value = _state.value.copy(includeFile = v); rebuild() }
    fun setIncludeApk(v: Boolean) { _state.value = _state.value.copy(includeApk = v); rebuild() }
    fun setIncludeFriend(v: Boolean) { _state.value = _state.value.copy(includeFriend = v); rebuild() }

    private fun rebuild() {
        val id = trackId ?: return
        viewModelScope.launch {
            val t = graph.library.track(id) ?: return@launch
            val genres = graph.library.genresOf(id)
            val s = _state.value
            // The friend request rides along as the public key only; the private key never leaves the phone.
            val friendKey = if (s.includeFriend) runCatching { graph.station.store.ensureIdentity().pubkeyHex }.getOrNull() else null
            val friendName = friendKey?.let { graph.station.store.name.first().ifBlank { graph.desktop.store.phoneName.first() }.takeIf { n -> n.isNotBlank() } }
            val sp = SharePayload(
                title = t.title, artist = t.artist, link = t.sourceLink,
                fileUrl = if (s.includeFile) graph.shareServer.trackUrl(id) else null,
                apkUrl = if (s.includeApk) graph.shareServer.apkUrl() else null,
                genres = genres, bpm = t.bpm,
                friendPubkey = friendKey, friendName = friendName,
            )
            // QR carries the web form: a stock camera lands on the download page, CUEd opens it directly.
            // NFC leads with the cued:// form so the receiving CUEd launches straight away (see SharePayloadHolder).
            val payload = sp.encodeWeb()
            val qr = withContext(Dispatchers.Default) { QrCodes.encode(payload, 720, AColor.BLACK, AColor.WHITE) }
            dev.cued.app.share.nfc.SharePayloadHolder.set(cuedUrl = sp.encode(), webUrl = payload)
            _state.value = _state.value.copy(payload = payload, qr = qr)
        }
    }

    fun stopSharing() {
        dev.cued.app.share.nfc.SharePayloadHolder.set(null, null)
        graph.shareServer.stop()
        _state.value = ShareUiState()
    }

    /** Receiving side: turn a scanned/tapped payload into a download or a saved link. */
    // ---- receiving ----
    private val _received = MutableStateFlow<Received?>(null)
    /** The share most recently handed to this phone, shown full-screen until dismissed. */
    val received: StateFlow<Received?> = _received
    private val seen = HashMap<String, Received>()

    /**
     * Handles an incoming share. The same share arriving again (a second tap, a
     * re-scanned QR) only re-shows the screen with a "tapped again" note; nothing
     * is fetched twice. [force] fetches even when the library already has it.
     */
    fun receive(payload: SharePayload, force: Boolean = false): String {
        // A share can carry a friend request (protocol §9); remember it for the Friends screen either way.
        payload.friendPubkey?.let { pk -> viewModelScope.launch { runCatching { graph.desktop.noteIncomingRequest(pk, payload.friendName.orEmpty()) } } }
        val key = payload.fileUrl ?: payload.link ?: "${payload.artist}|${payload.title}"
        val prev = seen[key]
        if (!force && prev != null && prev.status !is ReceiveStatus.Failed) {
            val again = prev.copy(repeats = prev.repeats + 1)
            seen[key] = again; _received.value = again
            return "Already received ${payload.title}"
        }
        fun update(st: ReceiveStatus) { val r = Received(payload, st, prev?.repeats ?: 0); seen[key] = r; _received.value = r }
        update(if (payload.fileUrl != null) ReceiveStatus.Fetching else if (payload.link != null) ReceiveStatus.Queued else ReceiveStatus.Failed("Nothing downloadable in this share"))
        viewModelScope.launch {
            when {
                payload.fileUrl != null -> {
                    val dup = if (force) null else graph.db.tracks().byTitleArtist(payload.title, payload.artist)
                    if (dup != null) { update(ReceiveStatus.AlreadyHave(dup.id)); return@launch }
                    update(ReceiveStatus.Fetching)
                    runCatching { LocalFileReceiver(graph).fetch(payload) }
                        .onSuccess { id -> update(if (id != null) ReceiveStatus.Done(id) else ReceiveStatus.Failed("The file arrived but the library could not see it")) }
                        .onFailure { update(ReceiveStatus.Failed(it.message ?: "Transfer failed")) }
                }
                payload.link != null -> { graph.downloads.enqueue(payload.link!!, payload.title, payload.artist); update(ReceiveStatus.Queued) }
                else -> update(ReceiveStatus.Failed("Nothing downloadable in this share"))
            }
        }
        return if (payload.fileUrl != null) "Fetching ${payload.title} from the other phone…" else "Queued ${payload.title} for download"
    }

    fun dismissReceived() { _received.value = null }

    override fun onCleared() { stopSharing() }
}

sealed class ReceiveStatus {
    data object Fetching : ReceiveStatus()
    /** Only a source link came: it went to the download queue. */
    data object Queued : ReceiveStatus()
    data class Done(val trackId: Long) : ReceiveStatus()
    data class AlreadyHave(val trackId: Long) : ReceiveStatus()
    data class Failed(val reason: String) : ReceiveStatus()
}

data class Received(val payload: SharePayload, val status: ReceiveStatus, val repeats: Int = 0)

/** Pulls a track file from another phone's share server straight into Music/CUEd. */
class LocalFileReceiver(private val graph: Graph) {
    /** Returns the new library track id, or null if the file landed but the scan did not pick it up. Throws on transfer errors. */
    suspend fun fetch(payload: SharePayload): Long? = withContext(Dispatchers.IO) {
        val url = payload.fileUrl ?: error("No file in this share")
        val conn = try { java.net.URL(url).openConnection() as java.net.HttpURLConnection } catch (e: Exception) { error("Bad file link") }
        conn.connectTimeout = 8_000; conn.readTimeout = 120_000
        try {
            val code = try { conn.responseCode } catch (e: java.io.IOException) { error("Could not reach the other phone. Both phones need to be on the same Wi-Fi or hotspot, with its Share screen still open.") }
            if (code !in 200..299) error("The other phone answered $code. Is its Share screen still open?")
            val mime = conn.contentType?.substringBefore(';') ?: "audio/mpeg"
            val ext = when (mime) { "audio/mp4" -> "m4a"; "audio/flac" -> "flac"; "audio/ogg" -> "ogg"; else -> "mp3" }
            val name = "${payload.artist} - ${payload.title}.$ext".replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val (uri, id) = dev.cued.app.download.DownloadManager.createPendingAudio(graph.app, name, mime)
            graph.app.contentResolver.openOutputStream(uri)!!.use { out -> conn.inputStream.use { it.copyTo(out) } }
            dev.cued.app.download.DownloadManager.finishPending(graph.app, uri)
            graph.library.rescan()
            graph.db.tracks().byMediaStoreId(id)?.let { t ->
                payload.link?.let { graph.library.setSourceLink(t.id, it) }
                if (payload.genres.isNotEmpty()) graph.library.setGenresAuto(t.id, payload.genres)
                if (!t.isLong) graph.analysis.request(t.id)
                t.id
            }
        } finally { conn.disconnect() }
    }
}

// ---------------------------------------------------------------------------

class SettingsViewModel(private val graph: Graph) : ViewModel() {
    val playback: StateFlow<PlaybackSettings> = graph.settings.playback.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings.DEFAULT)
    val ui: StateFlow<UiSettings> = graph.settings.ui.stateIn(viewModelScope, SharingStarted.Eagerly, UiSettings(ScrubberMode.REACTIVE_SPECTROGRAM, 120, 48))
    val sharePort: StateFlow<Int> = graph.settings.sharePort.stateIn(viewModelScope, SharingStarted.Eagerly, 8765)
    val analysisPending: StateFlow<Int> = graph.analysis.pending

    fun setCrossfadeEnabled(on: Boolean) = viewModelScope.launch { graph.settings.setCrossfadeEnabled(on) }
    fun setCrossfadeMs(ms: Long) = viewModelScope.launch { graph.settings.setCrossfadeMs(ms) }
    fun setTempoMatchMs(ms: Long) = viewModelScope.launch { graph.settings.setTempoMatchMs(ms) }
    fun setSkipSilence(on: Boolean) = viewModelScope.launch { graph.settings.setSkipSilence(on) }
    fun setSilenceThresholdDb(db: Float) = viewModelScope.launch { graph.settings.setSilenceThresholdDb(db) }
    fun setSilenceToleranceMs(ms: Int) = viewModelScope.launch { graph.settings.setSilenceToleranceMs(ms) }
    val lyrics: StateFlow<LyricsSettings> = graph.settings.lyrics.stateIn(viewModelScope, SharingStarted.Eagerly, LyricsSettings(true, true))
    val lyricsBulk: StateFlow<LyricsRepository.BulkProgress?> = graph.lyrics.bulk
    val lyricsCount: StateFlow<Int> = graph.lyrics.count.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    fun setLyricsOnline(on: Boolean) = viewModelScope.launch { graph.settings.setLyricsOnline(on) }
    fun setLyricsAuto(on: Boolean) = viewModelScope.launch { graph.settings.setLyricsAuto(on) }
    fun fetchAllLyrics() = graph.lyrics.fetchMissingAsync()
    fun cancelBulkLyrics() = graph.lyrics.cancelBulk()
    val genreSettings: StateFlow<GenreSettings> = graph.settings.genres.stateIn(viewModelScope, SharingStarted.Eagerly, GenreSettings(false))
    val genreProgress: StateFlow<GenreRepository.Progress?> = graph.genres.progress
    val unlabelledCount: StateFlow<Int> = graph.genres.unlabelledCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    fun setGenresOnline(on: Boolean) = viewModelScope.launch { graph.settings.setGenresOnline(on) }
    fun readTagsMissing() = graph.genres.refreshFromTagsAsync(onlyMissing = true)
    fun readTagsAll() = graph.genres.refreshFromTagsAsync(onlyMissing = false)
    fun tidyGenres() = graph.genres.tidyLabelsAsync()
    fun lookupGenresOnline() = graph.genres.lookupOnlineAsync()
    fun cancelGenres() = graph.genres.cancel()

    // ---- Lock screen + widgets ----
    val lockScreen: StateFlow<Boolean> = graph.settings.lockScreen.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setLockScreen(on: Boolean) = viewModelScope.launch { graph.settings.setLockScreen(on) }

    // ---- Updates ----
    val update: StateFlow<UpdateManager.State> = graph.updates.state
    val updateSettings: StateFlow<UpdateSettings> = graph.settings.updates.stateIn(viewModelScope, SharingStarted.Eagerly, UpdateSettings(true, 0L, null))
    val currentVersion: String get() = graph.updates.currentVersion
    fun checkForUpdate() = graph.updates.checkAsync()
    fun downloadUpdate() = graph.updates.downloadAsync()
    fun dismissUpdate() = graph.updates.dismiss()
    fun canInstall() = graph.updates.canInstall()
    fun unknownSourcesIntent() = graph.updates.unknownSourcesIntent()
    fun installIntent(file: java.io.File) = graph.updates.installIntent(file)
    fun setUpdatesAuto(on: Boolean) = viewModelScope.launch { graph.settings.setUpdatesAuto(on) }
    fun setGithubToken(t: String?) = viewModelScope.launch { graph.settings.setGithubToken(t) }

    // ---- Debug log ----
    val debugLog: StateFlow<Boolean> = graph.settings.debugLog.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setDebugLog(on: Boolean) = viewModelScope.launch { graph.settings.setDebugLog(on) }
    val devMode: StateFlow<Boolean> = graph.settings.devMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setDevMode(on: Boolean) = viewModelScope.launch { graph.settings.setDevMode(on) }
    val theme: StateFlow<dev.cued.app.ui.theme.ThemeColors> = graph.settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, dev.cued.app.ui.theme.ThemeColors.DEFAULT)
    fun setTheme(t: dev.cued.app.ui.theme.ThemeColors) = viewModelScope.launch { graph.settings.setTheme(t) }
    fun resetTheme() = viewModelScope.launch { graph.settings.resetTheme() }
    fun logRecent() = dev.cued.app.util.DebugLog.recent(60)
    fun logSize() = dev.cued.app.util.DebugLog.sizeBytes()
    fun clearLog() = dev.cued.app.util.DebugLog.clear()
    fun logShareIntent(context: android.content.Context) = dev.cued.app.util.DebugLog.shareIntent(context)
    fun setCurve(c: CrossfadeCurve) = viewModelScope.launch { graph.settings.setCurve(c) }
    fun setTempoMatch(on: Boolean) = viewModelScope.launch { graph.settings.setTempoMatch(on) }
    fun setMaxStretch(p: Float) = viewModelScope.launch { graph.settings.setMaxStretchPercent(p) }
    fun setMinConfidence(v: Float) = viewModelScope.launch { graph.settings.setMinBpmConfidence(v) }
    fun setScrubberMode(m: ScrubberMode) = viewModelScope.launch { graph.settings.setScrubberMode(m) }
    fun setVisualDelay(ms: Int) = viewModelScope.launch { graph.settings.setVisualDelayMs(ms) }
    fun setSharePort(p: Int) = viewModelScope.launch { graph.settings.setSharePort(p) }
    fun analyseAll() = graph.analysis.sweep()
    fun rescan() = graph.library.rescanAsync()
}
