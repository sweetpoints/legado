package io.legado.app.help.source

import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExploreScriptValueTest {
    @Test
    fun arraysAndObjectsBecomeJsonRatherThanJvmMapText() {
        val value = listOf(mapOf("title" to "分类", "url" to "https://example.org"))
        assertEquals(GSON.toJson(value), exploreScriptResultText(value))
        assertEquals(
            "分类",
            JsonParser.parseString(exploreScriptResultText(mapOf("title" to "分类")))
                .asJsonObject
                .get("title")
                .asString,
        )
        assertEquals("A::https://example.org", exploreScriptResultText("A::https://example.org"))
        assertEquals("", exploreScriptResultText(null))
    }

    @Test
    fun infoMapAcceptsOnlyJsonStringEntriesAndCopiesInput() {
        val original = mutableMapOf<String, Any?>("query" to "keyword")
        val copy = validateExploreInfoMap(original)
        original["query"] = "changed"
        assertEquals(mapOf("query" to "keyword"), copy)
        for (invalid in
            listOf(
                null,
                listOf("x"),
                mapOf("key" to null),
                mapOf("key" to 1),
                mapOf(1 to "value"),
            )) {
            assertThrows(IllegalArgumentException::class.java) { validateExploreInfoMap(invalid) }
        }
    }
}
