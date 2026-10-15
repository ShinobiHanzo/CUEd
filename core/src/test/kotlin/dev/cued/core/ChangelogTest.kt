package dev.cued.core

import dev.cued.core.changelog.Changelog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangelogTest {
    private val log = """
        # Changelog

        ## 0.3.1

        - **Patch notes** on first launch after an update.
          Continued on the next line.
        - Second item with `code`.

        ## 0.3.0

        Three things.

        - zero

        ## 0.2.6

        - old
    """.trimIndent()

    @Test fun sectionsAndBetween() {
        val s = Changelog.sections(log)
        assertEquals(listOf("0.3.1", "0.3.0", "0.2.6"), s.map { it.version })
        assertTrue(s[1].body.startsWith("Three things."))
        val unseen = Changelog.between(log, "0.2.6", "0.3.1")
        assertEquals(listOf("0.3.1", "0.3.0"), unseen.map { it.version })
        assertEquals(listOf("0.3.1"), Changelog.between(log, "0.3.0", "0.3.1").map { it.version })
        assertEquals(3, Changelog.between(log, null, "0.3.1").size)
        assertTrue(Changelog.between(log, "0.3.1", "0.3.1").isEmpty())
        assertTrue(Changelog.render(unseen).contains("## 0.3.0"))
    }

    @Test fun compareVersions() {
        assertTrue(Changelog.compare("0.3.1", "0.3.0") > 0)
        assertEquals(0, Changelog.compare("v0.3.1", "0.3.1-debug"))
        assertTrue(Changelog.compare("0.10.0", "0.9.9") > 0)
        assertEquals(0, Changelog.compare("1.0", "1.0.0"))
    }

    @Test fun markdownLiteLines() {
        val lines = Changelog.lines(Changelog.render(Changelog.between(log, "0.2.6", "0.3.1")))
        assertEquals(Changelog.Line.Heading("0.3.1"), lines[0])
        assertEquals(Changelog.Line.Bullet("Patch notes on first launch after an update. Continued on the next line."), lines[1])
        assertEquals(Changelog.Line.Bullet("Second item with code."), lines[2])
        assertEquals(Changelog.Line.Heading("0.3.0"), lines[3])
        assertEquals(Changelog.Line.Para("Three things."), lines[4])
        assertEquals(Changelog.Line.Bullet("zero"), lines[5])
    }
}
