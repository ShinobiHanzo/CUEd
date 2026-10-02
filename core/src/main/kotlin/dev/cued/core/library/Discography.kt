package dev.cued.core.library

/**
 * Turns a flat list of tracks into artists → albums → tracks, the way a
 * record shelf is organised, so the library can be browsed progressively:
 * pick an artist, then an album, then a track.
 *
 * Grouping rules (all plain string logic, nothing learned):
 *  - An album belongs to its album-artist tag when present, otherwise to the
 *    primary artist of the track (the part before "feat." / "ft." / "with").
 *  - Albums are keyed by (owner, album name), not by MediaStore's album id,
 *    so the same album split across two folders still shows as one.
 *  - 1–3 tracks under one album name count as a single / EP.
 *  - Tracks with no album tag are "loose".
 *  - An artist's "Appears on" lists tracks they are credited on (feat., "&",
 *    ",", "x", "/") that live under someone else's album.
 */
object Discography {
    /** The fields the grouping needs, pulled out of whatever the caller's track type is. */
    data class Fields(
        val title: String,
        val artist: String,
        val albumArtist: String?,
        val album: String,
        val trackNo: Int,
        val discNo: Int,
        val year: Int,
        val durationMs: Long,
    )

    data class Album<T>(val artist: String, val name: String, val year: Int, val tracks: List<T>, val durationMs: Long) {
        val key: String get() = key(artist, name)
        val isSingle: Boolean get() = tracks.size <= SINGLE_MAX_TRACKS
    }

    data class Artist<T>(
        val name: String,
        /** Full-length releases, newest first. */
        val albums: List<Album<T>>,
        /** 1–3 track releases, newest first. */
        val singles: List<Album<T>>,
        /** Tracks with no album tag. */
        val loose: List<T>,
        /** Tracks under other artists' albums that credit this artist. */
        val appearsOn: List<T>,
    ) {
        val ownTracks: List<T> get() = albums.flatMap { it.tracks } + singles.flatMap { it.tracks } + loose
        val trackCount: Int get() = ownTracks.size
        val releaseCount: Int get() = albums.size + singles.size
        /** The artist page's cover: the newest album's first track. */
        val coverTrack: T? get() = albums.firstOrNull()?.tracks?.firstOrNull() ?: singles.firstOrNull()?.tracks?.firstOrNull() ?: loose.firstOrNull() ?: appearsOn.firstOrNull()
    }

    data class Index<T>(val artists: List<Artist<T>>, val albums: List<Album<T>>) {
        private val byArtist by lazy { artists.associateBy { it.name.lowercase() } }
        private val byAlbum by lazy { albums.associateBy { it.key } }
        fun artist(name: String): Artist<T>? = byArtist[name.lowercase()]
        fun album(artist: String, name: String): Album<T>? = byAlbum[key(artist, name)]
        companion object { fun <T> empty() = Index<T>(emptyList(), emptyList()) }
    }

