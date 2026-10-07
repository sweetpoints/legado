package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Calls the real Android facade and V8; no Room registration or network is required. */
@RunWith(AndroidJUnit4::class)
class LegacyAuxiliaryFacadeV8IntegrationTest {
    @Test
    fun opaqueSourceLoadsItsLibraryOnceAndDefersDynamicHeadersUntilNetworking(): Unit =
        runBlocking(Dispatchers.IO) {
            val source =
                BookSource(
                    bookSourceUrl = "opaque-aux-${UUID.randomUUID()}",
                    bookSourceName = "",
                    jsLib =
                        "globalThis.auxLibraryLoads=(globalThis.auxLibraryLoads||0)+1; function auxLibrary(value){return value+':'+auxLibraryLoads;}",
                    header = "@js:java.put('headerRan','yes'); ({'X-Fixture':'value'})",
                )
            try {
                val first =
                    withTimeout(15_000) {
                        DartSourceEngine.evaluate(
                            source,
                            "({value:auxLibrary('first'),key:source.getKey(),header:java.get('headerRan'),put:java.put('saved','value')})",
                        )
                    }
                        as Map<*, *>
                assertEquals("first:1", first["value"])
                assertEquals(source.bookSourceUrl, first["key"])
                assertEquals("", first["header"])
                assertEquals("value", first["put"])
                val second =
                    withTimeout(15_000) {
                        DartSourceEngine.evaluate(
                            source,
                            "({value:auxLibrary('second'),saved:java.get('saved'),loads:auxLibraryLoads})",
                        )
                    }
                        as Map<*, *>
                assertEquals("second:1", second["value"])
                assertEquals("value", second["saved"])
                assertEquals(1, (second["loads"] as Number).toInt())
                assertEquals("", source.get("headerRan"))
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun existingEntityCallbacksRemainTheVariableOwnerInsteadOfSourceGlobalState(): Unit =
        runBlocking(Dispatchers.IO) {
            val source =
                BookSource(
                    bookSourceUrl = "opaque-bound-${UUID.randomUUID()}",
                    bookSourceName = "Fixture",
                )
            source.put("saved", "source-value")
            val entity = linkedMapOf("saved" to "book-value")
            val callbacks = SourceHostCallbacks { method, arguments ->
                when (method) {
                    "analyze.get" -> entity[arguments[0] as String].orEmpty()
                    "analyze.put" ->
                        (arguments[1] as String).also { entity[arguments[0] as String] = it }
                    else -> error("Unexpected callback $method")
                }
            }
            try {
                val result =
                    withContext(callbacks) {
                        withTimeout(15_000) {
                            DartSourceEngine.evaluate(
                                source,
                                "({saved:java.get('saved'),put:java.put('newKey','entity-value')})",
                            )
                        }
                    }
                        as Map<*, *>
                assertEquals("book-value", result["saved"])
                assertEquals("entity-value", result["put"])
                assertEquals("entity-value", entity["newKey"])
                assertEquals("source-value", source.get("saved"))
                assertEquals("", source.get("newKey"))
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun invalidModernCarrierStaysStrictAndDoesNotLoadTheLegacyLibrary(): Unit =
        runBlocking(Dispatchers.IO) {
            val source =
                BookSource(
                    bookSourceUrl = "https://fixture.invalid/${UUID.randomUUID()}",
                    bookSourceName = "Carrier",
                    jsLib =
                        "throw new Error('legacy library must not execute for modern carrier');",
                    bookSourceComment =
                        "@source:v1 {\"schemaVersion\":1,\"id\":\"modern-${UUID.randomUUID()}\",\"name\":\"\",\"baseUrl\":\"https://fixture.invalid/\"}",
                )
            val failure = runCatching {
                withTimeout(15_000) { DartSourceEngine.evaluate(source, "1") }
            }.exceptionOrNull()
            assertTrue(failure is SourceHostException)
            assertEquals("invalid_source", (failure as SourceHostException).code)
        }
}
