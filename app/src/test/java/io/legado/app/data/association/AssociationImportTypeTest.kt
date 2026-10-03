package io.legado.app.data.association

import io.legado.app.data.entities.HighlightRuleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssociationImportTypeTest {
    @Test
    fun completeLegacyFormatsRetainTheirClassification() {
        val cases =
            listOf(
                "highlightRule" to mapOf("type" to HighlightRuleFile.TYPE),
                "bookSource" to mapOf("bookSourceUrl" to "source"),
                "rssSource" to mapOf("sourceUrl" to "source"),
                "highlightRule" to mapOf("pattern" to "x", "style" to "{}", "uuid" to "id"),
                "replaceRule" to mapOf("pattern" to "x", "replacement" to "y"),
                "theme" to mapOf("themeName" to "Theme"),
                "dictRule" to mapOf("showRule" to "rule"),
                "txtRule" to mapOf("name" to "Rule", "rule" to "regex"),
                "autoTask" to mapOf("cron" to "0 * * * *", "script" to "run"),
                "httpTts" to mapOf("name" to "Speech", "url" to "https://speech"),
                "bookshelf" to mapOf("name" to "Book", "author" to "Author"),
            )
        for ((expected, record) in cases) {
            assertEquals(expected, associationJsonImportType(record))
        }
    }

    @Test
    fun earlierLegacyFormatsWinWhenRecordsContainOverlappingKeys() {
        assertEquals(
            "highlightRule",
            associationJsonImportType(
                mapOf(
                    "type" to HighlightRuleFile.TYPE,
                    "bookSourceUrl" to "source",
                    "pattern" to "x",
                    "replacement" to "y",
                )
            ),
        )
        assertEquals(
            "bookSource",
            associationJsonImportType(mapOf("bookSourceUrl" to "source", "sourceUrl" to "rss")),
        )
        assertEquals(
            "rssSource",
            associationJsonImportType(mapOf("sourceUrl" to "rss", "pattern" to "x")),
        )
        assertEquals(
            "theme",
            associationJsonImportType(mapOf("themeName" to "Theme", "showRule" to "dictionary")),
        )
        assertEquals(
            "txtRule",
            associationJsonImportType(
                mapOf("name" to "Rule", "rule" to "regex", "cron" to "daily", "script" to "run")
            ),
        )
        assertEquals(
            "httpTts",
            associationJsonImportType(
                mapOf("name" to "Speech", "url" to "url", "author" to "Author")
            ),
        )
    }

    @Test
    fun ambiguousUntypedHighlightRecordsAndUnknownFormatsRemainRejected() {
        for (record in
            listOf(
                mapOf("pattern" to "x", "style" to "{}"),
                mapOf("pattern" to "x", "uuid" to "id"),
                mapOf("pattern" to "x", "style" to "{}", "uuid" to "id", "replacement" to "y"),
                mapOf("cron" to "daily"),
                mapOf("script" to "run"),
                emptyMap(),
            )) {
            assertNull(associationJsonImportType(record))
        }
    }
}
