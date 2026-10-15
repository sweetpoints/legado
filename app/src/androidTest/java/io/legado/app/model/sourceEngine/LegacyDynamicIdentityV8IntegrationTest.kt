package io.legado.app.model.sourceEngine

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.BookDetailSource
import io.legado.app.data.repository.EngineBookDetailNetworkRepository
import io.legado.app.data.repository.FileBookDetailSessionRepository
import io.legado.app.data.repository.RoomBookDetailNetworkStorageRepository
import io.legado.app.data.repository.RoomBookDetailRepository
import io.legado.app.data.repository.RoomBookDetailStorageRepository
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.info.detail.BookDetailViewModel
import io.legado.app.utils.GSON
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The real Android request and rule bridges accept label IDs with dynamic search URLs. */
@RunWith(AndroidJUnit4::class)
class LegacyDynamicIdentityV8IntegrationTest {
    @Test
    fun objectDetailInitializationCarriesDynamicHostIntoSeparateTocCall(): Unit =
        runBlocking(Dispatchers.IO) {
            val requests = CopyOnWriteArrayList<String>()
            val server = object : NanoHTTPD("127.0.0.1", 0) {
                val base get() = "http://127.0.0.1:$listeningPort"
                override fun serve(session: IHTTPSession): Response {
                    requests.add(session.uri)
                    val body = when (session.uri) {
                        "/bootstrap" -> "<script>url[0]=\"$base\";\nurl[1]=\"$base\";\nurl[2]=\"$base\";</script>"
                        "/book-123.html" -> """<div class="book_name"><a>Fixture Book</a></div>
                            <div class="author">Fixture Author</div>
                            <div class="xinxi_content"><span>分类 已完结 100字</span></div>
                            <div class="dt_r">更新时间</div>
                            <div class="new_zhangjie1"><div class="dt_l"><a>Latest</a></div><div class="dt_r">Today</div></div>
                            <div class="jianjieneirong">Fixture Intro</div>
                            <div class="book_info_top_l"><img src="cover.jpg"></div>"""
                        "/chapterList-123.html" -> """<ul class="section_list">
                            <li><a href="read-1.html">正文 Chapter 1</a></li>
                            <li><a href="read-2.html">VIP章节 Chapter 2</a></li></ul>"""
                        else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown fixture")
                    }
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
                }
            }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val base = "http://127.0.0.1:${server.listeningPort}"
            val json = InstrumentationRegistry.getInstrumentation().context.assets
                .open("source-fixtures/fengqing-detail-toc.json").bufferedReader().use { it.readText() }
            val source = GSON.fromJson(json, BookSource::class.java).apply {
                bookSourceComment = bookSourceComment!!.replace(
                    "https://myaavideonet.neocities.org/aabook.html", "$base/bootstrap")
            }
            appDb.bookSourceDao.insert(source)
            try {
                val book = Book(bookUrl = "$base/book-123.html", origin = source.bookSourceUrl, name = "Search Book")
                try { withTimeout(45_000) {
                    WebBook.getBookInfoAwait(source, book)
                    assertEquals("$base/chapterList-123.html", book.tocUrl)
                    assertEquals("Fixture Author", book.author)
                    val parser = AnalyzeRule(book, source).setCoroutineContext(currentCoroutineContext())
                        .setContent("<ul class='section_list'><li><a href='read-1.html'>正文 Chapter 1</a></li></ul>", book.tocUrl)
                    val row = parser.getElements(source.getTocRule().chapterList!!).single()
                    parser.setContent(row, book.tocUrl)
                    assertEquals("$base/read-1.html", parser.getString(source.getTocRule().chapterUrl))
                    val chapters = EngineBookDetailNetworkRepository().toc(
                        BookDetailBook.from(book), BookDetailSource.from(source), true, true,
                    ).chapters
                    assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })
                    assertEquals(listOf("$base/read-1.html", "$base/read-2.html"), chapters.map { it.url })
                } } catch (error: Throwable) {
                    throw AssertionError("Controlled request trace: $requests; TOC=${book.tocUrl}", error)
                }
                assertEquals(2, requests.count { it == "/chapterList-123.html" })
                val search = SearchBook(bookUrl = book.bookUrl, origin = source.bookSourceUrl, name = "Search Book")
                appDb.searchBookDao.insert(search)
                val details = RoomBookDetailRepository()
                val sessions = FileBookDetailSessionRepository(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    RoomBookDetailStorageRepository(), details, RoomBookDetailNetworkStorageRepository(),
                )
                val model = withContext(Dispatchers.Main) {
                    BookDetailViewModel(SavedStateHandle(), details, sessions,
                        EngineBookDetailNetworkRepository(), BookDetailIdentity("Search Book", "", book.bookUrl))
                }
                try {
                    val loaded = withTimeout(20_000) {
                        model.state.first { it.loaded && !it.networkLoading &&
                            (it.data?.chapters?.isNotEmpty() == true || it.error != null) }
                    }
                    assertNull(loaded.error)
                    assertEquals(listOf("Chapter 1", "Chapter 2"), loaded.data!!.chapters.map { it.title })
                } catch (error: Throwable) {
                    val state = model.state.value
                    throw AssertionError("Detail state: loaded=${state.loaded}, loading=${state.loading}, network=${state.networkLoading}, busy=${state.busy}, error=${state.error}, chapters=${state.data?.chapters?.size}; requests=$requests", error)
                } finally {
                    withContext(Dispatchers.Main) { model.close() }
                    appDb.searchBookDao.delete(search)
                }
            } finally {
                DartSourceEngine.clearSourceState(source)
                appDb.bookSourceDao.delete(source)
                server.stop()
            }
        }

    @Test
    fun categoryClickUsesItsAbsoluteUrlAndKeepsTheOpaqueSourceId(): Unit =
        runBlocking(Dispatchers.IO) {
            val requests = CopyOnWriteArrayList<String>()
            val server = object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response {
                    requests.add(session.uri + "?page=" + session.parameters["page"]?.single())
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        "<article><h2>Category fixture</h2><a href='/book'>Book</a></article>",
                    )
                }
            }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val base = "http://127.0.0.1:${server.listeningPort}"
            val source = BookSource(
                bookSourceUrl = "歪歪斜斜的字眼里，满篇都是吃人二字-----鲁迅",
                bookSourceName = "Dynamic identity fixture",
                searchUrl = "<js>host + '/search?key={{key}}';</js>",
                ruleExplore = ExploreRule(
                    bookList = "tag.article", name = "tag.h2@text", bookUrl = "tag.a@href",
                ),
            )
            try {
                val records = withTimeout(30_000) {
                    DartSourceEngine.execute(source, "explore", mapOf(
                        "exploreUrl" to "$base/category?page={{page}}", "page" to 2,
                    ))
                }
                assertEquals(1, records.size)
                assertEquals("Category fixture", records.single()["name"])
                assertEquals("$base/book", records.single()["bookUrl"])
                assertEquals(listOf("/category?page=2"), requests)
                assertEquals("歪歪斜斜的字眼里，满篇都是吃人二字-----鲁迅", source.bookSourceUrl)
            } finally {
                DartSourceEngine.clearSourceState(source)
                server.stop()
            }
        }
}
