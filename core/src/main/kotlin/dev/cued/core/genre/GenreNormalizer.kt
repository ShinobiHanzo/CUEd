package dev.cued.core.genre

/**
 * Turns whatever a tag says ("Hip-Hop/Rap; (17)", "DnB", "Drum & Bass") into
 * clean, deduplicated, lowercase labels ("hip hop", "rap", "rock", "drum and
 * bass"). Purely a lookup table plus splitting rules, so the same input always
 * gives the same output and the table is easy to extend.
 */
object GenreNormalizer {

    /** ID3v1 numeric genres, index = code. Tags often carry "(17)" or "17" instead of the word. */
    val ID3V1: List<String> = listOf(
        "blues", "classic rock", "country", "dance", "disco", "funk", "grunge", "hip hop", "jazz", "metal",
        "new age", "oldies", "other", "pop", "r&b", "rap", "reggae", "rock", "techno", "industrial",
        "alternative", "ska", "death metal", "pranks", "soundtrack", "euro-techno", "ambient", "trip hop", "vocal", "jazz funk",
        "fusion", "trance", "classical", "instrumental", "acid", "house", "game", "sound clip", "gospel", "noise",
        "alternative rock", "bass", "soul", "punk", "space", "meditative", "instrumental pop", "instrumental rock", "ethnic", "gothic",
        "darkwave", "techno-industrial", "electronic", "pop folk", "eurodance", "dream", "southern rock", "comedy", "cult", "gangsta",
        "top 40", "christian rap", "pop/funk", "jungle", "native american", "cabaret", "new wave", "psychedelic", "rave", "showtunes",
        "trailer", "lo-fi", "tribal", "acid punk", "acid jazz", "polka", "retro", "musical", "rock & roll", "hard rock",
        "folk", "folk rock", "national folk", "swing", "fast fusion", "bebop", "latin", "revival", "celtic", "bluegrass",
        "avantgarde", "gothic rock", "progressive rock", "psychedelic rock", "symphonic rock", "slow rock", "big band", "chorus", "easy listening", "acoustic",
        "humour", "speech", "chanson", "opera", "chamber music", "sonata", "symphony", "booty bass", "primus", "porn groove",
        "satire", "slow jam", "club", "tango", "samba", "folklore", "ballad", "power ballad", "rhythmic soul", "freestyle",
        "duet", "punk rock", "drum solo", "a cappella", "euro-house", "dance hall", "goa", "drum and bass", "club-house", "hardcore",
        "terror", "indie", "britpop", "afro-punk", "polsk punk", "beat", "christian gangsta rap", "heavy metal", "black metal", "crossover",
        "contemporary christian", "christian rock", "merengue", "salsa", "thrash metal", "anime", "jpop", "synthpop",
    )

    /** Aliases → canonical label. Keys are compared after lowercasing and collapsing whitespace. */
    private val ALIASES: Map<String, String> = mapOf(
        "hiphop" to "hip hop", "hip-hop" to "hip hop", "hip hop/rap" to "hip hop", "rap/hip hop" to "hip hop", "rap/hip-hop" to "hip hop",
        "rnb" to "r&b", "r n b" to "r&b", "r and b" to "r&b", "rhythm and blues" to "r&b", "r&b/soul" to "r&b",
        "dnb" to "drum and bass", "d&b" to "drum and bass", "drum & bass" to "drum and bass", "drum n bass" to "drum and bass", "drum'n'bass" to "drum and bass", "drumnbass" to "drum and bass",
        "electronica" to "electronic", "electro" to "electronic", "edm" to "electronic dance", "electronic dance music" to "electronic dance",
        "alt rock" to "alternative rock", "alt-rock" to "alternative rock", "alternative & punk" to "alternative", "alternative/indie" to "alternative",
        "lofi" to "lo-fi", "lo fi" to "lo-fi", "lo-fi hip hop" to "lo-fi", "lofi hip hop" to "lo-fi", "chillhop" to "lo-fi",
        "uk garage" to "garage", "ukg" to "garage", "2-step" to "garage", "2 step" to "garage",
        "tech-house" to "tech house", "techhouse" to "tech house", "deep-house" to "deep house", "deephouse" to "deep house", "prog house" to "progressive house",
        "psy trance" to "psytrance", "psy-trance" to "psytrance", "goa trance" to "psytrance",
        "rock & roll" to "rock and roll", "rock n roll" to "rock and roll", "rock'n'roll" to "rock and roll", "rock/pop" to "rock", "pop/rock" to "pop rock", "pop-rock" to "pop rock",
        "k-pop" to "kpop", "j-pop" to "jpop", "c-pop" to "cpop",
        "singer/songwriter" to "singer-songwriter", "singer songwriter" to "singer-songwriter",
        "soundtracks" to "soundtrack", "ost" to "soundtrack", "original soundtrack" to "soundtrack", "score" to "soundtrack", "film score" to "soundtrack",
        "classic" to "classical", "orchestral" to "classical",
        "world" to "world music", "world-music" to "world music",
        "children's music" to "children", "kids" to "children",
        "spoken word" to "speech", "audiobook" to "speech", "podcast" to "speech",
        "unknown" to "", "genre" to "", "other" to "", "misc" to "", "default" to "", "none" to "", "n/a" to "",
    )

    private val idv1Pattern = Regex("^\\(?(\\d{1,3})\\)?$")
    private val parenCode = Regex("\\((\\d{1,3})\\)")

    /** Splits, cleans and canonicalises a raw tag value. Empty list if nothing usable. */
    fun normalize(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        // "(17)Rock" and "(17)(3)" forms: pull every parenthesised code out, keep the rest as text.
        for (m in parenCode.findAll(raw)) ID3V1.getOrNull(m.groupValues[1].toInt())?.let { out += canonical(it) }
        val rest = raw.replace(parenCode, " ")
        for (part in rest.split(';', ',', '/', '|', '\u0000', '\n')) {
            val p = part.trim().trim('"', '\'', '[', ']')
            if (p.isEmpty()) continue
            val code = idv1Pattern.find(p)
            if (code != null) ID3V1.getOrNull(code.groupValues[1].toInt())?.let { out += canonical(it) }
            else canonical(p).takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out.filter { it.isNotEmpty() }.toList()
    }

    /** Canonical form of one label. */
    fun canonical(label: String): String {
        var s = label.lowercase().trim()
            .removePrefix("genre:").removePrefix("genre=").trim()
            .replace(Regex("[_]+"), " ")
            .replace(Regex("\\s+"), " ")
        ALIASES[s]?.let { return it }
        ALIASES[s.replace("-", " ")]?.let { return it }
        ALIASES[s.replace(" ", "")]?.let { return it }
        s = s.replace(Regex("\\s*&\\s*"), " & ")
        return s
    }
}
