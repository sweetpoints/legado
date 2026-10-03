package io.legado.app.ui.book.read

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.model.ReadBook
import io.legado.app.ui.about.AboutActivity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReviewDetailDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun actualDialogRestoresCapturedChapterAndEmbeddedRepliesAfterRotationThenCloses() {
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = "https://review-dialog-$id.invalid/book", name = "Fixture")
        val chapter =
            BookChapter(
                bookUrl = book.bookUrl,
                title = "Captured",
                url = "${book.bookUrl}/chapter",
                index = 4,
            )
        val source =
            BookSource(
                "https://review-dialog-$id.invalid",
                "Review fixture",
                mainJs =
                    """
            var config = {bookSourceUrl: 'https://review-dialog-$id.invalid', bookSourceName: 'Review fixture'};
            function search() { return []; } function getChapters() { return []; } function getContent() { return ''; }
            function getReviewDetail(chapter, book, paraIndex, paraData, page) {
                return {items: [{id: 'parent', name: chapter.title, content: paraIndex + ':' + paraData + ':' + page,
                    replyCount: 1, replies: [{id: 'embedded', name: 'Reply author', content: 'Embedded reply'}]}]};
            }
        """
                        .trimIndent(),
            )
        val oldBook = ReadBook.book
        val oldSource = ReadBook.bookSource
        val oldChapter = ReadBook.durChapterIndex
        runBlocking(Dispatchers.IO) { appDb.bookChapterDao.insert(chapter) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity {
                    ReadBook.book = book
                    ReadBook.bookSource = source
                    ReadBook.durChapterIndex = 99
                    ReviewDetailDialog(
                            -1,
                            12,
                            4,
                            "token",
                            book.bookUrl,
                            source.getKey(),
                            source.mainJs.hashCode(),
                        )
                        .show(it.supportFragmentManager, "review-detail")
                }
                compose.waitUntil {
                    compose.onAllNodesWithText("-1:token:1").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Captured").assertExists()
                compose.onNodeWithText("Embedded reply").assertExists()
                scenario.recreate()
                compose.waitUntil {
                    compose.onAllNodesWithText("Embedded reply").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("-1:token:1").assertExists()
                compose.onNodeWithTag("review-detail-resize").performClick()
                scenario.onActivity { host ->
                    val dialog =
                        host.supportFragmentManager.findFragmentByTag("review-detail")
                            as ReviewDetailDialog
                    assertTrue(dialog.requireDialog().window!!.attributes.height > 0)
                }
                compose.onNodeWithTag("review-detail-close").performClick()
                compose.waitUntil {
                    compose.onAllNodesWithTag("review-detail-close").fetchSemanticsNodes().isEmpty()
                }
                scenario.onActivity {
                    assertFalse(it.isFinishing)
                    ReadBook.book = oldBook
                    ReadBook.bookSource = oldSource
                    ReadBook.durChapterIndex = oldChapter
                }
            }
        } finally {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ReadBook.book = oldBook
                ReadBook.bookSource = oldSource
                ReadBook.durChapterIndex = oldChapter
            }
            runBlocking(Dispatchers.IO) { appDb.bookChapterDao.delByBook(book.bookUrl) }
        }
    }
}
