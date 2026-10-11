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
import dev.cued.app.download.native.SpotifyClient
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
 * New releases and artist feeds. The metadata comes from Spotify's Web API
 * when your own client keys are set (dates to the day, covers every time), or
 * from MusicBrainz (open data, no key, one request a second) otherwise; the
 * rows live in Room so the home page and the artist pages work offline. Audio
 * is fetched only when you play or download a track: the stream starts at
 * once from YouTube Music while the normal download queue brings the file in.
 */
class FeedRepository(private val graph: Graph) {
    private val db get() = graph.db
    private val dao get() = graph.db.feed()
    private val mb by lazy { MusicBrainzClient("CUEd/${BuildConfig.VERSION_NAME} (https://github.com/ShinobiHanzo/CUEd)") }
    private var spotifyCache: Pair<String, SpotifyClient>? = null
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

    /**
     * Opening an artist's page: the artist joins the watch list (unfollowed)
     * and gets fetched if stale. False when that fetch could not run (offline).
     */
    suspend fun openArtist(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (dao.artist(n) == null) dao.upsertArtist(FeedArtistEntity(n, null, false, System.currentTimeMillis()))
        return runCatching { refreshArtist(n) }.onFailure { DebugLog.w(TAG, "open artist $n", it) }.getOrDefault(false)
    }

    fun refreshArtistAsync(name: String): Job = graph.appScope.launch { runCatching { refreshArtist(name, force = true) }.onFailure { DebugLog.w(TAG, "refresh artist $name", it) } }

    /**
     * Finds the artist at the source (once) and re-reads its releases. False
     * when it could not run: no connection, or only mobile data for a
     * [background] pass with mobile data off in Settings.
     */
    suspend fun refreshArtist(name: String, force: Boolean = false, background: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val a = dao.artist(name) ?: return@withContext false
        val now = System.currentTimeMillis()
        if (!force && now - a.lastRefreshAt < ARTIST_TTL_MS) return@withContext true
        if (if (background) !online() else !connected()) return@withContext false
        runCatching {
            if (source() == ReleaseEntity.SOURCE_SPOTIFY) refreshFromSpotify(a, now) else refreshFromMusicBrainz(a, now)
        }.onFailure { e -> DebugLog.w(TAG, "feed refresh ${a.name}", e); dao.markRefreshed(a.name, a.mbid, now, e.message ?: "failed") }
        true
    }

    private suspend fun refreshFromMusicBrainz(a: FeedArtistEntity, now: Long) {
        val mbid = a.mbid ?: mb.findArtist(a.name)?.id
        if (mbid == null) { dao.markRefreshed(a.name, null, now, "Not found on MusicBrainz"); return }
        val groups = mb.releaseGroups(mbid)
        dao.pruneOtherSource(a.name, ReleaseEntity.SOURCE_MUSICBRAINZ)
        for (g in groups) {
            val dateMs = ReleaseFeed.parseDate(g.date) ?: 0L
            val cover = mb.coverUrl(g.id)
            if (dao.insertRelease(ReleaseEntity(g.id, a.name, mbid, g.title, g.type, g.date, dateMs, cover, now, source = ReleaseEntity.SOURCE_MUSICBRAINZ)) == -1L) {
                dao.updateRelease(g.id, g.title, g.type, g.date, dateMs, cover, now)
            }
        }
        if (groups.isNotEmpty()) dao.pruneReleases(a.name, groups.map { it.id })
        dao.markRefreshed(a.name, mbid, now, null)
        DebugLog.d(TAG, "${a.name}: ${groups.size} releases (MusicBrainz)")
    }

