package dev.cued.app.feed

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.cued.app.BuildConfig
import dev.cued.app.Graph
import dev.cued.app.data.FeedSettings
import dev.cued.app.data.db.FeedArtistEntity
import dev.cued.app.data.db.ReleaseEntity
import dev.cued.app.data.db.ReleaseTrackEntity
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.download.native.YouTubeSource
import dev.cued.app.genre.MusicBrainzClient
import dev.cued.app.util.DebugLog
import dev.cued.core.download.Matcher
import dev.cued.core.feed.ReleaseFeed
import dev.cued.core.library.Discography
import dev.cued.core.share.SourceLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * New releases and artist feeds. MusicBrainz (open data, no key, one request
 * a second) says what the artists you play or follow have put out; the rows
 * live in Room so the home page and the artist pages work offline. Audio is
 * fetched only when you play or download a track: the stream starts at once
 * from YouTube Music while the normal download queue brings the file in.
 */
class FeedRepository(private val graph: Graph) {
    private val db get() = graph.db
    private val dao get() = graph.db.feed()
    private val mb by lazy { MusicBrainzClient("CUEd/${BuildConfig.VERSION_NAME} (https://github.com/ShinobiHanzo/CUEd)") }
    private val refreshMutex = Mutex()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _note = MutableStateFlow<String?>(null)
    /** One line about the last refresh: what was checked, or why nothing was. */
    val note: StateFlow<String?> = _note

    val followed: Flow<List<FeedArtistEntity>> = dao.observeFollowed()
    /** Releases from the last [ReleaseFeed.RECENT_DAYS] days (and announced ones), newest first. */
    fun newReleases(now: Long = System.currentTimeMillis()): Flow<List<ReleaseEntity>> =
        dao.observeRecent(now - ReleaseFeed.RECENT_DAYS * ReleaseFeed.DAY_MS, now + ReleaseFeed.UPCOMING_DAYS * ReleaseFeed.DAY_MS, 40)
    fun releasesFor(artist: String): Flow<List<ReleaseEntity>> = dao.observeReleases(artist)
    fun latestFor(artist: String): Flow<ReleaseEntity?> = dao.observeLatest(artist)
    fun artist(name: String): Flow<FeedArtistEntity?> = dao.observeArtist(name)
    fun release(id: String): Flow<ReleaseEntity?> = dao.observeRelease(id)
    fun tracks(releaseId: String): Flow<List<ReleaseTrackEntity>> = dao.observeTracks(releaseId)

    suspend fun follow(name: String, on: Boolean) {
        val n = name.trim()
        if (n.isEmpty()) return
        if (dao.artist(n) == null) dao.upsertArtist(FeedArtistEntity(n, null, on, System.currentTimeMillis()))
        else dao.setFollowed(n, on)
        if (on) refreshArtistAsync(n)
    }

