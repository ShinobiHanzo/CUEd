package dev.cued.core.download

/**
 * Turns a YouTube video title into something a tag can hold: "Artist - Song
 * (Official Video) [HD]" → title "Song", artist "Artist". Pure string rules.
 */
object TitleClean {
    data class Split(val title: String, val artist: String?)

    private val NOISE = Regex(
        """\s*[\(\[【]\s*(?:official\s*(?:music\s*)?(?:video|audio|visuali[sz]er|lyric(?:s)?\s*video)?|lyrics?(?:\s*video)?|audio|hd|hq|4k|1080p|720p|music\s*video|visuali[sz]er|explicit|clean|remaster(?:ed)?(?:\s*\d{4})?|\d{4}\s*remaster(?:ed)?|full\s*album|free\s*download|extended\s*mix|radio\s*edit)\s*[\)\]】]""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAIL = Regex("""\s*(?:\|.*|//.*|\s-\s*official.*|\s-\s*lyrics?.*|\s-\s*(?:hd|hq|4k)\b.*)$""", RegexOption.IGNORE_CASE)
    private val FEAT_IN_BRACKETS = Regex("""\s*[\(\[]\s*(feat\.?|ft\.?|featuring)\s+([^\)\]]+)[\)\]]""", RegexOption.IGNORE_CASE)
    private val DASH = Regex("""\s+[-–—]\s+""")
    private val QUOTES = Regex("""^["“”']+|["“”']+$""")

    /** Noise stripped, "feat." kept as plain text, whitespace tidied. */
    fun clean(raw: String): String {
        var s = raw.replace(NOISE, "").replace(TRAIL, "")
        s = s.replace(FEAT_IN_BRACKETS) { " feat. " + it.groupValues[2].trim() }
        return s.replace(Regex("""\s{2,}"""), " ").replace(QUOTES, "").trim()
    }

    /** "Artist - Title" → (Title, Artist); anything else → (cleaned, null). */
    fun split(raw: String): Split {
        val s = clean(raw)
        val parts = s.split(DASH, limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) Split(parts[1].trim(), parts[0].trim()) else Split(s, null)
    }

    /** True when [uploader] is a channel name rather than an artist ("Nickelback - Topic", "NickelbackVEVO"). */
    fun artistFromUploader(uploader: String?): String? {
        val u = uploader?.trim().orEmpty()
        if (u.isBlank()) return null
        return u.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "").replace(Regex("""VEVO$"""), "").replace(Regex("""\s*(official|music|records|tv)$""", RegexOption.IGNORE_CASE), "").trim().ifBlank { null }
    }
}
