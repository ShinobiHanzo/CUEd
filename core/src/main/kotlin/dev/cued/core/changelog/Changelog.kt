package dev.cued.core.changelog

/**
 * The patch notes shipped inside the app: `CHANGELOG.md` split into `## x.y.z`
 * sections, and the slice a person has not seen yet (everything newer than the
 * version they last ran, up to the one running now).
 */
object Changelog {
    data class Section(val version: String, val body: String)

    /** Newest first, as written in the file. */
    fun sections(markdown: String): List<Section> {
        val out = ArrayList<Section>()
        var version: String? = null
        val body = StringBuilder()
        fun flush() { version?.let { out += Section(it, body.toString().trim()) }; body.setLength(0) }
        for (line in markdown.lines()) {
            if (line.startsWith("## ")) { flush(); version = line.removePrefix("## ").trim() }
            else if (version != null) body.appendLine(line)
        }
        flush()
        return out
    }

    /** Sections with `from < version <= to`; `from == null` means everything up to [to]. */
    fun between(markdown: String, from: String?, to: String): List<Section> =
        sections(markdown).filter { compare(it.version, to) <= 0 && (from == null || compare(it.version, from) > 0) }

    fun render(sections: List<Section>): String = sections.joinToString("\n\n") { "## ${it.version}\n\n${it.body}" }

    /** Numeric dotted compare; a `v` prefix and anything after `-` are ignored. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { p -> p.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    /**
     * Markdown-lite for a notes sheet: a flat list of lines tagged by kind so a
     * screen can draw headings, bullets and paragraphs without a markdown library.
     */
    sealed class Line {
        data class Heading(val text: String) : Line()
        data class Bullet(val text: String) : Line()
        data class Para(val text: String) : Line()
    }

    fun lines(markdown: String): List<Line> {
        val out = ArrayList<Line>()
        val para = StringBuilder()
        fun flushPara() { if (para.isNotBlank()) out += Line.Para(para.toString().trim()); para.setLength(0) }
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> { flushPara(); out += Line.Heading(line.removePrefix("## ").trim()) }
                line.startsWith("### ") -> { flushPara(); out += Line.Heading(line.removePrefix("### ").trim()) }
                line.startsWith("- ") || line.startsWith("* ") -> { flushPara(); out += Line.Bullet(strip(line.drop(2).trim())) }
                line.startsWith("  ") && out.lastOrNull() is Line.Bullet && para.isEmpty() -> {
                    val last = out.removeAt(out.size - 1) as Line.Bullet
                    out += Line.Bullet(last.text + " " + strip(line.trim()))
                }
                line.isBlank() -> flushPara()
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(strip(line.trim())) }
            }
        }
        flushPara()
        return out
    }

    /** Drops the inline markup we use (`**bold**`, `` `code` ``), keeping the words. */
    fun strip(s: String): String = s.replace("**", "").replace("`", "")
}
