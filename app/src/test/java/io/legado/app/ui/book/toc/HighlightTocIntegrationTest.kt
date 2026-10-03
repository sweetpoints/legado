package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookHighlight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTocIntegrationTest {

    @Test
    fun `current chapter url wins after toc reorder`() {
        val highlight =
            BookHighlight(
                chapterUrl = "chapter-url",
                chapterIndex = 2,
            )

        assertEquals(
            8,
            resolveHighlightChapterIndex(highlight, mapOf("chapter-url" to 8)),
        )
        assertNull(resolveHighlightChapterIndex(highlight, emptyMap()))
        assertEquals(
            2,
            resolveHighlightChapterIndex(highlight.copy(chapterUrl = ""), emptyMap()),
        )
    }

    @Test
    fun `highlight order uses body positions`() {
        val withTitle = BookHighlight(chapterPos = 14, layoutTitleLength = 10)
        val withoutTitle = BookHighlight(chapterPos = 8, layoutTitleLength = 0)

        assertEquals(4, highlightBodyPosition(withTitle))
        assertEquals(8, highlightBodyPosition(withoutTitle))
        assertTrue(highlightBodyPosition(withTitle) < highlightBodyPosition(withoutTitle))
    }

    @Test
    fun `recreated chapter page does not stack observers`() {
        val fragment =
            projectFile("src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt").readText()
        val eventBusExtensions =
            projectFile("src/main/java/io/legado/app/utils/EventBusExtensions.kt").readText()
        val fragmentObserver =
            eventBusExtensions
                .substringAfter("inline fun <reified EVENT> Fragment.observeEvent(")
                .substringBefore("inline fun <reified EVENT> Fragment.observeEventSticky(")
        val fragmentStickyObserver =
            eventBusExtensions
                .substringAfter("inline fun <reified EVENT> Fragment.observeEventSticky(")
                .substringBefore("inline fun <reified EVENT> LifecycleService.observeEvent(")

        assertTrue(fragment.contains("bookData.observe(viewLifecycleOwner)"))
        assertTrue(!fragment.contains("observe(this@ChapterListFragment)"))
        assertTrue(fragmentObserver.contains("observe(viewLifecycleOwner, o)"))
        assertTrue(fragmentStickyObserver.contains("observeSticky(this, o)"))
    }

    @Test
    fun `highlight jump waits for current layout coordinates`() {
        val readBook = projectFile("src/main/java/io/legado/app/model/ReadBook.kt").readText()
        val readViewModel =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt").readText()

        val highlight =
            BookHighlight(
                chapterPos = 20,
                chapterPosEnd = 24,
                layoutTitleLength = 12,
                bookText = "Text",
            )
        assertEquals("Text", io.legado.app.model.book.tocHighlightAnchorText(highlight))
        assertEquals(
            "",
            io.legado.app.model.book.tocHighlightAnchorText(highlight.copy(chapterPosEnd = 23)),
        )
        assertTrue(readBook.contains("if (hasPendingHighlightJump()) return"))
        assertTrue(readBook.countMatches("positionReady && !available") >= 2)
        assertTrue(readBook.contains("if (curTextChapter !== textChapter) return false"))
        assertEquals(2, readBook.countMatches("resolvePendingHighlightAnchor(book, textChapter)"))
        assertTrue(readBook.contains("if (!chapter.isCompleted)"))
        assertTrue(readBook.contains("manualHighlightAnchorsVersion"))
        assertTrue(readBook.contains("val cacheResult = textChapter.isCompleted"))
        assertTrue(readViewModel.contains("|| hasHighlightTarget"))
        assertTrue(
            readViewModel.contains(
                "intent.removeExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH)"
            )
        )
        assertTrue(
            readViewModel.contains(
                "intent.removeExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT)"
            )
        )
        assertTrue(readViewModel.contains("highlightAnchorText = highlightAnchorText"))
        assertTrue(readViewModel.contains("intent.removeExtra(\"index\")"))
        assertTrue(readViewModel.contains("intent.removeExtra(\"chapterPos\")"))
    }

    @Test
    fun `highlight queries use the stable book owner`() {
        val dao = projectFile("src/main/java/io/legado/app/data/dao/BookHighlightDao.kt").readText()
        assertTrue(dao.contains("where bookUrl = :bookUrl"))
        assertTrue(dao.contains("fun flowByBook(bookUrl: String)"))
        assertTrue(dao.contains("fun flowSearch(bookUrl: String, key: String)"))
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")

    private fun String.countMatches(value: String): Int =
        windowed(value.length).count { it == value }
}
