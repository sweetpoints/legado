package io.legado.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaReaderShelfAcceptanceTest {
    @Test
    fun acceptancePreservesFreshMetadataAndProgressWithoutRevivingRemovedRow() = runBlocking {
        val url = "manga-shelf-${UUID.randomUUID()}"
        val initial =
            Book(bookUrl = url, name = "initial", type = BookType.image or BookType.notShelf)
        val repository = DefaultMangaReaderOperationsRepository()
        try {
            withContext(Dispatchers.IO) {
                appDb.bookDao.insert(initial)
                appDb.bookDao.update(
                    initial.copy(
                        name = "fresh",
                        customCoverUrl = "fresh-cover",
                        group = 8,
                        durChapterIndex = 4,
                        durChapterPos = 9,
                        durChapterTime = 1234,
                    )
                )
            }
            assertTrue(repository.addToBookshelf(url))
            withContext(Dispatchers.IO) {
                val accepted = checkNotNull(appDb.bookDao.getBook(url))
                assertEquals("fresh", accepted.name)
                assertEquals("fresh-cover", accepted.customCoverUrl)
                assertEquals(8L, accepted.group)
                assertEquals(4, accepted.durChapterIndex)
                assertEquals(9, accepted.durChapterPos)
                assertEquals(1234L, accepted.durChapterTime)
                assertEquals(BookType.image, accepted.type)
                appDb.bookDao.delete(accepted)
            }
            assertFalse(repository.addToBookshelf(url))
            withContext(Dispatchers.IO) { assertNull(appDb.bookDao.getBook(url)) }
        } finally {
            withContext(Dispatchers.IO) {
                appDb.bookDao.getBook(url)?.let { appDb.bookDao.delete(it) }
            }
        }
    }
}
