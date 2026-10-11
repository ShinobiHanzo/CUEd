package dev.cued.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cued.app.Graph
import dev.cued.app.data.FeedSettings
import dev.cued.app.data.db.FeedArtistEntity
import dev.cued.app.data.db.ReleaseEntity
import dev.cued.app.data.db.ReleaseTrackEntity
import dev.cued.app.data.db.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** New releases, followed artists and release pages, for the home page and the feed screens. */
class FeedViewModel(private val graph: Graph) : ViewModel() {
    private val feed get() = graph.feed

    val settings: StateFlow<FeedSettings> = graph.settings.feed.stateIn(viewModelScope, SharingStarted.Eagerly, FeedSettings(true, false, 0L))
    val followed: StateFlow<List<FeedArtistEntity>> = feed.followed.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val newReleases: StateFlow<List<ReleaseEntity>> = feed.newReleases().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val refreshing: StateFlow<Boolean> = feed.refreshing
    val note: StateFlow<String?> = feed.note
    /** Library music, for the "already have it" marks. */
    val library: StateFlow<List<TrackEntity>> = graph.library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** What the screen is waiting for (finding a stream), or null. */
    val busy = MutableStateFlow<String?>(null)
    /** One-shot snackbar lines; collected once at the root so nothing is shown twice or late. */
    val message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Artist names and release ids whose fetch is running right now. */
    val fetchingArtists = MutableStateFlow<Set<String>>(emptySet())
    val fetchingReleases = MutableStateFlow<Set<String>>(emptySet())
    /** Artists you play but do not follow yet. */
    val suggestions = MutableStateFlow<List<String>>(emptyList())

    init { feed.refreshIfStaleAsync(); loadSuggestions() }

    fun loadSuggestions() = viewModelScope.launch { suggestions.value = runCatching { feed.suggestions() }.getOrDefault(emptyList()) }

    fun refresh() { feed.refreshAsync(force = true) }
    fun refreshArtist(name: String) = viewModelScope.launch {
        fetchingArtists.value += name
        try { if (!feed.refreshArtist(name, force = true)) message.tryEmit("Offline: could not refresh $name") } finally { fetchingArtists.value -= name }
    }
    fun openArtist(name: String) = viewModelScope.launch {
        fetchingArtists.value += name
        try { if (!feed.openArtist(name)) message.tryEmit("Offline: showing what is already saved") } finally { fetchingArtists.value -= name }
    }
    fun artist(name: String): Flow<FeedArtistEntity?> = feed.artist(name)
    fun releasesFor(name: String): Flow<List<ReleaseEntity>> = feed.releasesFor(name)
    fun latestFor(name: String): Flow<ReleaseEntity?> = feed.latestFor(name)
    fun release(id: String): Flow<ReleaseEntity?> = feed.release(id)
    fun tracks(id: String): Flow<List<ReleaseTrackEntity>> = feed.tracks(id)
    fun ensureTracks(id: String, force: Boolean = false) = viewModelScope.launch {
        fetchingReleases.value += id
        try { if (!feed.ensureTracks(id, force) && force) message.tryEmit("Offline: no connection for the track list") } finally { fetchingReleases.value -= id }
    }
    fun setFollowed(name: String, on: Boolean) = viewModelScope.launch { feed.follow(name, on); message.tryEmit(if (on) "Following $name" else "Unfollowed $name"); loadSuggestions() }
    fun libraryCopy(t: ReleaseTrackEntity, library: List<TrackEntity>): TrackEntity? = feed.matchInLibrary(t, library)

    /**
     * Plays a release from [start]. Library copies play as they are. Exactly one
     * song that is not on the phone, the first one reached, streams at once
     * (and downloads behind it); every later missing song only downloads and
     * joins the queue, in release order, as its file lands.
     */
    fun playFrom(release: ReleaseEntity, tracks: List<ReleaseTrackEntity>, start: Int, library: List<TrackEntity>, onPlay: (List<TrackEntity>) -> Unit, onEnqueue: (TrackEntity) -> Unit) = viewModelScope.launch {
        val order = tracks.drop(start.coerceIn(0, tracks.size))
        val first = order.firstOrNull() ?: return@launch
        var streamed = false
        val firstEntity = libraryCopy(first, library) ?: run {
            streamed = true
            busy.value = "Finding ${first.title}…"
            val r = feed.stream(release, first)
            busy.value = null
            r.getOrElse { message.tryEmit("Could not play: ${it.message}"); return@launch }
        }
        onPlay(listOf(firstEntity))
        val waits = ArrayList<Deferred<TrackEntity?>>()
        var downloads = 0
        for (t in order.drop(1)) {
            val local = libraryCopy(t, library)
            waits += when {
                local != null -> CompletableDeferred(local)
                !streamed -> { streamed = true; async { feed.stream(release, t).getOrNull() } }
                else -> { downloads++; async { feed.awaitDownloaded(feed.download(t), t) } }
            }
        }
        if (downloads > 0) message.tryEmit("Downloading $downloads more track${if (downloads == 1) "" else "s"}; each joins the queue when it lands")
        // Awaiting in order keeps the release order even when downloads finish out of turn.
        for (d in waits) d.await()?.let(onEnqueue)
    }

    fun download(t: ReleaseTrackEntity) { feed.download(t); message.tryEmit("Queued ${t.title}") }
    fun downloadMissing(tracks: List<ReleaseTrackEntity>, library: List<TrackEntity>) {
        val missing = tracks.filter { libraryCopy(it, library) == null }
        missing.forEach { feed.download(it) }
        message.tryEmit(if (missing.isEmpty()) "Everything is in the library already" else "Queued ${missing.size} track${if (missing.size == 1) "" else "s"}")
    }
}
