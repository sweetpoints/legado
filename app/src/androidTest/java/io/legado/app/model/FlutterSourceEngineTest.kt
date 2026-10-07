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
import io.legado.app.model.sourceEngine.SourceScriptException
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
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

    @Test
    fun auxiliaryScriptsAndEphemeralJsConfigurationRunActualV8() = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val definition =
            BookSource(
                bookSourceUrl = "https://v8-auxiliary.invalid/",
                bookSourceName = "Auxiliary",
            )
        val value =
            DartSourceEngine.evaluate(
                definition,
                "(async()=>{await Promise.resolve(); infoMap.title='changed'; return {value:java.base64Encode('V8'),infoMap};})()",
                mapOf("infoMap" to mapOf("title" to "original")),
            ) as Map<*, *>
        assertEquals("Vjg=", value["value"])
        assertEquals("changed", (value["infoMap"] as Map<*, *>)["title"])
        val modern =
            definition.copy(
                jsLib = "legacyJsLibMustNotBeEvaluated()",
                bookSourceComment =
                    "@source:v1 " +
                        """{"schemaVersion":1,"id":"modern-auxiliary","name":"Modern","baseUrl":"https://modern-auxiliary.invalid/","stages":{}}""",
            )
        assertEquals("undefined", DartSourceEngine.evaluate(modern, "typeof java"))
        val script =
            """
            const config = {bookSourceUrl:'https://v8-config.invalid/',bookSourceName:'V8 config'};
            const search = (key,page) => [{name:key+page,author:'V8',bookUrl:'https://v8-config.invalid/book'}];
            function getChapters(book) { return [{title:'Chapter',url:'https://v8-config.invalid/chapter'}]; }
            function getContent(chapter,book,nextChapterUrl) { return chapter.index+':'+book.name; }
            """
                .trimIndent()
        val imported =
            withContext(Dispatchers.IO) {
                io.legado.app.model.jsSource.JsSourceConfig.extract(script)
            }
        assertEquals("V8 config", imported.bookSourceName)
        assertEquals(script, imported.mainJs)
        assertEquals("Actual2", WebBook.searchBookAwait(imported, "Actual", 2).single().name)
        val book =
            Book(
                bookUrl = "https://v8-config.invalid/book",
                name = "Actual2",
                origin = imported.bookSourceUrl,
            )
        val chapter =
            io.legado.app.data.entities.BookChapter(
                bookUrl = book.bookUrl,
                title = "Chapter",
                url = "https://v8-config.invalid/chapter",
                index = 7,
            )
        assertEquals(
            "7:Actual2",
            WebBook.getContentAwait(imported, book, chapter, needSave = false),
        )
    }

    @Test
    fun preUpdateScriptsApplyValidatedMetadataWithActualV8() = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        val definition =
            BookSource(
                bookSourceUrl = "https://v8-preupdate.invalid/",
                bookSourceName = "Pre-update",
                ruleToc =
                    io.legado.app.data.entities.rule.TocRule(
                        preUpdateJs =
                            "await Promise.resolve(); book.name='Updated'; book.tocUrl='https://v8-preupdate.invalid/new-toc';"
                    ),
            )
        val book =
            Book(
                bookUrl = "https://v8-preupdate.invalid/book",
                name = "Original",
                origin = definition.bookSourceUrl,
            )
        WebBook.runPreUpdateJs(definition, book).getOrThrow()
        assertEquals("Updated", book.name)
        assertEquals("https://v8-preupdate.invalid/new-toc", book.tocUrl)
        definition.ruleToc!!.preUpdateJs = "book.name='Must not apply'; book.totalChapterNum=100;"
        assertTrue(WebBook.runPreUpdateJs(definition, book).isFailure)
        assertEquals("Updated", book.name)
        assertEquals(0, book.totalChapterNum)
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

    private fun legacySearchSource(origin: String, nameRule: String = "a@text"): BookSource =
        Gson()
            .fromJson(
                Gson()
                    .toJson(
                        mapOf(
                            "bookSourceUrl" to origin,
                            "bookSourceName" to "Unmarked legacy source",
                            "bookSourceComment" to "ordinary comment preserved",
                            "enabledCookieJar" to true,
                            "searchUrl" to "$origin/search?q={{key}}",
                            "ruleSearch" to
                                mapOf(
                                    "bookList" to ".row",
                                    "name" to nameRule,
                                    "author" to ".author@text",
                                    "bookUrl" to "a@href",
                                ),
                        )
                    ),
                BookSource::class.java,
            )

    @Test
    fun legacyLiteralReplacementAndFinalHtml4DecodeRunThroughApp() = runBlocking {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val origin = "http://127.0.0.1:${server.localPort}"
            val original = legacySearchSource(origin, "a@text##旧##新")
            val snapshot = Gson().toJson(original)
            val bridge = backend()
            try {
                val preview = withTimeout(60_000) { bridge.migrate(snapshot) }
                assertTrue(preview.canApply)
                assertTrue(preview.issues.isEmpty())
                assertEquals("unverified", preview.status)
            } finally {
                bridge.close()
            }
            val serving =
                async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        val body =
                            "<div class='row'><a href='/book'>旧&amp;amp;正文</a><span class='author'>Author</span></div>"
                                .toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                            write(body)
                            flush()
                        }
                        request
                    }
                }
            val book = withTimeout(60_000) { WebBook.searchBookAwait(original, "key", 1).single() }
            assertEquals("新&正文", book.name)
            assertEquals("$origin/book", book.bookUrl)
            assertEquals("GET /search?q=key HTTP/1.1", withTimeout(10_000) { serving.await() })
            assertEquals(snapshot, Gson().toJson(original))
        }
    }

    @Test
    fun mixedMainJsAppHooksRequireManualMigrationThroughActualHost() = runBlocking {
        val bridge = backend()
        try {
            for (hook in listOf("imageStyle", "imageDecode", "payAction", "callBackJs")) {
                val snapshot =
                    Gson()
                        .toJson(
                            mapOf(
                                "bookSourceUrl" to "https://mixed-hooks.invalid/$hook",
                                "bookSourceName" to "Mixed hook",
                                "enabledCookieJar" to true,
                                "mainJs" to "function search(){return [];}",
                                "ruleContent" to mapOf(hook to "old-hook"),
                            )
                        )
                val preview = withTimeout(60_000) { bridge.migrate(snapshot) }
                assertTrue(preview.requiresManualWork)
                assertTrue(!preview.canApply)
                assertEquals("manualRequired", preview.status)
                val candidate =
                    Gson().fromJson(preview.candidateJson, com.google.gson.JsonObject::class.java)
                assertEquals(
                    Gson().fromJson(snapshot, com.google.gson.JsonObject::class.java),
                    candidate.getAsJsonObject("metadata").getAsJsonObject("legacyOriginal"),
                )
            }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun migrationChannelProducesApplicableUnverifiedPreviewWithoutMutatingSource() = runBlocking {
        val bridge = backend()
        try {
            val original = legacySearchSource("https://example.org/migration")
            val snapshot = Gson().toJson(original)
            val preview = withTimeout(60_000) { bridge.migrate(snapshot) }
            assertTrue(preview.canApply)
            assertTrue(!preview.requiresManualWork)
            assertTrue(preview.issues.isEmpty())
            assertEquals("unverified", preview.status)
            val candidate =
                Gson().fromJson(preview.candidateJson, com.google.gson.JsonObject::class.java)
            assertEquals(1, candidate.get("schemaVersion").asInt)
            assertEquals(original.bookSourceUrl, candidate.get("id").asString)
            assertEquals(
                Gson().fromJson(snapshot, com.google.gson.JsonObject::class.java),
                candidate.getAsJsonObject("metadata").getAsJsonObject("legacyOriginal"),
            )
            assertEquals(snapshot, Gson().toJson(original))
            assertTrue(bridge.tasks.value.isEmpty())
        } finally {
            bridge.close()
        }
    }

    @Test
    fun migrationChannelBlocksManualPreviewAndRejectsAfterShutdownWithoutMutation() = runBlocking {
        val bridge = backend()
        try {
            val original =
                legacySearchSource("https://example.org/manual", "@js:java.unknownApi(key)")
            val snapshot = Gson().toJson(original)
            val preview = withTimeout(60_000) { bridge.migrate(snapshot) }
            assertTrue(!preview.canApply)
            assertTrue(preview.requiresManualWork)
            assertTrue(preview.issues.isNotEmpty())
            assertEquals("manualRequired", preview.status)
            assertTrue(preview.candidateJson != null)
            assertEquals(snapshot, Gson().toJson(original))
            val candidateSnapshot = preview.candidateJson
            bridge.close()
            val failure = withTimeout(5_000) { runCatching { bridge.migrate(snapshot) } }
            assertTrue(failure.isFailure)
            assertTrue(
                failure.exceptionOrNull()?.message.orEmpty().contains("repository is closed")
            )
            assertEquals(snapshot, Gson().toJson(original))
            assertEquals(candidateSnapshot, preview.candidateJson)
            assertTrue(!preview.canApply)
            assertTrue(bridge.tasks.value.isEmpty())
        } finally {
            bridge.close()
        }
    }

    @Test
    fun unmarkedLegacySourcesUseDartV8ByDefault() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val origin = "http://127.0.0.1:${server.localPort}"
            val serving =
                async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        val body =
                            "<div class='row'><a href='/book'>Fixture title</a><span class='author'>Author</span></div>"
                                .toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                            write(body)
                            flush()
                        }
                        request
                    }
                }
            // V8 stack capture identifies the actual engine, while java.* exercises the
            // compatibility host of a plain old source with no engine selection marker.
            val original =
                legacySearchSource(
                    origin,
                    "@js:java.base64Encode('Default V8')",
                )
            val snapshot = Gson().toJson(original)
            assertTrue(!original.bookSourceComment.orEmpty().contains("@engine:dart"))
            assertTrue(!original.bookSourceComment.orEmpty().contains("@source:v1"))
            val rows = withTimeout(60_000) { WebBook.searchBookAwait(original, "key", 1) }
            assertEquals(1, rows.size)
            assertEquals("RGVmYXVsdCBWOA==", rows.single().name)
            assertEquals(
                "function",
                DartSourceEngine.evaluate(original, "typeof Error.captureStackTrace"),
            )
            assertEquals("Author", rows.single().author)
            assertEquals("$origin/book", rows.single().bookUrl)
            assertEquals(origin, rows.single().origin)
            assertEquals("GET /search?q=key HTTP/1.1", withTimeout(10_000) { serving.await() })
            assertEquals(snapshot, Gson().toJson(original))
        }
    }

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
            val error = oldApi.exceptionOrNull()
            assertTrue(
                "Modern source must report a structured script failure",
                error is SourceScriptException,
            )
            assertEquals("script_error", (error as SourceScriptException).code)
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
                    repeat(6) {
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
            // These legacy URL capabilities are now executed by the Native adapter.
            // The @js rule must return a URL, rather than an ajax HTML response.
            for (category in listOf(
                "$origin/b/{{java.get('x')}}",
                "$origin/b,${Gson().toJson(mapOf("webJs" to "document.documentElement.outerHTML"))}",
                "@js:'$origin/script?page=' + page",
            )) {
                val books = withTimeout(60_000) { WebBook.exploreBookAwait(selected, category, 2) }
                assertEquals("Title", books.single().name)
                assertEquals("Author", books.single().author)
                assertEquals("123字", books.single().wordCount)
                assertEquals("Fantasy,Adventure", books.single().kind)
            }
            serving.await()
            assertEquals(listOf("/b?page=2", "/search", "/book", "/b/", "/b", "/script?page=2"), requests)
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
    fun nonUrlLegacySourceIdsSearchAbsoluteEndpointAndKeepSessionsIsolated() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        val unique = java.util.UUID.randomUUID().toString()
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val origin = "http://127.0.0.1:${server.localPort}"
            val paths = mutableListOf<String>()
            val cookies = mutableListOf<String>()
            val serving =
                async(Dispatchers.IO) {
                    repeat(3) { index ->
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader()
                            paths.add(reader.readLine().split(' ')[1])
                            var cookie = ""
                            while (true) {
                                val header = reader.readLine()
                                if (header.isNullOrEmpty()) break
                                if (header.startsWith("Cookie:", ignoreCase = true))
                                    cookie = header.substringAfter(':').trim()
                            }
                            cookies.add(cookie)
                            val owner = if (index == 1) "B" else "A"
                            val body =
                                "<div class='row'><a href='book'>Title $owner</a><span class='author'>Author</span></div>"
                                    .toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nSet-Cookie: owner=$owner; Path=/; HttpOnly\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            fun selected(id: String) =
                Gson()
                    .fromJson(
                        Gson()
                            .toJson(
                                mapOf(
                                    "bookSourceUrl" to id,
                                    "bookSourceName" to id,
                                    "bookSourceComment" to "@engine:dart",
                                    "enabledCookieJar" to true,
                                    "searchUrl" to "$origin/catalog/search",
                                    "ruleSearch" to
                                        mapOf(
                                            "bookList" to ".row",
                                            "name" to "a@text",
                                            "author" to ".author@text",
                                            "bookUrl" to "a@href",
                                        ),
                                )
                            ),
                        BookSource::class.java,
                    )
            val first = selected("Local archive $unique A")
            val second = selected("Local archive $unique B")
            for ((source, title) in
                listOf(first to "Title A", second to "Title B", first to "Title A")) {
                val result =
                    withTimeout(60_000) { WebBook.searchBookAwait(source, "Title").single() }
                assertEquals(title, result.name)
                assertEquals("$origin/catalog/book", result.bookUrl)
                assertEquals(source.bookSourceUrl, result.origin)
            }
            serving.await()
            assertEquals(listOf("/catalog/search", "/catalog/search", "/catalog/search"), paths)
            assertEquals(listOf("", "", "owner=A"), cookies)
        }
    }

    @Test
    fun nativeLegacyPostWireAndExplicitModernJsonTemplateKeepTheirOwnContracts() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val origin = "http://127.0.0.1:${server.localPort}"
            val requests = mutableListOf<Triple<String, String, String>>()
            val serving =
                async(Dispatchers.IO) {
                    repeat(5) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = socket.getInputStream()
                            val headerBytes = java.io.ByteArrayOutputStream()
                            var window = 0
                            while (true) {
                                val byte = input.read()
                                check(byte >= 0) { "HTTP request headers ended early" }
                                headerBytes.write(byte)
                                window = (window shl 8) or byte
                                if (window == 0x0d0a0d0a) break
                            }
                            val lines = headerBytes.toString("US-ASCII").split("\r\n")
                            val headers =
                                lines
                                    .drop(1)
                                    .filter { it.contains(':') }
                                    .associate {
                                        it.substringBefore(':').lowercase() to
                                            it.substringAfter(':').trim()
                                    }
                            fun readExactly(size: Int): ByteArray {
                                require(size in 0..1_048_576) { "Unexpected fixture body size" }
                                val bytes = ByteArray(size)
                                var offset = 0
                                while (offset < size) {
                                    val count = input.read(bytes, offset, size - offset)
                                    check(count > 0) { "HTTP body ended early" }
                                    offset += count
                                }
                                return bytes
                            }
                            fun readFramingLine(): String {
                                val bytes = java.io.ByteArrayOutputStream()
                                while (true) {
                                    val byte = input.read()
                                    check(byte >= 0) { "HTTP chunk framing ended early" }
                                    if (byte == 13) {
                                        check(input.read() == 10) { "Expected framing CRLF" }
                                        return bytes.toString("US-ASCII")
                                    }
                                    check(bytes.size() < 8192) {
                                        "Unexpected fixture framing line size"
                                    }
                                    bytes.write(byte)
                                }
                            }
                            val chunked =
                                headers["transfer-encoding"].orEmpty().split(',').any {
                                    it.trim().equals("chunked", ignoreCase = true)
                                }
                            val body =
                                if (chunked) {
                                    val decoded = java.io.ByteArrayOutputStream()
                                    while (true) {
                                        val size =
                                            readFramingLine().substringBefore(';').trim().toInt(16)
                                        if (size == 0) {
                                            while (readFramingLine().isNotEmpty()) {}
                                            break
                                        }
                                        decoded.write(readExactly(size))
                                        check(readFramingLine().isEmpty()) {
                                            "Expected chunk-ending CRLF"
                                        }
                                    }
                                    decoded.toByteArray()
                                } else {
                                    readExactly(headers["content-length"]?.toInt() ?: 0)
                                }
                            requests.add(
                                Triple(
                                    lines.first(),
                                    headers["content-type"].orEmpty(),
                                    body.toString(Charsets.UTF_8),
                                )
                            )
                            val response =
                                "<div class='row'><a href='/book'>Title</a><span class='author'>Author</span></div>"
                                    .toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                write(response)
                                flush()
                            }
                        }
                    }
                }
            val fields =
                mapOf("name" to "a@text", "author" to ".author@text", "bookUrl" to "a@href")
            val options =
                Gson().toJson(mapOf("method" to "POST", "body" to "q={{key}}&page={{page}}"))
            val legacy =
                Gson()
                    .fromJson(
                        Gson()
                            .toJson(
                                mapOf(
                                    "bookSourceUrl" to origin,
                                    "bookSourceName" to "Legacy POST",
                                    "bookSourceComment" to "@engine:dart",
                                    "enabledCookieJar" to true,
                                    "searchUrl" to "$origin/legacy,$options",
                                    "ruleSearch" to (fields + mapOf("bookList" to ".row")),
                                )
                            ),
                        BookSource::class.java,
                    )
            val key = "中文 空格+%"
            val result =
                withTimeout(60_000) {
                    WebBook.searchBookAwait(
                        legacy,
                        key,
                        3,
                        filter = { name, author, _ -> name == "Title" && author == "Author" },
                    )
                }
            assertEquals(1, result.size)
            val legacyEdgeGoldens = listOf("quote\"", "slash\\", "line\ncontrol").map { edgeKey ->
                val golden = withContext(Dispatchers.IO) {
                    AnalyzeUrl(legacy.searchUrl!!, key = edgeKey, page = 1, source = legacy, baseUrl = origin)
                        .resolveRequestDescriptor(includeCookies = false)
                }
                assertEquals("Title", withTimeout(60_000) {
                    WebBook.searchBookAwait(legacy, edgeKey, filter = { name, author, _ -> name == "Title" && author == "Author" }).single().name
                })
                golden
            }
            // Input rejection belongs to this explicit portable JSON-template contract,
            // not to the Native legacy URL parser's historical lenient/default-GET behavior.
            val guardedDefinition = Gson().toJson(mapOf(
                "schemaVersion" to 1, "id" to "$origin/guard", "name" to "Explicit JSON guard", "baseUrl" to origin,
                "stages" to mapOf("search" to mapOf(
                    "url" to "$origin/guard", "method" to "POST", "body" to "q={{key}}&page={{page}}",
                    "bodyTemplateMode" to "legacyJsonString", "bodyEncoding" to "legacyFormUtf8",
                    "list" to "@css:.row", "fields" to fields.mapValues { "@css:${it.value}" },
                )),
            ))
            val guarded = BookSource(bookSourceUrl = "$origin/guard").apply { bookSourceComment = "@source:v1 $guardedDefinition" }
            for (unsafe in listOf("quote\"", "slash\\", "line\ncontrol")) {
                val failure = withTimeout(60_000) { runCatching { WebBook.searchBookAwait(guarded, unsafe) } }
                assertTrue(failure.isFailure)
                assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("legacy_body_template_requires_migration"))
            }
            val rawBody = "q=$key&page=3"
            val definition =
                Gson()
                    .toJson(
                        mapOf(
                            "schemaVersion" to 1,
                            "id" to "$origin/modern-post",
                            "name" to "Modern raw POST",
                            "baseUrl" to origin,
                            "stages" to
                                mapOf(
                                    "search" to
                                        mapOf(
                                            "url" to "$origin/modern",
                                            "method" to "POST",
                                            "body" to rawBody,
                                            "headers" to
                                                mapOf(
                                                    "Content-Type" to
                                                        "application/x-www-form-urlencoded"
                                                ),
                                            "list" to "@css:.row",
                                            "fields" to fields.mapValues { "@css:${it.value}" },
                                        )
                                ),
                        )
                    )
            val modern =
                BookSource(bookSourceUrl = "$origin/modern-post").apply {
                    bookSourceComment = "@source:v1 $definition"
                }
            assertEquals(
                "Title",
                withTimeout(60_000) { WebBook.searchBookAwait(modern, "ignored").single().name },
            )
            serving.await()
            assertEquals(5, requests.size)
            assertEquals("POST /legacy HTTP/1.1", requests[0].first)
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", requests[0].second)
            assertEquals("q=${java.net.URLEncoder.encode(key, "UTF-8")}&page=3", requests[0].third)
            legacyEdgeGoldens.forEachIndexed { index, golden ->
                val actual = requests[index + 1]
                assertEquals("${golden["method"]} /legacy HTTP/1.1", actual.first)
                assertEquals(golden["contentType"]?.toString().orEmpty(), actual.second)
                val expectedBytes = (golden["bodyBytes"] as? List<*>)?.map { (it as Number).toByte() }?.toByteArray() ?: byteArrayOf()
                assertEquals(expectedBytes.toString(Charsets.UTF_8), actual.third)
            }
            assertEquals("POST /modern HTTP/1.1", requests[4].first)
            assertEquals("application/x-www-form-urlencoded", requests[4].second)
            assertEquals(rawBody, requests[4].third)
            assertTrue(requests.none { it.first.contains("/guard ") })
        }
    }

    @Test
    fun legacyExploreOptionsAndFinitePaginationUseSelectedRequest() = runBlocking {
        assumeTrue("Requires -PflutterSourceEngine=true", BuildConfig.FLUTTER_SOURCE_ENGINE)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val origin = "http://127.0.0.1:${server.localPort}"
            val requests = mutableListOf<Map<String, String>>()
            val serving =
                async(Dispatchers.IO) {
                    repeat(4) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            // This fixture's request body is ASCII p=1, so character counts equal
                            // byte counts.
                            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val request = reader.readLine()
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine()
                                if (line.isNullOrEmpty()) break
                                headers[line.substringBefore(':').lowercase()] =
                                    line.substringAfter(':').trim()
                            }
                            fun readCharacters(size: Int): String {
                                val chars = CharArray(size)
                                var offset = 0
                                while (offset < size) {
                                    val count = reader.read(chars, offset, size - offset)
                                    check(count > 0)
                                    offset += count
                                }
                                return String(chars)
                            }
                            val body =
                                if (
                                    headers["transfer-encoding"]
                                        .orEmpty()
                                        .contains("chunked", ignoreCase = true)
                                ) {
                                    buildString {
                                        while (true) {
                                            val size =
                                                reader
                                                    .readLine()
                                                    .substringBefore(';')
                                                    .trim()
                                                    .toInt(16)
                                            if (size == 0) {
                                                while (!reader.readLine().isNullOrEmpty()) {}
                                                break
                                            }
                                            append(readCharacters(size))
                                            check(reader.readLine().isEmpty())
                                        }
                                    }
                                } else readCharacters(headers["content-length"]?.toInt() ?: 0)
                            requests.add(headers + mapOf("request" to request, "body" to body))
                            val response =
                                "<div class='row'><a href='/book'>Title</a><span class='author'>Author</span></div>"
                                    .toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                write(response)
                                flush()
                            }
                        }
                    }
                }
            val categoryA = "$origin/<first,other>"
            val categoryB =
                "$origin/<first,other>,${Gson().toJson(mapOf("method" to "POST", "body" to "p={{page - 1}}", "headers" to mapOf("X-Shared" to "selected", "X-Category" to "B")))}"
            val fields =
                mapOf(
                    "bookList" to ".row",
                    "name" to "a@text",
                    "author" to ".author@text",
                    "bookUrl" to "a@href",
                )
            val selected =
                Gson()
                    .fromJson(
                        Gson()
                            .toJson(
                                mapOf(
                                    "bookSourceUrl" to origin,
                                    "bookSourceName" to "Finite pages",
                                    "bookSourceComment" to "@engine:dart",
                                    "enabledCookieJar" to true,
                                    "header" to
                                        Gson()
                                            .toJson(
                                                mapOf(
                                                    "X-Default" to "source",
                                                    "X-Shared" to "default",
                                                )
                                            ),
                                    "exploreUrl" to "A::$categoryA\nB::$categoryB",
                                    "ruleExplore" to fields,
                                    "searchUrl" to "$origin/search?page={{page + 1}}",
                                    "ruleSearch" to fields,
                                )
                            ),
                        BookSource::class.java,
                    )
            assertEquals(
                "Title",
                withTimeout(60_000) {
                    WebBook.exploreBookAwait(selected, categoryA, 1).single().name
                },
            )
            assertEquals(
                "Title",
                withTimeout(60_000) {
                    WebBook.exploreBookAwait(selected, categoryB, 2).single().name
                },
            )
            assertEquals(
                "Title",
                withTimeout(60_000) { WebBook.searchBookAwait(selected, "Title", 2).single().name },
            )
            assertEquals("Title", withTimeout(60_000) {
                WebBook.exploreBookAwait(selected, "$origin/{{java.get('page')}}", 2).single().name
            })
            assertEquals(
                listOf(
                    "GET /first HTTP/1.1",
                    "POST /other HTTP/1.1",
                    "GET /search?page=3 HTTP/1.1",
                    "GET /2 HTTP/1.1",
                ),
                requests.map { it["request"] },
            )
            assertEquals("", requests[0]["body"])
            assertEquals("default", requests[0]["x-shared"])
            assertEquals("source", requests[1]["x-default"])
            assertEquals("selected", requests[1]["x-shared"])
            assertEquals("B", requests[1]["x-category"])
            assertEquals("p=1", requests[1]["body"])
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", requests[1]["content-type"])
            serving.await()
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
