package io.legado.app.model.sourceEngine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyVariableScopesTest {
    private fun state(
        id: String,
        target: String = "book",
        book: Map<String, String> = emptyMap(),
        chapter: Map<String, String> = emptyMap(),
    ) =
        mapOf(
            "id" to id,
            "target" to target,
            "source" to mapOf("fallback" to "source"),
            "book" to book,
            "chapter" to chapter,
        )

    @Test
    fun interleavedRowsKeepIndependentWritesAndIgnoreStaleSnapshots() {
        val scopes = LegacyVariableScopes()
        val first = scopes.select(state("a"))!!
        val second = scopes.select(state("b"))!!
        first.putVariable("saved", "A")
        second.putVariable("saved", "B")
        assertEquals(
            "A",
            scopes.select(state("a", book = mapOf("saved" to "stale")))!!.getVariable("saved"),
        )
        assertEquals("B", scopes.select(state("b"))!!.getVariable("saved"))
        assertEquals("A", LegacyVariableScopes().select(first.snapshot())!!.getVariable("saved"))
        assertEquals(emptyMap<String, String>(), first.snapshot()["chapter"])
    }

    @Test
    fun chapterWritesDoNotChangeBookAndEmptyValueFallsBack() {
        val values =
            LegacyVariableScopes().select(state("chapter", "chapter", mapOf("saved" to "book")))!!
        assertEquals("book", values.getVariable("saved"))
        values.putVariable("saved", "chapter")
        assertEquals("chapter", values.getVariable("saved"))
        assertEquals(mapOf("saved" to "book"), values.snapshot()["book"])
        values.putVariable("saved", "")
        assertEquals("book", values.getVariable("saved"))
        assertEquals("source", values.getVariable("fallback"))
    }

    @Test
    fun largeValuesAndRemovalRemainInTheSelectedLayer() {
        val values = LegacyVariableScopes().select(state("a"))!!
        val large = "x".repeat(10_001)
        values.putVariable("large", large)
        assertEquals(large, values.getVariable("large"))
        values.putVariable("large", null)
        assertEquals(emptyMap<String, String>(), values.snapshot()["book"])
        assertEquals(mapOf("large" to ""), values.writes())
    }

    @Test
    fun malformedLayerAndChangedTargetAreRejected() {
        val scopes = LegacyVariableScopes()
        scopes.select(state("a"))
        assertThrows(SourceScriptException::class.java) { scopes.select(state("a", "chapter")) }
        assertThrows(SourceScriptException::class.java) {
            scopes.select(state("b") + ("book" to mapOf("bad" to 1)))
        }
    }
}
