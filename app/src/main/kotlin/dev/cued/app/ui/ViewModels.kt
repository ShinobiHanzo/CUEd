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
    val playlists: StateFlow<List<PlaylistEntity>> = lib.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val longPlays: StateFlow<List<TrackEntity>> = lib.longPlays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unlabelled: StateFlow<List<TrackEntity>> = graph.genres.unlabelled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unlabelledCount: StateFlow<Int> = graph.genres.unlabelledCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun unlockGenres(trackId: Long) = viewModelScope.launch { lib.unlockGenres(trackId) }
    fun setKind(trackId: Long, kind: String) = viewModelScope.launch { lib.setKind(trackId, kind) }
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
        viewModelScope.launch {
            while (isActive) {
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
    val settings: StateFlow<DownloadSettings> = graph.settings.download.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSettings(DownloadBackend.BUILT_IN, "", "mp3", false, true, null, null))
    fun setSpotifyKeys(id: String, secret: String) = viewModelScope.launch { graph.settings.setSpotifyKeys(id, secret) }
    val termux = TermuxDownloader(graph.app)

    fun enqueue(source: String) = graph.downloads.enqueue(source)
    fun retry(id: Long) = graph.downloads.retry(id)
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
}

// ---------------------------------------------------------------------------

data class ShareUiState(
    val serverUrl: String? = null,
    val payload: String? = null,
    val qr: Bitmap? = null,
    val includeFile: Boolean = true,
    val includeApk: Boolean = true,
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

    private fun rebuild() {
        val id = trackId ?: return
        viewModelScope.launch {
            val t = graph.library.track(id) ?: return@launch
            val genres = graph.library.genresOf(id)
            val s = _state.value
            val payload = SharePayload(
                title = t.title, artist = t.artist, link = t.sourceLink,
                fileUrl = if (s.includeFile) graph.shareServer.trackUrl(id) else null,
                apkUrl = if (s.includeApk) graph.shareServer.apkUrl() else null,
                genres = genres, bpm = t.bpm,
            ).encode()
            val qr = withContext(Dispatchers.Default) { QrCodes.encode(payload, 720, AColor.BLACK, AColor.WHITE) }
            dev.cued.app.share.nfc.SharePayloadHolder.set(payload)
            _state.value = _state.value.copy(payload = payload, qr = qr)
        }
    }

    fun stopSharing() {
        dev.cued.app.share.nfc.SharePayloadHolder.set(null)
        graph.shareServer.stop()
        _state.value = ShareUiState()
    }

    /** Receiving side: turn a scanned/tapped payload into a download or a saved link. */
    fun receive(payload: SharePayload): String {
        val source = payload.fileUrl ?: payload.link ?: return "Nothing downloadable in this share"
        if (payload.fileUrl != null) {
            viewModelScope.launch { LocalFileReceiver(graph).fetch(payload) }
            return "Fetching ${payload.title} from the other phone…"
        }
        graph.downloads.enqueue(source, payload.title, payload.artist)
        return "Queued ${payload.title} for download"
    }

    override fun onCleared() { stopSharing() }
}

/** Pulls a track file from another phone's share server straight into Music/CUEd. */
class LocalFileReceiver(private val graph: Graph) {
    suspend fun fetch(payload: SharePayload) = withContext(Dispatchers.IO) {
        val url = payload.fileUrl ?: return@withContext
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 120_000
        try {
            if (conn.responseCode !in 200..299) return@withContext
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
