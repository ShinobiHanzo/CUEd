package dev.cued.app.playback

import dev.cued.app.data.LibraryRepository
import dev.cued.app.data.SmartList
import dev.cued.app.data.db.TrackEntity
import dev.cued.core.voice.VoiceCommands
import dev.cued.core.voice.VoiceCommands.Target
import kotlinx.coroutines.flow.first

/**
 * Maps a parsed voice target (or a raw Assistant query) onto library tracks.
 * Fuzzy, forgiving, and entirely local: substring and token matching over
 * titles, artists, genres and playlist names.
 */
class VoiceResolver(private val library: LibraryRepository) {

    data class Resolution(val tracks: List<TrackEntity>, val label: String)

    suspend fun resolveQuery(query: String): Resolution {
        val cmd = VoiceCommands.parse(if (query.isBlank()) "play something" else "play $query")
        val target = (cmd as? VoiceCommands.Command.Play)?.target ?: Target.Search(query)
        return resolve(target)
    }

    suspend fun resolve(target: Target): Resolution = when (target) {
        is Target.Anything -> anything()
        is Target.SmartList -> {
            val kind = runCatching { SmartList.valueOf(target.name) }.getOrNull()
            val list = kind?.let { library.smartLists.first()[it] }.orEmpty()
            if (list.isEmpty()) anything() else Resolution(list, kind!!.title)
        }
        is Target.Playlist -> {
            val playlists = library.playlists.first()
            val best = playlists.firstOrNull { it.name.equals(target.name, true) }
                ?: playlists.firstOrNull { it.name.contains(target.name, true) }
                ?: playlists.maxByOrNull { tokenScore(target.name, it.name) }?.takeIf { tokenScore(target.name, it.name) > 0 }
            if (best == null) search(target.name) else Resolution(library.playlistTracksNow(best.id), best.name)
        }
        is Target.Genre -> {
            val genres = library.genres.first()
            val g = genres.firstOrNull { it.equals(target.name, true) } ?: genres.firstOrNull { it.contains(target.name, true) || target.name.contains(it, true) }
            if (g == null) search(target.name) else Resolution(library.byGenre(g).first().shuffled(), g)
        }
        is Target.Artist -> {
            val all = library.tracks.first()
            val hits = all.filter { it.artist.contains(target.name, true) }.ifEmpty { all.filter { tokenScore(target.name, it.artist) > 0 } }
            if (hits.isEmpty()) search(target.name) else Resolution(hits.shuffled(), hits.first().artist)
        }
        is Target.Search -> search(target.query)
    }

    private suspend fun search(q: String): Resolution {
        val direct = library.search(q).first()
        if (direct.isNotEmpty()) return Resolution(direct, direct.first().title)
        val all = library.tracks.first()
        val scored = all.map { it to tokenScore(q, "${it.title} ${it.artist} ${it.album}") }.filter { it.second > 0 }.sortedByDescending { it.second }
        return if (scored.isEmpty()) Resolution(emptyList(), "No match for \"$q\"") else Resolution(scored.map { it.first }.take(50), scored.first().first.title)
    }

    private suspend fun anything(): Resolution {
        val lists = library.smartLists.first()
        lists[SmartList.RECOMMENDED]?.takeIf { it.isNotEmpty() }?.let { return Resolution(it, "Recommended") }
        lists[SmartList.TRENDING]?.takeIf { it.isNotEmpty() }?.let { return Resolution(it, "Trending") }
        val all = library.tracks.first()
        return Resolution(all.shuffled(), if (all.isEmpty()) "Library is empty" else "Shuffling everything")
    }

    private fun tokenScore(query: String, text: String): Int {
        val t = text.lowercase()
        return query.lowercase().split(' ').filter { it.length > 1 }.count { it in t }
    }
}
