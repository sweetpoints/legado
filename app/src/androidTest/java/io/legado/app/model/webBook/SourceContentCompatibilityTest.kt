package io.legado.app.model.webBook

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.model.analyzeRule.AnalyzeByXPath
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.SourceEngineSourcePolicy
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceContentCompatibilityTest {
    private val sources
        get() =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("issue1180-content-rules.json")
                .bufferedReader()
                .use {
                    GSON.fromJson(it, Array<BookSource>::class.java)
                }

    private val html =
        """
        <div class="chapter_content">您现在阅读的是<a href="https://www.303wx.com">303文学<a/>www.303wx.com提供的《示例》<br>正文第一段<br>正文第二段<br>【请收藏 303文学 303wx.com】</div>
        """
            .trimIndent()

    @Test
    fun originalTextNodeAndReplacementRulesRetainChapterContent() = runBlocking {
        val original = sources[0]
        assertManualMigration(original)
        val result =
            analyze(
                original,
                html,
                """
                async function getContent(input) {
                    const response = await source.net.get(input.chapterUrl);
                    const text = await source.parse.getString('@legacy:.chapter_content@textNodes', response.body);
                    return text.replace(/【请收藏 303文学 303wx.com】|.*www\.303wx\.com.*|您现在阅读的是/g, '').trim();
                }
                """
                    .trimIndent(),
            )
        assertEquals(
            listOf("正文第一段", "正文第二段"),
            result.lines().map { it.trim() }.filter { it.isNotBlank() },
        )
        val xpath = AnalyzeByXPath(html).getString("//div[@class='chapter_content']/text()")!!
        assertTrue(xpath.contains("正文第一段"))
        assertTrue(xpath.contains("正文第二段"))
        assertFalse(xpath.contains("303文学\n"))
    }

    @Test
    fun originalJavascriptBase64AndBookNameReplacementRulesRetainContent() = runBlocking {
        val encoded =
            Base64.encodeToString(
                "<p>正文第一段</p><p>正文第二段</p><p>喜欢测试书籍</p>".toByteArray(),
                Base64.NO_WRAP,
            )
        val original = sources[1]
        assertManualMigration(original)
        val result =
            analyze(
                original,
                "<script>var content='$encoded';</script>",
                """
                async function getContent(input) {
                    const response = await source.net.get(input.chapterUrl);
                    const encoded = response.body.match(/PHA\+[A-Za-z0-9+\/]+={0,2}/g);
                    if (!encoded) throw new Error('Content decryption requires browser migration');
                    const decoded = await Promise.all(encoded.map(value => source.encoding.base64DecodeWithCharset(value, 'UTF-8')));
                    const text = await source.parse.getString('@legacy:p@text', decoded.join('\n'));
                    return text.replace(new RegExp('本小章还未完.*精彩内容！|(喜欢|请大家收藏.*)' + input.book.name + '|小主.*后面更精彩！|这章没有.*继续阅读！', 'g'), '');
                }
                """
                    .trimIndent(),
            )
        assertTrue(result.contains("正文第一段"))
        assertTrue(result.contains("正文第二段"))
        assertFalse(result.contains("喜欢测试书籍"))
    }

    @Test
    fun directoryDisplayReversalDoesNotChangeSourceRefreshOrderOrChapterIdentity() = runBlocking {
        val baseUrl = "https://toc-reverse.invalid/"
        val source =
            BookSource(
                bookSourceUrl = baseUrl,
                bookSourceName = "TOC compatibility",
                ruleToc =
                    TocRule(
                        chapterList = "tag.a",
                        chapterName = "text",
                        chapterUrl = "href",
                    ),
            )
        val body = (1..5).joinToString("") { "<a href='chapter-$it'>Chapter $it</a>" }
        for (sourceReversed in listOf(false, true)) {
            val book =
                Book(
                        bookUrl = "${baseUrl}book-$sourceReversed",
                        name = "Order fixture",
                        origin = baseUrl,
                    )
                    .apply { setReverseToc(sourceReversed) }
            val server = server(body)
            server.start()
            book.tocUrl = "http://127.0.0.1:${server.listeningPort}/toc"
            suspend fun identities() =
                WebBook.getChapterListAwait(source, book).getOrThrow().map {
                    listOf(it.index, it.url, it.title)
                }
            try {
                val original = identities()
                assertEquals(5, original.size)
                for (displayReversed in listOf(true, false)) {
                    book.setReverseTocDisplay(displayReversed)
                    assertEquals(
                        "Display changes must not reorder a later source refresh",
                        original,
                        identities(),
                    )
                    assertEquals(sourceReversed, book.getReverseToc())
                }
            } finally {
                server.stop()
            }
        }
    }

    private suspend fun assertManualMigration(source: BookSource) {
        val report = DartSourceEngine.migrate(source)
        assertTrue("Original compound rules require explicit review", report.requiresManualWork)
        assertFalse(report.canApply)
        assertTrue(report.issues.any { it.path.startsWith("ruleContent.") })
    }

    private fun server(body: String) =
        object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(body)
        }

    private suspend fun analyze(original: BookSource, body: String, script: String): String {
        val server = server(body)
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.listeningPort}"
            val source =
                original.copy(
                    bookSourceUrl = origin,
                    bookSourceComment =
                        SourceEngineSourcePolicy.withCandidate(
                            original.bookSourceComment,
                            GSON.toJson(
                                mapOf(
                                    "schemaVersion" to 1,
                                    "id" to origin,
                                    "name" to original.bookSourceName,
                                    "baseUrl" to origin,
                                    "script" to script,
                                )
                            ),
                        ),
                )
            val book = Book(bookUrl = "$origin/book", name = "测试书籍", origin = origin)
            val chapter =
                BookChapter(bookUrl = book.bookUrl, url = "$origin/chapter", title = "第一章")
            return WebBook.getContentAwait(
                source,
                book,
                chapter,
                "$origin/next-chapter",
                needSave = false,
            )
        } finally {
            server.stop()
        }
    }
}
