package io.legado.app.ui.book.toc

import android.os.Bundle
import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.repository.TocHighlightTarget
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.book.read.HighlightNoteDialog
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class TocHighlightsHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val querySession = UUID.randomUUID().toString()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "HighlightFixture-${UUID.randomUUID()}"
    private val book = Book(bookUrl = "fixture://$name", name = name, author = "Author", durChapterIndex = 4)
    private val row = BookHighlight(time = System.currentTimeMillis() + 700000, bookUrl = book.bookUrl, bookName = name,
        bookAuthor = "Author", chapterIndex = 3, chapterPos = 27, chapterPosEnd = 35, chapterName = "Match chapter", bookText = "Original", note = "Note")
    private lateinit var scenario: ActivityScenario<AboutActivity>
    private lateinit var host: HighlightFragment
    @Before fun setup() {
        runBlocking(Dispatchers.IO) { appDb.bookHighlightDao.insert(row) }
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        scenario.onActivity {
            ViewModelProvider(it)[TocViewModel::class.java].bookData.value = book
            host = HighlightFragment().apply { arguments = Bundle().apply { putString("tocHighlights.session", querySession) } }
            it.supportFragmentManager.beginTransaction().add(android.R.id.content, host, "toc-highlights").commitNow()
        }
        compose.waitUntil { host.model.state.value.loaded }
    }
    @After fun cleanup() {
        scenario.close(); runBlocking(Dispatchers.IO) {
            withTimeout(5000) { while (!File(context.filesDir, "toc-highlight-state/$querySession.released").exists()) delay(10) }
            appDb.bookHighlightDao.delete(row)
        }
        AtomicFile(File(context.filesDir, "toc-highlight-state/$querySession.json")).delete()
        AtomicFile(File(context.filesDir, "toc-highlight-state/$querySession.released")).delete()
    }
    @Test fun sharedSearchAndRecreationKeepOneOwnedCallbackAndLatestFullEditorMetadata() {
        scenario.onActivity { val shared = ViewModelProvider(it)[TocViewModel::class.java]; shared.searchKey = "Match"; shared.startHighlightSearch("Match"); assertSame(host, shared.highlightCallBack) }
        compose.waitUntil { host.model.state.value.parameters?.search == "Match" && host.model.state.value.loaded }
        runBlocking(Dispatchers.IO) { appDb.bookHighlightDao.update(row.copy(chapterPos = 88, note = "Latest note")) }
        compose.onNodeWithTag("toc-highlights-row-${row.time}").performTouchInput { longClick() }
        compose.waitUntil { var exists = false; scenario.onActivity { exists = it.supportFragmentManager.findFragmentByTag("toc-highlight-editor") != null }; exists }
        scenario.onActivity { val editor = it.supportFragmentManager.findFragmentByTag("toc-highlight-editor") as HighlightNoteDialog
            @Suppress("DEPRECATION") val original = editor.requireArguments().getParcelable<BookHighlight>("highlight")!!
            assertEquals(88, original.chapterPos); assertEquals("Latest note", original.note)
        }
        compose.onNodeWithTag("highlight-note-input").assertTextContains("Latest note")
        scenario.recreate(); scenario.onActivity {
            host = it.supportFragmentManager.findFragmentByTag("toc-highlights") as HighlightFragment
            assertSame(host, ViewModelProvider(it)[TocViewModel::class.java].highlightCallBack)
            assertEquals(1, it.supportFragmentManager.fragments.count { fragment -> fragment is HighlightNoteDialog }); assertNull(host.model.state.value.open)
        }
        compose.onNodeWithTag("highlight-note-cancel").performClick()
        assertEquals("Latest note", runBlocking(Dispatchers.IO) { appDb.bookHighlightDao.getByBook(book.bookUrl).single().note })
    }
    @Test fun removedViewDoesNotClearAnotherOwnersSearchCallback() {
        scenario.onActivity { activity ->
            val shared = ViewModelProvider(activity)[TocViewModel::class.java]
            val another = object : TocViewModel.HighlightCallBack { override fun upHighlight(searchKey: String?) {} }
            shared.highlightCallBack = another
            activity.supportFragmentManager.beginTransaction().remove(host).commitNow()
            assertSame(another, shared.highlightCallBack)
        }
    }
    @Test fun nativeReadingResultRetainsRawCoordinatesAnchorAndUnknownTitleAndRejectsOrphan() {
        val target = TocHighlightTarget(row.copy(layoutTitleLength = -1), 9)
        val intent = highlightResultIntent(target)!!
        assertEquals(9, intent.getIntExtra("index", -1)); assertEquals(27, intent.getIntExtra("chapterPos", -1))
        assertEquals(-1, intent.getIntExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, 0))
        assertEquals("Original", intent.getStringExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT))
        assertEquals("", highlightResultIntent(target.copy(highlight = row.copy(chapterPosEnd = 40)))!!.getStringExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT))
        assertNull(highlightResultIntent(target.copy(chapterIndex = null)))
    }
}
