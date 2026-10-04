package io.legado.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.legado.app.BuildConfig
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.SourceEngineBackend
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
                    return [{name:title,author:'Author',bookUrl:'https://example.org/book',tocUrl:'https://example.org/toc'}];
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
