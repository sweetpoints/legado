package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceScriptBridgeTest {
    @Test
    fun mainThreadRejectsBeforeConfiguringBindingsOrInvokingBackend() {
        var configured = false
        var evaluated = false
        assertThrows(IllegalStateException::class.java) {
            BookSourceScriptBridge.evaluate(
                BookSource(),
                "must not execute",
                { configured = true },
                true,
            ) { _, _, _ ->
                evaluated = true
            }
        }
        assertFalse(configured)
        assertFalse(evaluated)
    }

    @Test
    fun workerCallsOnlyProvidedV8EvaluatorWithExplicitJsonBindings() {
        val source = BookSource()
        var called = false
        val result =
            BookSourceScriptBridge.evaluate(
                source,
                "V8 script",
                {
                    put("java", Any())
                    put("source", source)
                    put("result", mapOf("value" to listOf(1, "two", true)))
                },
                false,
            ) { actualSource, script, values ->
                called = true
                assertTrue(actualSource === source)
                assertEquals("V8 script", script)
                assertEquals(mapOf("result" to mapOf("value" to listOf(1, "two", true))), values)
                "V8 result"
            }
        assertTrue(called)
        assertEquals("V8 result", result)
    }

    @Test
    fun hostObjectsAreFilteredButOtherObjectsAreRejectedWithoutReflection() {
        assertEquals(
            emptyMap<String, Any?>(),
            BookSourceScriptBridge.jsonBindings(mapOf("cache" to Any())),
        )
        for (value in listOf(Any(), Double.NaN, mapOf(1 to "number key"))) {
            assertThrows(BookSourceBindingsUnsupportedException::class.java) {
                BookSourceScriptBridge.jsonBindings(mapOf("result" to value))
            }
        }
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        assertThrows(BookSourceBindingsUnsupportedException::class.java) {
            BookSourceScriptBridge.jsonBindings(mapOf("result" to cycle))
        }
    }
}
