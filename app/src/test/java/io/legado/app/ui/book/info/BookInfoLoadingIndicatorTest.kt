package io.legado.app.ui.book.info

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoLoadingIndicatorTest {

    @Test
    fun `read progress appears only for read multi chapter books`() {
        assertNull(resolveBookInfoReadProgress(Book(totalChapterNum = 20)))
        assertNull(resolveBookInfoReadProgress(Book(totalChapterNum = 1, durChapterPos = 1)))
        assertEquals(
            50,
            resolveBookInfoReadProgress(Book(totalChapterNum = 11, durChapterIndex = 5)),
        )
    }

    @Test
    fun `empty stored toc title follows the current chapter index`() {
        val chapters =
            listOf(
                BookChapter(title = "卷一", isVolume = true),
                BookChapter(title = "第一章"),
                BookChapter(title = "第二章"),
            )

        assertEquals(
            "已保存章节",
            resolveBookInfoTocTitle("已保存章节", 2, chapters),
        )
        assertEquals("卷一", resolveBookInfoTocTitle(null, 0, chapters))
        assertEquals("第二章", resolveBookInfoTocTitle(null, 99, chapters))
    }

    @Test
    fun `main shelf refresh still propagates pre-update script failure`() {
        val mainViewModel =
            projectFile("src/main/java/io/legado/app/ui/main/MainViewModel.kt").readText()
        assertTrue(mainViewModel.contains("WebBook.runPreUpdateJs(source, book).getOrThrow()"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp")).first(File::isFile)
}
