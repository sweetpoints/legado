package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceParsingBoundaryTest {
    @Test
    fun bookSourceCanUsePureJsonExtractionWithoutExecutingJavaScript() {
        val rule =
            AnalyzeRule(source = BookSource(bookSourceUrl = "https://fixture.invalid"))
                .setContent(
                    mapOf(
                        "book" to mapOf("title" to "Nested"),
                        "items" to listOf(mapOf("name" to "First"), mapOf("name" to "Second")),
                    )
                )
        assertEquals("Nested", rule.getString("$.book.title"))
        assertEquals(listOf("First", "Second"), rule.getStringList("$.items[*].name"))
        assertEquals(emptyList<String>(), rule.getStringList("$.missing[*]"))
    }

    @Test
    fun nestedScriptCallbackRejectsBeforeVmExecutionWithStableDiagnostics() {
        val rule = AnalyzeRule(source = BookSource(bookSourceUrl = "https://fixture.invalid"))
        val script = "throw new Error('private-body-not-exposed')"
        val error =
            assertThrows(SourceScriptException::class.java) {
                rule.withScriptCallback { rule.evalJS(script) }
            }
        assertEquals("nested_script_requires_migration", error.code)
        assertTrue(error.message.orEmpty().contains("nested_script_requires_migration"))
        assertFalse(error.message.orEmpty().contains(script))
        assertFalse(error.message.orEmpty().contains("private-body-not-exposed"))
    }

    @Test
    fun pureJsonParsingRemainsAvailableInsideNestedCallbackScopes() {
        val rule =
            AnalyzeRule(source = BookSource(bookSourceUrl = "https://fixture.invalid"))
                .setContent(mapOf("title" to "Available"))
        rule.withScriptCallback {
            rule.withScriptCallback { assertEquals("Available", rule.getString("$.title")) }
            val error =
                assertThrows(SourceScriptException::class.java) { rule.evalJS("mustNotExecute()") }
            assertEquals("nested_script_requires_migration", error.code)
        }
        assertEquals("Available", rule.getString("$.title"))
    }
}