    const val SINGLE_MAX_TRACKS = 3
    private val FEAT = Regex("""\s+(?:feat\.?|ft\.?|featuring|with)\s+""", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("""\s*(?:,|&|/|;|\+|\bx\b|\bvs\.?\b|\band\b)\s*""", RegexOption.IGNORE_CASE)
    private val PARENS = Regex("""\s*[\(\[].*?[\)\]]""")

    fun key(artist: String, album: String) = artist.lowercase().trim() + "\u0000" + album.lowercase().trim()

    /** "Daft Punk feat. Pharrell" → "Daft Punk". */
    fun primaryArtist(artist: String): String = artist.split(FEAT, limit = 2).first().trim().ifBlank { artist.trim() }

    /** Every name credited in an artist string: "A feat. B & C" → [A, B, C]. */
    fun credited(artist: String): List<String> {
        val parts = artist.split(FEAT).flatMap { it.split(SPLIT) }
        return parts.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    }

    /** Alphabetic sort key: case-insensitive, leading "the" ignored. */
    fun sortKey(name: String): String {
        val n = name.trim().lowercase()
        return if (n.startsWith("the ")) n.removePrefix("the ") else n
    }

    /** First letter for an index rail: A–Z, or "#" for digits and symbols. */
    fun indexLetter(name: String): Char {
        val c = sortKey(name).firstOrNull() ?: return '#'
        return if (c in 'a'..'z') c.uppercaseChar() else '#'
    }

    fun <T> build(tracks: List<T>, fields: (T) -> Fields): Index<T> {
        if (tracks.isEmpty()) return Index.empty()
        val f = tracks.associateWith(fields)

        // Owner of each track and album grouping.
        data class Owned(val track: T, val owner: String, val album: String)
        val owned = tracks.map { t ->
            val fl = f.getValue(t)
            val owner = fl.albumArtist?.trim()?.takeIf { it.isNotBlank() } ?: primaryArtist(fl.artist)
            Owned(t, owner, fl.album.trim())
        }
        val albums = owned.filter { it.album.isNotBlank() }
            .groupBy { key(it.owner, it.album) }
            .values.map { group ->
                val sorted = group.map { it.track }.sortedWith(
                    compareBy<T> { f.getValue(it).discNo }.thenBy { f.getValue(it).trackNo }.thenBy { f.getValue(it).title.lowercase() }
                )
                val years = sorted.map { f.getValue(it).year }.filter { it > 0 }
                val year = years.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
                val first = group.first()
                Album(canonicalName(group.map { it.owner }), canonicalName(group.map { it.album }), year, sorted, sorted.sumOf { f.getValue(it).durationMs })
                    .also { check(it.key == key(first.owner, first.album)) }
            }
        val albumsByOwner = albums.groupBy { it.artist.lowercase() }
        val looseByOwner = owned.filter { it.album.isBlank() }.groupBy({ it.owner.lowercase() }, { it.track })

        // Who is credited where, for "Appears on".
        val creditsByName = HashMap<String, MutableList<T>>()
        val ownerOf = owned.associate { it.track to it.owner.lowercase() }
        for (t in tracks) {
            val fl = f.getValue(t)
            val names = credited(fl.artist) + (fl.albumArtist?.let { credited(it) } ?: emptyList())
            for (n in names.distinctBy { it.lowercase() }) {
                if (n.lowercase() == ownerOf[t]) continue
                creditsByName.getOrPut(n.lowercase()) { ArrayList() }.add(t)
            }
        }

        val displayNames = HashMap<String, String>()
        for (o in owned) displayNames.putIfAbsent(o.owner.lowercase(), o.owner)
        for (t in tracks) for (n in credited(f.getValue(t).artist)) displayNames.putIfAbsent(n.lowercase(), n)

        val allNames = (albumsByOwner.keys + looseByOwner.keys + creditsByName.keys).toSet()
        val artists = allNames.map { lower ->
            val mine = albumsByOwner[lower].orEmpty().sortedWith(compareByDescending<Album<T>> { it.year }.thenBy { it.name.lowercase() })
            Artist(
                name = displayNames[lower] ?: lower,
                albums = mine.filter { !it.isSingle },
                singles = mine.filter { it.isSingle },
                loose = looseByOwner[lower].orEmpty().sortedBy { f.getValue(it).title.lowercase() },
                appearsOn = creditsByName[lower].orEmpty().sortedBy { f.getValue(it).title.lowercase() },
            )
        }.filter { it.trackCount > 0 || it.appearsOn.isNotEmpty() }
            .sortedBy { sortKey(it.name) }

        return Index(artists, albums.sortedWith(compareBy<Album<T>> { sortKey(it.artist) }.thenByDescending { it.year }.thenBy { it.name.lowercase() }))
    }

    /** Most common spelling wins; ties go to the first. */
    private fun canonicalName(variants: List<String>): String =
        variants.groupingBy { it }.eachCount().entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })?.key ?: variants.first()

    /** Pretty "(2013)" style helper for UI. */
    fun yearLabel(year: Int): String? = year.takeIf { it > 0 }?.toString()

    /** Strips "(Deluxe Edition)"-style suffixes for matching album names loosely. */
    fun bareAlbumName(name: String): String = name.replace(PARENS, "").trim()
}
