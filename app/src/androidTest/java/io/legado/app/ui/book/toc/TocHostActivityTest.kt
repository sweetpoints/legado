package io.legado.app.ui.book.toc

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.book.bookmark.BookmarkDialog
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class TocHostActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "TocHost-${UUID.randomUUID()}"
    private val book =
        Book(
            bookUrl = "fixture://$name",
            name = name,
            author = "Author",
            origin = "fixture-source",
            totalChapterNum = 2,
        )
    private val chapters =
        listOf(
            BookChapter(bookUrl = book.bookUrl, url = "first", index = 0, title = "First"),
            BookChapter(bookUrl = book.bookUrl, url = "second", index = 1, title = "Match"),
        )
    private val bookmark =
        Bookmark(
            time = System.currentTimeMillis() + 940000,
            bookName = name,
            bookAuthor = book.author,
            chapterName = "Match",
            content = "Owned note",
        )
    private val highlight =
        BookHighlight(
            time = bookmark.time + 1,
            bookUrl = book.bookUrl,
            bookName = name,
            bookAuthor = book.author,
            chapterUrl = "second",
            chapterIndex = 1,
            chapterName = "Match",
            note = "Owned highlight",
        )
    private lateinit var scenario: ActivityScenario<TocActivity>

    @Before
    fun setup() {
        runBlocking(Dispatchers.IO) {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            appDb.bookmarkDao.insert(bookmark)
            appDb.bookHighlightDao.insert(highlight)
        }
        scenario =
            ActivityScenario.launch(
                Intent(context, TocActivity::class.java).putExtra("bookUrl", book.bookUrl)
            )
        compose.waitUntil {
            var loaded = false
            scenario.onActivity { loaded = it.chapterModel.state.value.loaded }
            loaded
        }
    }

    @After
    fun cleanup() {
        scenario.close()
        runBlocking(Dispatchers.IO) {
            appDb.bookHighlightDao.delete(highlight)
            appDb.bookmarkDao.delete(bookmark)
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
        }
    }

    @Test
    fun directHostSearchesAllThreePagesAndRestoresSelectedTabWithoutLegacyFragments() {
        compose.onNodeWithTag("toc-host-search").performClick()
        compose.onNodeWithTag("toc-host-query").performTextReplacement("Match")
        compose.waitUntil {
            var matched = false
            scenario.onActivity {
                matched =
                    it.chapterModel.state.value.rows.map { row -> row.title } == listOf("Match")
            }
            matched
        }
        scenario.onActivity { it.sessionModel.tab(1) }
        compose.waitUntil {
            var matched = false
            scenario.onActivity {
                matched =
                    it.bookmarkModel.state.value.parameters?.search == "Match" &&
                        it.bookmarkModel.state.value.rows.size == 1
            }
            matched
        }
        scenario.onActivity { it.sessionModel.tab(2) }
        compose.waitUntil {
            var matched = false
            scenario.onActivity {
                matched =
                    it.highlightModel.state.value.parameters?.search == "Match" &&
                        it.highlightModel.state.value.rows.size == 1
            }
            matched
        }
        scenario.recreate()
        compose.onNodeWithTag("toc-host-query").assertTextContains("Match")
        scenario.onActivity {
            assertEquals(2, it.sessionModel.state.value.tab)
            assertTrue(
                it.supportFragmentManager.fragments.none { page ->
                    page is ChapterListFragment ||
                        page is BookmarkFragment ||
                        page is HighlightFragment
                }
            )
        }
    }

    @Test
    fun expansionAndReverseMenuUpdateActualRoomAndImmediatelyRefreshChapterPresentation() {
        compose.onNodeWithTag("toc-host-menu").performClick()
        compose.onNodeWithTag("toc-host-expanded").performClick()
        compose.waitUntil {
            !runBlocking(Dispatchers.IO) { appDb.bookDao.getBook(book.bookUrl)!!.getTocExpanded() }
        }
        compose.onNodeWithTag("toc-host-menu").performClick()
        compose.onNodeWithTag("toc-host-reverse").performClick()
        compose.waitUntil {
            var reversed = false
            scenario.onActivity {
                reversed =
                    it.chapterModel.state.value.rows.map { row -> row.title } ==
                        listOf("Match", "First")
            }
            reversed
        }
        assertTrue(
            runBlocking(Dispatchers.IO) {
                appDb.bookDao.getBook(book.bookUrl)!!.getReverseTocDisplay()
            }
        )
        assertEquals(
            chapters,
            runBlocking(Dispatchers.IO) { appDb.bookChapterDao.getChapterList(book.bookUrl) },
        )
    }

    @Test
    fun bookmarkLongPressOpensOneEditorAndRecreationPreservesDialogWithoutReplayingTicket() {
        compose.onNodeWithTag("toc-host-tab-1").performClick()
        compose.waitUntil {
            var ready = false
            scenario.onActivity { ready = it.bookmarkModel.state.value.loaded }
            ready
        }
        compose.onNodeWithTag("toc-bookmarks-row-${bookmark.time}").performTouchInput {
            longClick()
        }
        compose.waitUntil {
            var present = false
            scenario.onActivity {
                present =
                    it.supportFragmentManager.fragments.any { value -> value is BookmarkDialog }
            }
            present
        }
        scenario.recreate()
        scenario.onActivity {
            assertEquals(
                1,
                it.supportFragmentManager.fragments.count { value -> value is BookmarkDialog },
            )
            assertNull(it.bookmarkModel.state.value.open)
        }
    }
}
