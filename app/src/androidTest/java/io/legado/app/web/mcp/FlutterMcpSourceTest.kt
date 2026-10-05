package io.legado.app.web.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.BuildConfig
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlutterMcpSourceTest {
    @Before
    fun requireFlutterEngine() {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
    }

    private fun source() =
        BookSource(
            bookSourceUrl = "https://mcp-example.com",
            bookSourceName = "MCP test",
            mainJs = "var mainLoaded = true",
        )

    @Test
    fun requestContextExecutesActualV8AndKeepsJsonMetadataContract() = runBlocking {
        withTimeout(60_000) {
            val source = source()
            val objectResult =
                McpSourceScriptEvaluator.evaluate(source, "({message: 'ok', items: [1, 2]})")
                    .orEmpty()
            assertTrue(objectResult.contains("\"message\":\"ok\""))
            assertTrue(objectResult.contains("\"items\":[1,2]"))
            assertEquals("plain", McpSourceScriptEvaluator.evaluate(source, "'plain'"))
            assertNull(McpSourceScriptEvaluator.evaluate(source, "null"))
            assertEquals(
                "https://mcp-example.com|https://mcp-example.com|https://mcp-example.com|undefined",
                McpSourceScriptEvaluator.evaluate(
                    source,
                    "baseUrl + '|' + source.bookSourceUrl + '|' + sourceApi.bookSourceUrl + '|' + typeof mainLoaded",
                ),
            )
            assertEquals(
                "async",
                withContext(Dispatchers.Main.immediate) {
                    McpSourceScriptEvaluator.evaluate(source, "Promise.resolve('async')")
                },
            )
            assertEquals(
                "available",
                McpSourceScriptEvaluator.evaluate(
                    source,
                    "(async()=>{await source.variables.put('mcp-key','available');return await source.variables.get('mcp-key')})()",
                ),
            )
        }
    }

    @Test
    fun requestCancellationStopsActualV8AndNextRequestSucceeds() = runBlocking {
        val source = source()
        // Initialize before timing cancellation so this case checks execution rather than startup.
        assertEquals(
            "ready",
            withTimeout(60_000) { McpSourceScriptEvaluator.evaluate(source, "'ready'") },
        )
        val failure = runCatching {
            withTimeout(500) { McpSourceScriptEvaluator.evaluate(source, "while (true) {}") }
        }
        assertTrue(failure.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(
            "recovered",
            withTimeout(15_000) { McpSourceScriptEvaluator.evaluate(source, "'recovered'") },
        )
    }
}
