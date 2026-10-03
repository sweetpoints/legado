package io.legado.app.ui.book.changecover

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChangeCoverDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun originalActivityCallbackReceivesDefaultSentinelAfterActualDialogRecreation() =
        withBook { book ->
            ActivityScenario.launch<BookInfoEditActivity>(intent(book)).use { scenario ->
                waitBook(scenario)
                scenario.onActivity {
                    ChangeCoverDialog(book.name, "作者：${book.author} 著")
                        .show(it.supportFragmentManager, "change-cover")
                }
                waitDefault()
                scenario.recreate()
                waitDefault()
                compose.onNodeWithTag("change-cover-item-default").performClick()
                compose.waitUntil {
                    var received = false
                    scenario.onActivity {
                        received = model(it).state.value.draft?.input?.cover == "use_default_cover"
                    }
                    received
                }
                scenario.onActivity {
                    assertTrue(model(it).state.value.draft!!.input!!.refreshCover)
                    assertEquals(
                        model(it).state.value.draft!!.input!!.cover,
                        model(it).state.value.draft!!.preview!!.path,
                    )
                    it.supportFragmentManager.executePendingTransactions()
                    assertNull(it.supportFragmentManager.findFragmentByTag("change-cover"))
                }
            }
        }

    @Test
    fun realSourceCoverCallbackKeepsOriginalUrlAndCloseLeavesActivityDraftUntouched() =
        withBook { book ->
            ActivityScenario.launch<BookInfoEditActivity>(intent(book)).use { scenario ->
                waitBook(scenario)
                scenario.onActivity {
                    ChangeCoverDialog(book.name, book.author)
                        .show(it.supportFragmentManager, "change-cover")
                }
                waitDefault()
                compose.onNodeWithTag("change-cover-close").performClick()
                scenario.onActivity {
                    it.supportFragmentManager.executePendingTransactions()
                    assertTrue(model(it).state.value.draft!!.input!!.changed.isEmpty())
                    ChangeCoverDialog(book.name, book.author)
                        .show(it.supportFragmentManager, "change-cover")
                }
                waitDefault()
                val sourceBook = "${book.bookUrl}/candidate/1"
                compose.onNodeWithTag("change-cover-item-book:$sourceBook").performClick()
                compose.waitUntil {
                    var received = false
                    scenario.onActivity {
                        received =
                            model(it).state.value.draft?.input?.cover == "/nonexistent-cover-1.jpg"
                    }
                    received
                }
                scenario.onActivity {
                    assertTrue(model(it).state.value.draft!!.input!!.refreshCover)
                    assertEquals(
                        model(it).state.value.draft!!.input!!.cover,
                        model(it).state.value.draft!!.preview!!.path,
                    )
                }
            }
        }

    private fun model(activity: BookInfoEditActivity) = activity.viewModel

    private fun intent(book: Book) =
        Intent(
                ApplicationProvider.getApplicationContext<Context>(),
                BookInfoEditActivity::class.java,
            )
            .putExtra("bookUrl", book.bookUrl)

    private fun waitBook(scenario: ActivityScenario<BookInfoEditActivity>) = compose.waitUntil {
        var loaded = false
        scenario.onActivity { loaded = model(it).state.value.loaded }
        loaded
    }

    private fun waitDefault() = compose.waitUntil {
        compose.onAllNodesWithTag("change-cover-item-default").fetchSemanticsNodes().isNotEmpty()
    }

    private fun withBook(body: (Book) -> Unit) {
        val token = UUID.randomUUID().toString()
        val book =
            Book(
                bookUrl = "https://change-cover-$token.invalid",
                name = "Cover $token",
                author = "Author",
                persistedCoverUrl = "previous",
            )
        val source =
            BookSource(
                bookSourceUrl = "https://cover-source-$token.invalid",
                bookSourceName = "Source",
            )
        runBlocking(Dispatchers.IO) {
            appDb.bookDao.insert(book)
            appDb.bookSourceDao.insert(source)
            appDb.searchBookDao.insert(
                *(1..2)
                    .map { index ->
                        SearchBook(
                            bookUrl = "${book.bookUrl}/candidate/$index",
                            name = book.name,
                            author = book.author,
                            origin = source.bookSourceUrl,
                            originName = "Source $index",
                            coverUrl = "/nonexistent-cover-$index.jpg",
                            originOrder = index,
                        )
                    }
                    .toTypedArray()
            )
        }
        try {
            body(book)
        } finally {
            runBlocking(Dispatchers.IO) {
                appDb.bookDao.delete(book)
                appDb.bookSourceDao.delete(source)
            }
        }
    }
}
