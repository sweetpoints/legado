package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.ReadBook
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class AppReviewDetailStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun actualJsDetailAndRepliesUseCapturedChapterParagraphDataAndPageBeforeDeclarativeGates() =
        runBlocking {
            val id = UUID.randomUUID().toString()
            val session = UUID.randomUUID().toString()
            val book = Book(bookUrl = "https://review-$id.invalid/book", name = "Review fixture")
            val chapter =
                BookChapter(
                    bookUrl = book.bookUrl,
                    title = "Captured",
                    url = "https://review-$id.invalid/chapter",
                    index = 4,
                )
            val source =
                BookSource(
                    "https://review-$id.invalid",
                    "Review JS",
                    mainJs =
                        """
            var config = {bookSourceUrl: 'https://review-$id.invalid', bookSourceName: 'Review JS'};
            function search() { return []; } function getChapters() { return []; } function getContent() { return ''; }
            function getReviewDetail(chapter, book, paraIndex, paraData, page) {
                return {items: [{id: 'parent', name: chapter.title, content: paraIndex + ':' + paraData + ':' + page, replyCount: 2,
                    avatar: '/avatar.png', imageUrl: '/image.png', audioUrl: '/audio.mp3', badges: ['Badge']}], nextPageUrl: '/page2'};
            }
            function getReviewReplies(chapter, book, paraIndex, paraData, reviewId, page) {
                return {items: [{id: 'reply', content: reviewId + ':' + page + ':' + chapter.title + ':' + paraData}]};
            }
        """
                            .trimIndent(),
                )
            val key =
                ReviewDetailKey(
                    -1,
                    4,
                    "captured-token",
                    book.bookUrl,
                    source.getKey(),
                    source.mainJs.hashCode(),
                )
            val oldBook = ReadBook.book
            val oldSource = ReadBook.bookSource
            val oldChapter = ReadBook.durChapterIndex
            try {
                withContext(Dispatchers.IO) { appDb.bookChapterDao.insert(chapter) }
                withContext(Dispatchers.Main) {
                    ReadBook.book = book
                    ReadBook.bookSource = source
                    ReadBook.durChapterIndex = 99
                }
                val repo = DefaultReviewDetailRepository(AppReviewDetailStore(context))
                val result = withContext(Dispatchers.Main) { repo.detail(key, 3, null) }
                assertNotNull(result)
                assertTrue(result!!.hasNextPageRule)
                assertTrue(result.hasReplyUrl)
                assertEquals("-1:captured-token:3", result.items.single().content)
                assertEquals("Captured", result.items.single().name)
                assertTrue(result.items.single().imageUrl!!.endsWith("/image.png"))
                assertTrue(result.items.single().audioUrl!!.endsWith("/audio.mp3"))
                assertEquals(listOf("Badge"), result.items.single().badges)
                val replies = repo.replies(key, "parent", 2)
                assertEquals("parent:2:Captured:captured-token", replies!!.replies.single().content)
                val snapshot =
                    ReviewDetailSnapshot(
                        key,
                        result.items,
                        page = 3,
                        nextPageUrl = result.nextPageUrl,
                        hasReplies = true,
                    )
                repo.stage(session, snapshot)
                assertEquals(snapshot, repo.restore(session, key))
                withContext(Dispatchers.Main) { ReadBook.book = book.copy(bookUrl = "other") }
                assertNull(repo.detail(key, 1, null))
                assertNull(repo.restore(session, key))
            } finally {
                withContext(Dispatchers.Main) {
                    ReadBook.book = oldBook
                    ReadBook.bookSource = oldSource
                    ReadBook.durChapterIndex = oldChapter
                }
                withContext(Dispatchers.IO) {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    File(context.cacheDir, "review-details/$session.json").delete()
                }
            }
        }
}