    private suspend fun refreshFromSpotify(a: FeedArtistEntity, now: Long) {
        val sp = spotify() ?: return refreshFromMusicBrainz(a, now)
        val sid = a.spotifyId ?: sp.searchArtists(a.name).let { hits -> ReleaseFeed.pickByName(a.name, hits.map { ReleaseFeed.NamedCandidate(it.id, it.name, it.popularity) })?.id }
        if (sid == null) { dao.markRefreshed(a.name, a.mbid, now, "Not found on Spotify"); return }
        if (a.spotifyId != sid) dao.setSpotifyId(a.name, sid)
        val albums = sp.artistAlbums(sid, market())
        dao.pruneOtherSource(a.name, ReleaseEntity.SOURCE_SPOTIFY)
        val kept = ArrayList<String>()
        for (al in albums) {
            val type = ReleaseFeed.spotifyTypeLabel(al.type, al.totalTracks) ?: continue
            val dateMs = ReleaseFeed.parseDate(al.releaseDate) ?: 0L
            kept += al.id
            if (dao.insertRelease(ReleaseEntity(al.id, a.name, a.mbid.orEmpty(), al.name, type, al.releaseDate, dateMs, al.coverUrl, now, source = ReleaseEntity.SOURCE_SPOTIFY, spotifyId = al.id)) == -1L) {
                dao.updateRelease(al.id, al.name, type, al.releaseDate, dateMs, al.coverUrl, now)
            }
        }
        if (kept.isNotEmpty()) dao.pruneReleases(a.name, kept)
        dao.markRefreshed(a.name, a.mbid, now, null)
        DebugLog.d(TAG, "${a.name}: ${kept.size} releases (Spotify)")
    }

    /**
     * Which metadata source a refresh uses right now. Spotify needs Developer
     * mode and its keys; otherwise, whatever the setting says, MusicBrainz.
     */
    suspend fun source(): String {
        val s = graph.settings.feedNow()
        val keys = graph.settings.devMode.first() && spotify() != null
        return when (s.source) {
            FeedSettings.SOURCE_SPOTIFY -> if (keys) ReleaseEntity.SOURCE_SPOTIFY else ReleaseEntity.SOURCE_MUSICBRAINZ
            FeedSettings.SOURCE_MUSICBRAINZ -> ReleaseEntity.SOURCE_MUSICBRAINZ
            else -> if (keys) ReleaseEntity.SOURCE_SPOTIFY else ReleaseEntity.SOURCE_MUSICBRAINZ
        }
    }

    /** A Spotify client for the keys in the download settings, or null without keys. One per key pair, so the token is reused. */
    private suspend fun spotify(): SpotifyClient? {
        val ds = graph.settings.downloadNow()
        val id = ds.spotifyClientId?.takeIf { it.isNotBlank() } ?: return null
        val secret = ds.spotifyClientSecret?.takeIf { it.isNotBlank() } ?: return null
        val key = "$id:$secret"
        spotifyCache?.takeIf { it.first == key }?.let { return it.second }
        return SpotifyClient(id, secret).also { spotifyCache = key to it }
    }

    private fun market(): String = java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase() ?: "US"

    /** The track list for a release, fetched once (or again with [force]). False when offline. */
    suspend fun ensureTracks(releaseId: String, force: Boolean = false, background: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val r = dao.release(releaseId) ?: return@withContext false
        if (!force && r.tracksFetchedAt > 0) return@withContext true
        if (if (background) !online() else !connected()) return@withContext false
        runCatching {
            val rows = if (r.source == ReleaseEntity.SOURCE_SPOTIFY && r.spotifyId != null) {
                val sp = spotify() ?: error("Spotify keys were removed")
                sp.albumTracks(r.spotifyId).mapIndexed { i, t -> ReleaseTrackEntity(r.id, i + 1, t.name, t.artists.joinToString(", ").ifBlank { r.artist }, t.durationMs, null, spotifyId = t.id) }
            } else {
                mb.releaseTracks(r.id, r.artist).map { ReleaseTrackEntity(r.id, it.position, it.title, it.artist, it.durationMs, it.recordingId) }
            }
            if (rows.isNotEmpty()) dao.replaceTracks(r.id, rows) else dao.setTracksFetched(r.id, System.currentTimeMillis())
        }.onFailure { DebugLog.w(TAG, "tracks for ${r.title}", it) }
        true
    }

    fun refreshAsync(force: Boolean = false): Job = graph.appScope.launch { runCatching { refresh(force) }.onFailure { DebugLog.w(TAG, "feed refresh", it) } }

    /** Home page opened: a full pass when the last one is older than six hours. */
    fun refreshIfStaleAsync(): Job = graph.appScope.launch {
        val s = graph.settings.feedNow()
        if (s.enabled && System.currentTimeMillis() - s.lastRefreshAt > STALE_MS) runCatching { refresh(false) }.onFailure { DebugLog.w(TAG, "feed refresh", it) }
    }

