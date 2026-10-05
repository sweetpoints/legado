package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.model.sourceEngine.DartSourceEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBookTest {

    private val source = BookSource(
        bookSourceUrl = "https://source.example",
        bookSourceName = "Test source",
    )
    private val book = Book(
        bookUrl = "https://source.example/book/1",
        name = "Book",
    )

    @Test
    fun `volume placeholder stays empty when the content rule is absent`() = runBlocking {
        val content = WebBook.getContentAwait(
            bookSource = source,
            book = book,
            bookChapter = BookChapter(
                url = "Volume 1#0",
                title = "Volume 1",
                isVolume = true,
                tag = "2026-08-10",
            ),
            needSave = false,
        )

        assertEquals("", content)
    }

    @Test
    fun `ordinary chapter still uses its url when the content rule is absent`() = runBlocking {
        val chapterUrl = "https://source.example/chapter/1"

        val content = WebBook.getContentAwait(
            bookSource = source,
            book = book,
            bookChapter = BookChapter(
                url = chapterUrl,
                title = "Chapter 1",
            ),
            needSave = false,
        )

        assertEquals(chapterUrl, content)
    }
    @Test
    fun `empty pre update hook needs no script backend`() = runBlocking {
        assertTrue(WebBook.runPreUpdateJs(source, book).isSuccess)
    }

    @Test
    fun `batch without a script returns chapters for Dart single chapter fallback`() = runBlocking {
        val chapters = listOf(BookChapter(url = "https://source.example/1", title = "One"))
        assertEquals(chapters, WebBook.getContentBatchAwait(source, book, chapters))
        val withBatch = source.copy(ruleContent = ContentRule(contentBatch = "java.cacheContent('1','text')"))
        assertEquals(chapters, WebBook.getContentBatchAwait(withBatch, book, chapters))
    }

    @Test
    fun `pre update JSON metadata changes apply only after complete validation`() {
        val target = book.copy()
        val before = DartSourceEngine.jsonObject(target)
        WebBook.applyDartPreUpdatePatch(target, before, before + mapOf(
            "tocUrl" to "https://source.example/new-toc", "name" to "New", "intro" to "Intro",
        ))
        assertEquals("https://source.example/new-toc", target.tocUrl)
        assertEquals("New", target.name)
        assertEquals("Intro", target.intro)
        val after = DartSourceEngine.jsonObject(target)
        WebBook.applyDartPreUpdatePatch(target, after, after + mapOf("intro" to null))
        assertEquals(null, target.intro)
    }

    @Test
    fun `unsupported pre update mutation never applies a partial metadata patch`() {
        val target = book.copy()
        val before = DartSourceEngine.jsonObject(target)
        val result = runCatching { WebBook.applyDartPreUpdatePatch(target, before,
            before + mapOf("name" to "Partial", "durChapterIndex" to 42)) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("requires migration"))
        assertEquals(book.name, target.name)
        assertEquals(book.durChapterIndex, target.durChapterIndex)
        assertTrue(runCatching { WebBook.applyDartPreUpdatePatch(target, before,
            before + mapOf("name" to null)) }.isFailure)
        assertEquals(book.name, target.name)
    }

    @Test
    fun `pre update transport numeric types preserve unchanged book fields`() {
        val target = book.copy()
        val before = DartSourceEngine.jsonObject(target)
        val transported = before.mapValues { (_, value) ->
            if (value is Number) value.toDouble() else value
        } + mapOf("name" to "Transported")
        WebBook.applyDartPreUpdatePatch(target, before, transported)
        assertEquals("Transported", target.name)
        assertEquals(book.durChapterIndex, target.durChapterIndex)
    }

    @Test
    fun `pre update rejects distinct large numeric fields without rounding or partial mutation`() {
        val target = book.copy()
        val before = DartSourceEngine.jsonObject(target) + mapOf("lastCheckTime" to 9007199254740992L)
        val result = runCatching {
            WebBook.applyDartPreUpdatePatch(target, before,
                before + mapOf("name" to "Partial", "lastCheckTime" to 9007199254740993L))
        }
        assertTrue(result.isFailure)
        assertEquals(book.name, target.name)
    }

}
