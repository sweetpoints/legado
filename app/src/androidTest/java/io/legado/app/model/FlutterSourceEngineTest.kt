package io.legado.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.legado.app.BuildConfig
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.TocEmptyException
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.SourceEngineBackend
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlutterSourceEngineTest {
    private fun backend(): SourceEngineBackend {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val implementation =
            Class.forName(
                "io.legado.app.model.sourceEngine.FlutterSourceRepository",
                true,
                context.classLoader,
            )
        return implementation
            .getConstructor(android.content.Context::class.java)
            .newInstance(context) as SourceEngineBackend
    }

    private fun source(script: String): String =
        Gson()
            .toJson(
                mapOf(
                    "schemaVersion" to 1,
                    "id" to "https://example.org/test",
                    "name" to "V8 test",
                    "baseUrl" to "https://example.org/",
                    "script" to script,
                )
            )

    @Test
    fun composeRepositoryRunsActualDartV8AndAsyncFunctions() = runBlocking {
        val bridge = backend()
        val definition =
            source(
                """
                async function search(input) {
                    const title = await Promise.resolve(input.key + ' V8');
                    return [{name:title,author:'Author',bookUrl:'https://example.org/book',tocUrl:'https://example.org/toc',digest:await source.crypto.md5('abc')}];
                }
                async function getBookInfo(input) {
                    return {name:'',author:'',tocUrl:'',coverUrl:'',intro:'',kind:'',wordCount:'',latestChapterTitle:''};
                }
                async function getChapters(input) {
                    return [{title:'Chapter 1',url:'https://example.org/chapter'}];
                }
                async function getContent(input) { return await Promise.resolve('Actual V8 content'); }
                """
                    .trimIndent()
            )
        withTimeout(60_000) {
            assertEquals(
                "900150983cd24fb0d6963f7d28e17f72",
                bridge.execute("search", definition, mapOf("key" to "Test")).single()["digest"],
            )
            val oldApi = runCatching {
                bridge.execute(
                    "search",
                    source("function search(){return [{name:java.md5Encode('abc')}] }"),
                    emptyMap(),
                )
            }
            assertTrue("Modern source must reject java.*", oldApi.isFailure)
            assertTrue(oldApi.exceptionOrNull()?.message.orEmpty().contains("script_error"))
            assertEquals(
                "Test V8",
                bridge.execute("search", definition, mapOf("key" to "Test")).single()["name"],
            )
            assertEquals(
                "Chapter 1",
                bridge.execute("toc", definition, emptyMap()).single()["title"],
            )
            assertEquals(
                "Actual V8 content",
                bridge.execute("content", definition, emptyMap()).single()["content"],
            )
        }
        val selected =
            BookSource(bookSourceUrl = "https://example.org/test", bookSourceName = "V8 test")
                .apply {
                    bookSourceComment = "@source:v1 $definition"
                }
        assertTrue(DartSourceEngine.selected(selected))
        val result = withTimeout(60_000) { WebBook.searchBookAwait(selected, "Compose") }
        assertEquals("Compose V8", result.single().name)
        assertEquals(selected.bookSourceUrl, result.single().origin)
        val existing =
            Book(bookUrl = "https://example.org/book", name = "Existing", author = "Author").apply {
                intro = "Existing introduction"
            }
        WebBook.getBookInfoAwait(selected, existing)
        assertEquals("Existing", existing.name)
        assertEquals("Author", existing.author)
        assertEquals("Existing introduction", existing.intro)
        assertEquals(existing.bookUrl, existing.tocUrl)
        val chapters = WebBook.getChapterListAwait(selected, existing).getOrThrow()
        assertEquals(1, chapters.size)
        assertEquals(1, existing.totalChapterNum)
        assertEquals(1, existing.lastCheckCount)
        assertEquals("Chapter 1", existing.latestChapterTitle)
        assertEquals("Chapter 1", existing.durChapterTitle)
        assertTrue(existing.lastCheckTime > 0)
        assertTrue(existing.latestChapterTime > 0)
        bridge.close()
    }

    @Test
    fun cancellationStopsInfiniteScriptAndEngineRemainsUsable() = runBlocking {
        val bridge = backend()
        val blocked = async {
            bridge.execute("search", source("function search(){while(true){}}"), emptyMap())
        }
        delay(2_000)
        withTimeout(10_000) {
            blocked.cancel()
            blocked.join()
        }
        val result =
            withTimeout(30_000) {
                bridge.execute(
                    "search",
                    source("function search(){return [{name:'Recovered'}]}"),
                    emptyMap(),
                )
            }
        assertEquals("Recovered", result.single()["name"])
        bridge.close()
    }

    @Test
    fun emptyDartTocFailsWithoutChangingBookMetadata() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        val definition = source("function getChapters(){return []}")
        val selected =
            BookSource(bookSourceUrl = "https://example.org/test").apply {
                bookSourceComment = "@source:v1 $definition"
            }
        val book =
            Book(bookUrl = "https://example.org/book").apply {
                tocUrl = "https://example.org/toc"
                totalChapterNum = 7
                latestChapterTitle = "Known chapter"
            }
        val result = withTimeout(60_000) { WebBook.getChapterListAwait(selected, book) }
        assertTrue(result.exceptionOrNull() is TocEmptyException)
        assertEquals(7, book.totalChapterNum)
        assertEquals("Known chapter", book.latestChapterTitle)
    }

    @Test
    fun synchronousSendFailureCleansTaskAndCloseWakesPendingRequest() = runBlocking {
        val bridge = backend()
        try {
            // Warm the engine before intentionally passing an unsupported channel value.
            bridge.execute("search", source("function search(){return []}"), emptyMap())
            val invalid = runCatching {
                bridge.execute(
                    "search",
                    source("function search(){return []}"),
                    mapOf("invalid" to Any()),
                )
            }
            assertTrue(invalid.isFailure)
            assertTrue(bridge.tasks.value.isEmpty())
            val pending = async {
                runCatching {
                    bridge.execute(
                        "search",
                        source("async function search(){await new Promise(()=>{});return []}"),
                        emptyMap(),
                    )
                }
            }
            withTimeout(5_000) {
                while (bridge.tasks.value.isEmpty()) delay(20)
            }
            val closing = async { runCatching { bridge.close() } }
            val failure = withTimeout(5_000) { pending.await() }
            assertTrue(failure.isFailure)
            assertTrue(
                failure.exceptionOrNull()?.message.orEmpty().contains("repository is closed")
            )
            withTimeout(20_000) { closing.await() }
            assertTrue(bridge.tasks.value.isEmpty())
        } finally {
            bridge.close()
        }
    }

    @Test
    fun closeDuringStartupWakesReadyWaiter() = runBlocking {
        val bridge = backend()
        try {
            withContext(Dispatchers.Main.immediate) {
                // Run through engine creation to ready.await without letting the main
                // message queue deliver the Dart ready handshake between these steps.
                val startup =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            bridge.execute(
                                "search",
                                source("function search(){return []}"),
                                emptyMap(),
                            )
                        }
                    }
                assertTrue("Must exercise the startup waiter", !startup.isCompleted)
                assertTrue(bridge.tasks.value.isEmpty())
                val closing =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { bridge.close() }
                    }
                val failure = withTimeout(5_000) { startup.await() }
                assertTrue(failure.isFailure)
                assertTrue(
                    failure.exceptionOrNull()?.message.orEmpty().contains("repository is closed")
                )
                withTimeout(20_000) { closing.await() }
                assertTrue(bridge.tasks.value.isEmpty())
            }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun sessionVariablesSurviveEngineShutdownAndStaySourceIsolated() = runBlocking {
        val unique = java.util.UUID.randomUUID().toString()
        val definition =
            Gson()
                .toJson(
                    mapOf(
                        "schemaVersion" to 1,
                        "id" to "https://example.org/persistence/$unique",
                        "name" to "Persistence",
                        "baseUrl" to "https://example.org/",
                        "script" to
                            """
                            async function search(input) {
                                if (input.value) await source.variables.put('saved', input.value);
                                return [{name: (await source.variables.get('saved')) || ''}];
                            }
                            """
                                .trimIndent(),
                    )
                )
        val first = backend()
        assertEquals(
            "persisted",
            withTimeout(60_000) {
                first.execute("search", definition, mapOf("value" to "persisted")).single()["name"]
            },
        )
        first.close()
        val second = backend()
        try {
            assertEquals(
                "persisted",
                withTimeout(60_000) {
                    second.execute("search", definition, emptyMap()).single()["name"]
                },
            )
            val other = definition.replace("/persistence/$unique", "/persistence/other-$unique")
            assertEquals(
                "",
                withTimeout(60_000) {
                    second.execute("search", other, emptyMap()).single()["name"]
                },
            )
        } finally {
            second.close()
        }
    }
}