    /**
     * One pass over the artists you follow: stale ones are re-read from the
     * source, and their newest releases get track lists so the home row can
     * play straight away. Artists you merely opened refresh when opened.
     */
    suspend fun refresh(force: Boolean) {
        if (!refreshMutex.tryLock()) return
        _refreshing.value = true
        try {
            val s = graph.settings.feedNow()
            if (!s.enabled) { _note.value = "Feeds are off in Settings"; return }
            if (!online(s)) { _note.value = "Waiting for Wi-Fi"; return }
            val now = System.currentTimeMillis()
            val stale = dao.allArtists().filter { it.followed && (force || now - it.lastRefreshAt > ARTIST_TTL_MS) }.sortedBy { it.lastRefreshAt }.take(MAX_ARTISTS_PER_RUN)
            var checked = 0
            for (a in stale) if (refreshArtist(a.name, force = true, background = true)) checked++
            for (r in dao.recentWithoutTracks(now - ReleaseFeed.RECENT_DAYS * ReleaseFeed.DAY_MS, MAX_TRACKLISTS_PER_RUN)) ensureTracks(r.id, background = true)
            graph.settings.setFeedRefreshedAt(now)
            _note.value = if (checked == 0) "Up to date" else "Checked $checked artist${if (checked == 1) "" else "s"} on ${if (source() == ReleaseEntity.SOURCE_SPOTIFY) "Spotify" else "MusicBrainz"}"
        } finally {
            _refreshing.value = false
            refreshMutex.unlock()
        }
    }

    /** Artists you play but do not follow yet, best first: one-tap follow suggestions. */
    suspend fun suggestions(limit: Int = 6): List<String> {
        val followed = dao.allArtists().filter { it.followed }.map { it.name.lowercase() }.toSet()
        return listenedArtists().filter { it.lowercase() !in followed }.take(limit)
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
            val source = downloadSource(t) ?: best.id
            graph.downloads.enqueue(source, t.title, t.artist)
            retireWhenDownloaded(id, source, t)
            db.tracks().byId(id) ?: error("row vanished")
        }
    }

    /**
     * Download without playing: a Spotify track link when known (full metadata),
     * else a YouTube Music search for artist and title. Returns the queued
     * source, for [awaitDownloaded].
     */
    fun download(t: ReleaseTrackEntity): String {
        val source = downloadSource(t) ?: "${t.artist} - ${t.title}"
        graph.downloads.enqueue(source, t.title, t.artist)
        return source
    }

    private fun downloadSource(t: ReleaseTrackEntity): String? = t.spotifyId?.let { "https://open.spotify.com/track/$it" }

    /**
     * Waits for the download queued from [source] to finish and returns the
     * library track it produced; null when it failed, or after two hours.
     */
    suspend fun awaitDownloaded(source: String, t: ReleaseTrackEntity): TrackEntity? {
        val src = SourceLinks.canonical(source)
        val job = withTimeoutOrNull(2 * 60 * 60 * 1000L) {
            graph.downloads.jobs.mapNotNull { jobs -> jobs.firstOrNull { it.source == src && (it.status == "DONE" || it.status == "FAILED") } }.first()
        } ?: return null
        if (job.status != "DONE") return null
        return db.tracks().addedSince(job.createdAt - 60_000L).firstOrNull { it.kind == TrackEntity.KIND_MUSIC && ReleaseFeed.sameTrack(t.title, t.artist, it.title, it.artist) }
            ?: libraryCopy(t)
    }

    /** Once the background download has produced a library file, the temporary stream row goes. */
    private fun retireWhenDownloaded(tempId: Long, source: String, t: ReleaseTrackEntity) = graph.appScope.launch {
        val real = awaitDownloaded(source, t) ?: return@launch
        if (real.id != tempId) db.tracks().deleteById(tempId)
    }

    private fun connected(): Boolean {
        val cm = graph.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Refreshes are background traffic: Wi-Fi (or Ethernet) unless mobile data is allowed in Settings. */
    private suspend fun online(settings: FeedSettings? = null): Boolean {
        val s = settings ?: graph.settings.feedNow()
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
