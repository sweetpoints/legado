package io.legado.app.model.rss

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Test
import org.junit.Assert.*

class RssReaderVariablesTest {
    private fun values(value: String?) = GSON.fromJsonObject<Map<String, String>>(value).getOrThrow()
    @Test fun parserAddsUpdatesAndRemovalsApplyWhenTheirBaselineHasNotChanged() {
        val result = values(mergeRssReaderVariables("{\"before\":\"old\",\"removed\":\"value\"}",
            "{\"before\":\"new\",\"added\":\"fresh\"}", "{\"before\":\"old\",\"removed\":\"value\",\"concurrent\":\"keep\"}"))
        assertEquals(mapOf("before" to "new", "added" to "fresh", "concurrent" to "keep"), result)
    }
    @Test fun concurrentEditsAndRemovalsWinOverParserChangesToTheSameKeys() {
        val result = values(mergeRssReaderVariables("{\"edited\":\"old\",\"removed\":\"old\"}",
            "{\"edited\":\"parser\",\"removed\":\"parser\",\"new\":\"parser\"}", "{\"edited\":\"external\",\"new\":\"external\"}"))
        assertEquals(mapOf("edited" to "external", "new" to "external"), result)
    }
    @Test fun unchangedParserPreservesExactLatestRawJsonIncludingMalformedLegacyValue() {
        assertEquals("Exact legacy variable", mergeRssReaderVariables("Old", "Old", "Exact legacy variable"))
        assertEquals(" {\"key\": \"value\"} ", mergeRssReaderVariables("{}", "{}", " {\"key\": \"value\"} "))
    }
}
