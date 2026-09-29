package dev.cued.core.lyrics

/** One line of synced lyrics. [timeMs] is null for plain (unsynced) text. */
data class LyricLine(val timeMs: Long?, val text: String)

/**
 * Parses LRC ("[mm:ss.xx] text") and plain text into lines, and finds the
 * line that is current at a playback position.
 */
object Lrc {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val meta = Regex("^\\[([a-zA-Z]+):(.*)]$")

    fun isSynced(text: String): Boolean = stamp.containsMatchIn(text)

    /** Parses LRC; unsynced input comes back as lines with null times. Honors the `[offset:ms]` tag. */
    fun parse(text: String): List<LyricLine> {
        if (!isSynced(text)) {
            return text.lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }.dropWhile { it.isBlank() }.map { LyricLine(null, it) }
        }
        var offset = 0L
        val out = ArrayList<LyricLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            meta.find(line)?.let { m ->
                if (m.groupValues[1].equals("offset", true)) offset = m.groupValues[2].trim().toLongOrNull() ?: 0L
                return@let
            }
            val stamps = stamp.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val body = line.substring(stamps.last().range.last + 1).trim()
            for (s in stamps) {
                val min = s.groupValues[1].toLong()
                val sec = s.groupValues[2].toLong()
                val fracStr = s.groupValues[3]
                val frac = when (fracStr.length) { 0 -> 0L; 1 -> fracStr.toLong() * 100; 2 -> fracStr.toLong() * 10; else -> fracStr.take(3).toLong() }
                out += LyricLine((min * 60_000 + sec * 1_000 + frac - offset).coerceAtLeast(0L), body)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Index of the line playing at [positionMs], or -1 before the first line. */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var idx = -1
        for ((i, l) in lines.withIndex()) {
            val t = l.timeMs ?: return -1
            if (t <= positionMs) idx = i else break
        }
        return idx
    }

    fun toPlain(lines: List<LyricLine>): String = lines.joinToString("\n") { it.text }
}
