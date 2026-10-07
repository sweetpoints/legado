package io.legado.app.model.sourceEngine

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.annotation.Keep
import io.legado.app.BuildConfig
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Included only by the dedicated release init script; it never registers Flutter plugins itself.
 */
@Keep
class SourceEngineRuntimeInstrumentation : Instrumentation() {
    private var phase = "cold"
    private lateinit var token: String

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase") ?: "cold"
        token = requireNotNull(arguments?.getString("token"))
        require(phase in setOf("cold", "restart"))
        require(token.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        start()
    }

    override fun onStart() {
        super.onStart()
        try {
            waitForIdleSync()
            check(!BuildConfig.DEBUG) { "Release shrinking must be exercised" }
            check(BuildConfig.FLUTTER_SOURCE_ENGINE) { "Actual Flutter/V8 backend is required" }
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            check(targetContext.packageName == "com.legado.app.sourceenginesmoke")
            runBlocking(Dispatchers.IO) {
                withTimeout(120_000) {
                    FixtureServer().use { server ->
                        verifySessionStorage(server)
                        verifyLegacyPipeline(server)
                        check(
                            server.requests.containsAll(
                                listOf("/search", "/book", "/toc", "/chapter/1", "/explore-b")
                            )
                        )
                        if (phase == "restart") check("/session/set" !in server.requests)
                        val proof =
                            JSONObject()
                                .put("phase", phase)
                                .put("debuggable", false)
                                .put("sessionReadWrite", true)
                                .put("closeReopenRestore", true)
                                .put("sessionCookieRestored", true)
                                .put("legacySearchInfoTocContent", true)
                                .put("trailingNewlineExplore", true)
                                .put("requests", org.json.JSONArray(server.requests.toList()))
                        File(
                                targetContext.getExternalFilesDir("source-engine-runtime"),
                                "$phase.json",
                            )
                            .apply {
                                parentFile!!.mkdirs()
                                writeText(proof.toString(2))
                            }
                    }
                }
            }
            finish(
                Activity.RESULT_OK,
                Bundle().apply {
                    putString(
                        "stream",
                        "SOURCE_ENGINE_RELEASE_RUNTIME_PASSED phase=$phase sessionStorage=true restored=true legacyFourStages=true newlineExplore=true\n",
                    )
                },
            )
        } catch (error: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", error.stackTraceToString())
                },
            )
        }
    }

    private fun backend(): SourceEngineBackend =
        Class.forName(
                "io.legado.app.model.sourceEngine.FlutterSourceRepository",
                true,
                targetContext.classLoader,
            )
            .getConstructor(Context::class.java)
            .newInstance(targetContext) as SourceEngineBackend

    private suspend fun verifySessionStorage(server: FixtureServer) {
        val definition =
            GSON.toJson(
                mapOf(
                    "schemaVersion" to 1,
                    "id" to "https://source-engine-runtime.invalid/session/$token",
                    "name" to "Release storage acceptance",
                    "baseUrl" to server.origin,
                    "enabledCookieJar" to true,
                    "script" to
                        """
                        async function search(input) {
                            if (input.write) {
                                await source.variables.put('releaseSaved', 'release-persisted');
                                await source.net.get('/session/set');
                            }
                            return [{name:await source.variables.get('releaseSaved'),author:await source.net.get('/session/check')}];
                        }
                        """
                            .trimIndent(),
                )
            )
        val first = backend()
        try {
            val row =
                first.execute("search", definition, mapOf("write" to (phase == "cold"))).single()
            check(row["name"] == "release-persisted") { "Stored variables were not restored" }
            check(row["author"] == "cookie-restored") { "Stored HTTP cookie was not restored" }
        } finally {
            first.close()
        }
        // A second actual FlutterEngine must read the persisted plugin storage, not a VM cache.
        val reopened = backend()
        try {
            val row = reopened.execute("search", definition, emptyMap()).single()
            check(row["name"] == "release-persisted" && row["author"] == "cookie-restored")
        } finally {
            reopened.close()
        }
    }

    private suspend fun verifyLegacyPipeline(server: FixtureServer) {
        val source =
            BookSource(
                bookSourceUrl = "${server.origin}/source/$token",
                bookSourceName = "Release legacy acceptance",
                searchUrl = "${server.origin}/search?key={{key}}&page={{page}}",
                exploreUrl = "分类A::${server.origin}/explore-a\n分类B::${server.origin}/explore-b\n",
                ruleSearch =
                    SearchRule(
                        bookList = ".book",
                        name = ".name@text",
                        author = ".author@text",
                        bookUrl = "a@href",
                    ),
                ruleExplore =
                    ExploreRule(
                        bookList = ".book",
                        name = ".name@text",
                        author = ".author@text",
                        bookUrl = "a@href",
                    ),
                ruleBookInfo =
                    BookInfoRule(
                        name = ".name@text",
                        author = ".author@text",
                        tocUrl = ".toc@href",
                    ),
                ruleToc =
                    TocRule(
                        chapterList = ".chapter",
                        chapterName = "a@text",
                        chapterUrl = "a@href",
                    ),
                ruleContent = ContentRule(content = ".content@text"),
            )
        // No migration annotation or reviewed modern candidate: exercise ordinary old-format rules.
        check(source.bookSourceComment == null && source.mainJs == null)
        val search = WebBook.searchBookAwait(source, "旧书").single()
        check(search.name == "旧书" && search.bookUrl == "${server.origin}/book")
        val book =
            Book(
                bookUrl = search.bookUrl,
                name = search.name,
                author = search.author,
                origin = source.bookSourceUrl,
            )
        WebBook.getBookInfoAwait(source, book)
        check(book.tocUrl == "${server.origin}/toc")
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        check(chapters.size == 1 && chapters.single().title == "第一章")
        check(chapters.single().url == "${server.origin}/chapter/1")
        val content = WebBook.getContentAwait(source, book, chapters.single(), needSave = false)
        check(content.contains("发行正文验收") && !content.contains("<div"))
        val categories = source.exploreKinds()
        check(categories.map { it.title } == listOf("分类A", "分类B"))
        check(categories.last().url == "${server.origin}/explore-b")
        check(
            WebBook.exploreBookAwait(source, requireNotNull(categories.last().url)).single().name ==
                "旧书B"
        )
    }

    private class FixtureServer : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val origin = "http://127.0.0.1:${server.localPort}"

        init {
            worker.submit {
                try {
                    while (!server.isClosed) server.accept().use { client ->
                        client.soTimeout = 20_000
                        val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val path = reader.readLine().split(' ')[1].substringBefore('?')
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] =
                                line.substringAfter(':').trim()
                        }
                        requests.add(path)
                        val body =
                            when (path) {
                                "/session/set" -> "cookie-set"
                                "/session/check" ->
                                    if (
                                        headers["cookie"]
                                            .orEmpty()
                                            .contains("sdk_session=release-cookie")
                                    )
                                        "cookie-restored"
                                    else "cookie-missing"
                                "/search",
                                "/explore-a",
                                "/explore-b" ->
                                    "<div class='book'><a class='name' href='/book'>${if (path == "/explore-b") "旧书B" else "旧书"}</a><span class='author'>作者</span></div>"
                                "/book" ->
                                    "<h1 class='name'>旧书</h1><p class='author'>作者</p><a class='toc' href='/toc'>目录</a>"
                                "/toc" -> "<div class='chapter'><a href='/chapter/1'>第一章</a></div>"
                                "/chapter/1" -> "<div class='content'>发行正文验收</div>"
                                else -> error("Unexpected fixture request: $path")
                            }.toByteArray(Charsets.UTF_8)
                        val cookie =
                            if (path == "/session/set")
                                "Set-Cookie: sdk_session=release-cookie; Path=/; Max-Age=3600; HttpOnly\r\n"
                            else ""
                        client.getOutputStream().apply {
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n$cookie"
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            write(
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            write(body)
                            flush()
                        }
                    }
                } catch (error: SocketException) {
                    if (!server.isClosed) throw error
                }
            }
        }

        override fun close() {
            server.close()
            worker.shutdownNow()
        }
    }
}
