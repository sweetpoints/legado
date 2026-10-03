package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.ui.main.bookshelf.components.toBookshelfCardModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfReadProgressTest {

    @Test
    fun `unopened books do not expose progress`() {
        assertNull(Book(totalChapterNum = 20).readProgress())
    }

    @Test
    fun `single chapter books become complete after reading starts`() {
        val book = Book(totalChapterNum = 1, durChapterPos = 1)
        assertEquals(1f, book.readProgress() ?: 0f, 0f)
    }

    @Test
    fun `chapter index maps to bounded progress`() {
        assertEquals(
            0.5f,
            Book(totalChapterNum = 11, durChapterIndex = 5).readProgress() ?: 0f,
            0f,
        )
        assertEquals(
            1f,
            Book(totalChapterNum = 10, durChapterIndex = 20).readProgress() ?: 0f,
            0f,
        )
    }

    @Test
    fun `bookshelf progress modes preserve legacy settings and thickness`() {
        assertEquals(0, BookshelfReadProgressMode.resolve(null, false))
        assertEquals(1, BookshelfReadProgressMode.resolve(null, true))
        assertEquals(2, BookshelfReadProgressMode.resolve("2", false))
        assertEquals(2, BookshelfReadProgressMode.thicknessDp(0))
        assertEquals(2, BookshelfReadProgressMode.thicknessDp(1))
        assertEquals(4, BookshelfReadProgressMode.thicknessDp(2))
    }

    @Test
    fun `compose progress projection honors each mode without changing book identity`() {
        val book = Book(bookUrl = "reader", totalChapterNum = 11, durChapterIndex = 5)
        val hidden = book.toBookshelfCardModel(false, 0, false)
        val standard = book.toBookshelfCardModel(false, 1, false)
        val enhanced = book.toBookshelfCardModel(false, 2, false)
        assertNull(hidden.readProgress)
        assertNull(hidden.progressPercent)
        assertEquals("50%", standard.progressPercent)
        assertEquals(2, standard.progressThicknessDp)
        assertEquals("50%", enhanced.progressPercent)
        assertEquals(4, enhanced.progressThicknessDp)
        assertEquals(hidden.key, standard.key)
        assertEquals(standard.key, enhanced.key)
        assertNull(Book().toBookshelfCardModel(false, 2, false).readProgress)
    }

    @Test
    fun `compose header defaults hide both optional sections`() {
        val header = io.legado.app.ui.main.bookshelf.components.BookshelfHeaderModel()
        assertNull(header.stats)
        assertNull(header.recent)
    }

    @Test
    fun `continue reading query includes every shelf media type`() {
        val source = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        val propertyIndex = source.indexOf("val lastReadBookOnShelf")
        val queryStart = source.lastIndexOf("@get:Query(", propertyIndex)
        val query = source.substring(queryStart, propertyIndex)

        assertTrue(query.contains("type & \${BookType.notShelf} = 0"))
        assertFalse(query.replace("BookType.notShelf", "").contains("BookType."))
        assertTrue(query.contains("durChapterIndex > 0 OR durChapterPos > 0"))
        assertTrue(query.contains("durChapterTime DESC limit 1"))
    }

    @Test
    fun `reading count query only includes started shelf books`() {
        val source = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        val propertyIndex = source.indexOf("val readingCount")
        val queryStart = source.lastIndexOf("@get:Query(", propertyIndex)
        val query = source.substring(queryStart, propertyIndex)

        assertTrue(query.contains("(durChapterIndex > 0 OR durChapterPos > 0)"))
        assertTrue(query.contains("and type & \${BookType.notShelf} = 0"))
        assertFalse(query.replace("BookType.notShelf", "").contains("BookType."))
        assertFalse(query.contains("durChapterTime"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
