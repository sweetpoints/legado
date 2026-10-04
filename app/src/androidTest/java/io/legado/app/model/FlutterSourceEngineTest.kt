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
    fun legacyTocFlagsKeepLegacyTruthinessAndModernFlagsRequireBoolean() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        fun selected(definition: String) =
            BookSource(bookSourceUrl = "https://example.org/test").apply {
                bookSourceComment = "@source:v1 $definition"
            }
        fun book() =
            Book(bookUrl = "https://example.org/book").apply { tocUrl = "https://example.org/toc" }
        val flags =
            source(
                "function getChapters(){return [{title:'Chapter',url:'https://example.org/chapter',isVip:'VIP',isPay:'已购买',isVolume:'卷名',updateTime:'Yesterday'}]}"
            )
        val legacyJson =
            Gson()
                .fromJson(flags, com.google.gson.JsonObject::class.java)
                .apply {
                    add("metadata", Gson().toJsonTree(mapOf("legacy" to true)))
                }
                .toString()
        val legacy =
            withTimeout(60_000) {
                WebBook.getChapterListAwait(selected(legacyJson), book()).getOrThrow().single()
            }
        assertTrue(legacy.isVip)
        assertTrue(legacy.isPay)
        assertTrue(legacy.isVolume)
        assertEquals("Yesterday", legacy.tag)
        val modernStrings = WebBook.getChapterListAwait(selected(flags), book())
        assertTrue(modernStrings.isFailure)
        assertTrue(modernStrings.exceptionOrNull()?.message.orEmpty().contains("must be a Boolean"))
        val modern =
            WebBook.getChapterListAwait(
                    selected(
                        source(
                            "function getChapters(){return [{title:'Chapter',url:'https://example.org/chapter',isVip:true,isPay:false,isVolume:false,tag:'Modern tag'}]}"
                        )
                    ),
                    book(),
                )
                .getOrThrow()
                .single()
        assertTrue(modern.isVip)
        assertTrue(!modern.isPay)
        assertTrue(!modern.isVolume)
        assertEquals("Modern tag", modern.tag)

        // Exercise the original @engine:dart importer path against a local HTML fixture.
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val origin = "http://127.0.0.1:${server.localPort}"
            val serving =
                async(Dispatchers.IO) {
                    repeat(3) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) {}
                            val body =
                                "<div class='row'><a href=''>Chapter</a><span class='vip'>VIP</span><span class='pay'>已购买</span><span class='volume'>卷名</span><span class='time'>Yesterday</span><span class='yes'>true</span><span class='no'>false</span></div>"
                                    .toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            val original =
                Gson()
                    .fromJson(
                        Gson()
                            .toJson(
                                mapOf(
                                    "bookSourceUrl" to origin,
                                    "bookSourceName" to "Local legacy",
                                    "bookSourceComment" to "@engine:dart",
                                    "enabledCookieJar" to true,
                                    "ruleToc" to
                                        mapOf(
                                            "chapterList" to ".row",
                                            "chapterName" to "a@text",
                                            "chapterUrl" to "a@href",
                                            "isVip" to ".vip@text",
                                            "isPay" to ".pay@text",
                                            "isVolume" to ".volume@text",
                                            "updateTime" to ".time@text",
                                        ),
                                )
                            ),
                        BookSource::class.java,
                    )
            val originalBook = Book(bookUrl = "$origin/book").apply { tocUrl = "$origin/toc" }
            val chapter =
                withTimeout(60_000) {
                    WebBook.getChapterListAwait(original, originalBook).getOrThrow().single()
                }
            assertTrue(chapter.isVip && chapter.isPay && chapter.isVolume)
            assertEquals("Yesterday", chapter.tag)
            assertEquals("Chapter0", chapter.url)
            assertEquals(
                "",
                WebBook.getContentAwait(original, originalBook, chapter, needSave = false),
            )
            val stages =
                mapOf(
                    "toc" to
                        mapOf(
                            "url" to "{{tocUrl}}",
                            "list" to "@legacy:.row",
                            "fields" to
                                mapOf(
                                    "title" to "@legacy:a@text",
                                    "url" to "@legacy:a@href",
                                    "isVip" to "@legacy:.vip@text",
                                    "isPay" to "@legacy:.pay@text",
                                    "isVolume" to "@legacy:.volume@text",
                                    "updateTime" to "@legacy:.time@text",
                                ),
                        )
                )
            // Final migrated candidates run modern mode but retain legacyOriginal DTO provenance.
            val candidate =
                Gson()
                    .toJson(
                        mapOf(
                            "schemaVersion" to 1,
                            "id" to "$origin/migrated",
                            "name" to "Migrated",
                            "baseUrl" to origin,
                            "stages" to stages,
                            "metadata" to
                                mapOf(
                                    "legacy" to false,
                                    "legacyOriginal" to Gson().toJsonTree(original),
                                ),
                        )
                    )
            val migrated =
                withTimeout(60_000) {
                    WebBook.getChapterListAwait(selected(candidate), originalBook)
                        .getOrThrow()
                        .single()
                }
            assertTrue(migrated.isVip && migrated.isPay && migrated.isVolume)
            assertEquals("Yesterday", migrated.tag)
            val modernStage =
                Gson()
                    .toJson(
                        mapOf(
                            "schemaVersion" to 1,
                            "id" to "$origin/modern",
                            "name" to "Modern stages",
                            "baseUrl" to origin,
                            "stages" to
                                mapOf(
                                    "toc" to
                                        mapOf(
                                            "url" to "{{tocUrl}}",
                                            "list" to "@css:.row",
                                            "fields" to
                                                mapOf(
                                                    "title" to "@css:a@text",
                                                    "url" to "@css:a@href",
                                                    "isVip" to "@css:.yes@text",
                                                    "isPay" to "@css:.no@text",
                                                    "isVolume" to "@css:.no@text",
                                                ),
                                        )
                                ),
                        )
                    )
            val stageChapter =
                withTimeout(60_000) {
                    WebBook.getChapterListAwait(selected(modernStage), originalBook)
                        .getOrThrow()
                        .single()
                }
            assertTrue(stageChapter.isVip)
            assertTrue(!stageChapter.isPay && !stageChapter.isVolume)
            serving.await()
        }
    }

    @Test
    fun volumeHeadingSkipsDartContentExecution() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        val selected =
            BookSource(bookSourceUrl = "https://example.org/test").apply {
                bookSourceComment =
                    "@source:v1 ${source("function getChapters(){return [{title:'第一卷',url:'',isVolume:true},{title:'第一卷',url:'',isVolume:true}]} function getContent(){throw new Error('Volume content must not execute')}")}"
            }
        val book =
            Book(bookUrl = "https://example.org/book").apply { tocUrl = "https://example.org/toc" }
        val headings =
            withTimeout(60_000) { WebBook.getChapterListAwait(selected, book).getOrThrow() }
        assertEquals(listOf("第一卷0", "第一卷1"), headings.map { it.url })
        for (heading in headings) {
            assertEquals("", WebBook.getContentAwait(selected, book, heading, needSave = false))
        }
        val heading = headings.first()
        val normal = heading.copy(url = "https://example.org/chapter", isVolume = false)
        val failure = runCatching {
            WebBook.getContentAwait(selected, book, normal, needSave = false)
        }
        assertTrue(
            failure.exceptionOrNull()?.message.orEmpty().contains("Volume content must not execute")
        )
    }

    @Test
    fun legacyExploreUsesSelectedCategoryAndNormalizesBookFields() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val origin = "http://127.0.0.1:${server.localPort}"
            val requests = mutableListOf<String>()
            val serving =
                async(Dispatchers.IO) {
                    repeat(3) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader()
                            requests.add(reader.readLine().split(' ')[1])
                            while (!reader.readLine().isNullOrEmpty()) {}
                            val body =
                                "<div class='row'><a href='/book'>  Title 作者 Author  </a><span class='author'>  作者：Author  </span><span class='words'>123</span><span class='kind'>Fantasy</span><span class='kind'>Adventure</span></div>"
                                    .toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            val fields =
                mapOf(
                    "name" to "a@text",
                    "author" to ".author@text",
                    "wordCount" to ".words@text",
                    "kind" to ".kind@text",
                    "bookUrl" to "a@href",
                )
            val definition =
                mapOf(
                    "bookSourceUrl" to origin,
                    "bookSourceName" to "Legacy metadata",
                    "bookSourceComment" to "@engine:dart",
                    "enabledCookieJar" to true,
                    "searchUrl" to "$origin/search",
                    "exploreUrl" to "A::$origin/a\nB::$origin/b?page={{page}}",
                    "ruleSearch" to (fields + mapOf("bookList" to ".row")),
                    "ruleExplore" to (fields + mapOf("bookList" to ".row")),
                    "ruleBookInfo" to (fields - "bookUrl" + mapOf("canReName" to "@text")),
                )
            val selected = Gson().fromJson(Gson().toJson(definition), BookSource::class.java)
            val explored =
                withTimeout(60_000) {
                    WebBook.exploreBookAwait(selected, "$origin/b?page={{page}}", 2)
                }
            assertEquals("Title", explored.single().name)
            assertEquals("Author", explored.single().author)
            assertEquals("123字", explored.single().wordCount)
            assertEquals("Fantasy,Adventure", explored.single().kind)
            val searched =
                withTimeout(60_000) {
                    WebBook.searchBookAwait(
                        selected,
                        "Title",
                        filter = { name, author, _ -> name == "Title" && author == "Author" },
                    )
                }
            assertEquals(1, searched.size)
            val book = Book(bookUrl = "$origin/book", name = "Old", author = "Old")
            withTimeout(60_000) { WebBook.getBookInfoAwait(selected, book, canReName = true) }
            assertEquals("Title", book.name)
            assertEquals("Author", book.author)
            assertEquals("123字", book.wordCount)
            assertEquals("Fantasy,Adventure", book.kind)
            serving.await()
            assertEquals(listOf("/b?page=2", "/search", "/book"), requests)
            for (unsupported in
                listOf(
                    "$origin/b,{\"method\":\"POST\"}",
                    "$origin/b/{{java.get('x')}}",
                    "$origin/b/<1,2>",
                )) {
                val rejected = runCatching { WebBook.exploreBookAwait(selected, unsupported) }
                assertTrue(
                    rejected.exceptionOrNull()?.message.orEmpty().contains("requires migration")
                )
            }
        }
    }

    @Test
    fun modernBookFieldsRemainUnchangedAndMigratedFieldsUseLegacyFormatting() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        val definition =
            source(
                "function search(){return [{name:'  Title  ',author:'  Author  ',wordCount:'123',kind:'Fantasy\\nAdventure',bookUrl:'https://example.org/book'}]} function getBookInfo(){return {name:'  Title  ',author:'  Author  ',wordCount:'123',kind:'Fantasy\\nAdventure'}}"
            )
        fun selected(json: String) =
            BookSource(bookSourceUrl = "https://example.org/test").apply {
                bookSourceComment = "@source:v1 $json"
            }
        val modern = WebBook.searchBookAwait(selected(definition), "Title").single()
        assertEquals("  Title  ", modern.name)
        assertEquals("  Author  ", modern.author)
        assertEquals("123", modern.wordCount)
        assertEquals("Fantasy\nAdventure", modern.kind)
        val book = Book(bookUrl = "https://example.org/book")
        WebBook.getBookInfoAwait(selected(definition), book)
        assertEquals("  Title  ", book.name)
        assertEquals("  Author  ", book.author)
        val candidate =
            Gson()
                .fromJson(definition, com.google.gson.JsonObject::class.java)
                .apply {
                    add(
                        "metadata",
                        Gson()
                            .toJsonTree(
                                mapOf(
                                    "legacy" to false,
                                    "legacyOriginal" to
                                        mapOf(
                                            "bookSourceUrl" to "https://example.org/test",
                                            "ruleBookInfo" to mapOf("canReName" to "@text"),
                                        ),
                                )
                            ),
                    )
                }
                .toString()
        val migrated =
            WebBook.searchBookAwait(
                    selected(candidate),
                    "Title",
                    filter = { name, author, _ -> name == "Title" && author == "Author" },
                )
                .single()
        assertEquals("123字", migrated.wordCount)
        assertEquals("Fantasy,Adventure", migrated.kind)
        WebBook.getBookInfoAwait(selected(candidate), book)
        assertEquals("Title", book.name)
        assertEquals("Author", book.author)
        val noRename =
            Gson()
                .fromJson(candidate, com.google.gson.JsonObject::class.java)
                .apply {
                    getAsJsonObject("metadata")
                        .getAsJsonObject("legacyOriginal")
                        .getAsJsonObject("ruleBookInfo")
                        .remove("canReName")
                }
                .toString()
        book.name = "Known title"
        book.author = "Known author"
        WebBook.getBookInfoAwait(selected(noRename), book)
        assertEquals("Known title", book.name)
        assertEquals("Known author", book.author)
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