    /** Opening an artist's page: the artist joins the watch list (unfollowed) and gets fetched if stale. */
    fun openArtistAsync(name: String): Job = graph.appScope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        if (dao.artist(n) == null) dao.upsertArtist(FeedArtistEntity(n, null, false, System.currentTimeMillis()))
        runCatching { refreshArtist(n) }.onFailure { DebugLog.w(TAG, "open artist $n", it) }
    }

    fun refreshArtistAsync(name: String): Job = graph.appScope.launch { runCatching { refreshArtist(name, force = true) }.onFailure { DebugLog.w(TAG, "refresh artist $name", it) } }

    /** Finds the artist on MusicBrainz (once) and re-reads its release groups. False when offline or unknown. */
    suspend fun refreshArtist(name: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val a = dao.artist(name) ?: return@withContext false
        val now = System.currentTimeMillis()
        if (!force && now - a.lastRefreshAt < ARTIST_TTL_MS) return@withContext true
        if (!online()) return@withContext false
        runCatching {
            val mbid = a.mbid ?: mb.findArtist(a.name)?.id
            if (mbid == null) { dao.markRefreshed(a.name, null, now, "Not found on MusicBrainz"); return@runCatching }
            val groups = mb.releaseGroups(mbid)
            for (g in groups) {
                val dateMs = ReleaseFeed.parseDate(g.date) ?: 0L
                val cover = mb.coverUrl(g.id)
                if (dao.insertRelease(ReleaseEntity(g.id, a.name, mbid, g.title, g.type, g.date, dateMs, cover, now)) == -1L) {
                    dao.updateRelease(g.id, g.title, g.type, g.date, dateMs, cover, now)
                }
            }
            if (groups.isNotEmpty()) dao.pruneReleases(a.name, groups.map { it.id })
            dao.markRefreshed(a.name, mbid, now, null)
            DebugLog.d(TAG, "${a.name}: ${groups.size} releases")
        }.onFailure { e -> DebugLog.w(TAG, "feed refresh ${a.name}", e); dao.markRefreshed(a.name, a.mbid, now, e.message ?: "failed") }
        true
    }

    /** The track list for a release, fetched once (or again with [force]). */
    suspend fun ensureTracks(releaseId: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        val r = dao.release(releaseId) ?: return@withContext
        if (!force && r.tracksFetchedAt > 0) return@withContext
        if (!online()) return@withContext
        runCatching {
            val tracks = mb.releaseTracks(r.id, r.artist)
            if (tracks.isNotEmpty()) dao.replaceTracks(r.id, tracks.map { ReleaseTrackEntity(r.id, it.position, it.title, it.artist, it.durationMs, it.recordingId) })
            else dao.setTracksFetched(r.id, System.currentTimeMillis())
        }.onFailure { DebugLog.w(TAG, "tracks for ${r.title}", it) }
    }

    fun refreshAsync(force: Boolean = false): Job = graph.appScope.launch { runCatching { refresh(force) }.onFailure { DebugLog.w(TAG, "feed refresh", it) } }

    /** Home page opened: a full pass when the last one is older than six hours. */
    fun refreshIfStaleAsync(): Job = graph.appScope.launch {
        val s = graph.settings.feedNow()
        if (s.enabled && System.currentTimeMillis() - s.lastRefreshAt > STALE_MS) runCatching { refresh(false) }.onFailure { DebugLog.w(TAG, "feed refresh", it) }
    }

    /**
     * One pass: the artists you listen to join the watch list, stale artists
     * are re-read from MusicBrainz, and the newest releases get their track
     * lists so the home row can play straight away.
     */
    suspend fun refresh(force: Boolean) {
        if (!refreshMutex.tryLock()) return
        _refreshing.value = true
        try {
            val s = graph.settings.feedNow()
            if (!s.enabled) { _note.value = "Feeds are off in Settings"; return }
            if (!online(s)) { _note.value = "Waiting for Wi-Fi"; return }
            val now = System.currentTimeMillis()
            for (name in listenedArtists()) if (dao.artist(name) == null) dao.upsertArtist(FeedArtistEntity(name, null, false, now))
            val stale = dao.allArtists().filter { force || now - it.lastRefreshAt > ARTIST_TTL_MS }.sortedBy { it.lastRefreshAt }.take(MAX_ARTISTS_PER_RUN)
            var checked = 0
            for (a in stale) if (refreshArtist(a.name, force = true)) checked++
            for (r in dao.recentWithoutTracks(now - ReleaseFeed.RECENT_DAYS * ReleaseFeed.DAY_MS, MAX_TRACKLISTS_PER_RUN)) ensureTracks(r.id)
            graph.settings.setFeedRefreshedAt(now)
            _note.value = if (checked == 0) "Up to date" else "Checked $checked artist${if (checked == 1) "" else "s"} on MusicBrainz"
        } finally {
            _refreshing.value = false
            refreshMutex.unlock()
        }
    }

    /** Artists you play, ranked: recent plays weigh most, then lifetime plays, then how much of them you own. */
    private suspend fun listenedArtists(): List<String> {
        val tracks = db.tracks().all()
        val recent = db.playEvents().since(System.currentTimeMillis() - 90 * ReleaseFeed.DAY_MS).filter { !it.skipped }.groupingBy { it.trackId }.eachCount()
        val byArtist = HashMap<String, ReleaseFeed.ArtistPlays>()
        for (t in tracks) {
            val name = Discography.primaryArtist(t.albumArtist?.takeIf { it.isNotBlank() } ?: t.artist)
            val prev = byArtist[name.lowercase()]
            byArtist[name.lowercase()] = ReleaseFeed.ArtistPlays(prev?.name ?: name, (prev?.playCount ?: 0) + t.playCount, (prev?.recentPlays ?: 0) + (recent[t.id] ?: 0), (prev?.tracks ?: 0) + 1)
        }
        return ReleaseFeed.rankListened(byArtist.values.toList())
    }

    /** The library's copy of a release track, from a list of library tracks (station/stream rows excluded). */
    fun matchInLibrary(t: ReleaseTrackEntity, library: List<TrackEntity>): TrackEntity? =
        library.firstOrNull { it.kind == TrackEntity.KIND_MUSIC && !it.missing && ReleaseFeed.sameTrack(t.title, t.artist, it.title, it.artist) }

    /** Same, against the database. */
    suspend fun libraryCopy(t: ReleaseTrackEntity): TrackEntity? {
        db.tracks().musicByTitleArtist(t.title, t.artist)?.let { return it }
        return db.tracks().musicByArtist(Discography.primaryArtist(t.artist)).firstOrNull { ReleaseFeed.sameTrack(t.title, t.artist, it.title, it.artist) }
    }

    /**
     * Plays a track the library does not have yet. The metadata row is written
     * now, the audio streams from YouTube Music at once, and the real file
     * downloads in the background through the normal download queue. The row
     * is a temporary `station` row: hidden from the library, swept after 48
     * hours, and removed as soon as the downloaded file is in.
     */
    suspend fun stream(release: ReleaseEntity, t: ReleaseTrackEntity): Result<TrackEntity> = withContext(Dispatchers.IO) {
        runCatching {
            libraryCopy(t)?.let { return@runCatching it }
            val now = System.currentTimeMillis()
            // A stream row from the last few hours still works (YouTube URLs expire after about six).
            db.tracks().byTitleArtist(t.title, t.artist)?.takeIf { it.kind == TrackEntity.KIND_STATION && it.path == null && now - it.addedAt < STREAM_ROW_TTL_MS }?.let { return@runCatching it }
            if (!connected()) error("No connection")
            val yt = YouTubeSource()
            val q = "${t.artist} - ${t.title}"
            val cands = yt.search(q)
            val wanted = Matcher.Wanted(t.title, listOf(t.artist), (t.durationMs / 1000).toInt().takeIf { it > 0 })
            val best = Matcher.best(wanted, cands)?.candidate ?: cands.firstOrNull() ?: error("Nothing found on YouTube Music for \"$q\"")
            val (info, stream) = yt.info(best.id, preferM4a = true)
            val row = TrackEntity(
                mediaStoreId = -now, uri = stream.content, path = null, title = t.title, artist = t.artist, album = release.title, albumId = null,
                durationMs = t.durationMs.takeIf { it > 0 } ?: info.duration * 1000L, addedAt = now, sourceLink = best.id, kind = TrackEntity.KIND_STATION,
                trackNo = t.position, year = release.date.take(4).toIntOrNull() ?: 0,
            )
            val id = db.tracks().insert(row)
            if (id <= 0) error("Could not keep the track")
            graph.downloads.enqueue(best.id, t.title, t.artist)
            retireWhenDownloaded(id, best.id, t)
            db.tracks().byId(id) ?: error("row vanished")
        }
    }

    /** Download without playing: the built-in downloader searches YouTube Music for the artist and title. */
    fun download(t: ReleaseTrackEntity) = graph.downloads.enqueue("${t.artist} - ${t.title}", t.title, t.artist)

    /** Once the background download has produced a library file, the temporary stream row goes. */
    private fun retireWhenDownloaded(tempId: Long, ytUrl: String, t: ReleaseTrackEntity) = graph.appScope.launch {
        val src = SourceLinks.canonical(ytUrl)
        val job = withTimeoutOrNull(2 * 60 * 60 * 1000L) {
            graph.downloads.jobs.mapNotNull { jobs -> jobs.firstOrNull { it.source == src && (it.status == "DONE" || it.status == "FAILED") } }.first()
        } ?: return@launch
        if (job.status != "DONE") return@launch
        val real = db.tracks().addedSince(job.createdAt - 60_000L).firstOrNull { it.kind == TrackEntity.KIND_MUSIC && ReleaseFeed.sameTrack(t.title, t.artist, it.title, it.artist) }
            ?: libraryCopy(t) ?: return@launch
        if (real.id != tempId) db.tracks().deleteById(tempId)
    }

    private fun connected(): Boolean {
        val cm = graph.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Refreshes are background traffic: Wi-Fi (or Ethernet) unless mobile data is allowed in Settings. */
    private suspend fun online(s: FeedSettings = graph.settings.feedNow()): Boolean {
        val cm = graph.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
        return s.onMobile
    }

    companion object {
        private const val TAG = "feed"
        private const val ARTIST_TTL_MS = 20L * 60 * 60 * 1000
        private const val STALE_MS = 6L * 60 * 60 * 1000
        private const val STREAM_ROW_TTL_MS = 5L * 60 * 60 * 1000
        private const val MAX_ARTISTS_PER_RUN = 25
        private const val MAX_TRACKLISTS_PER_RUN = 8
    }
}
