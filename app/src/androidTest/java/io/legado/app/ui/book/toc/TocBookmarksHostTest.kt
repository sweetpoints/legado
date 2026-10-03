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
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.book.bookmark.BookmarkDialog
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class TocBookmarksHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val querySession = UUID.randomUUID().toString()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val editorSessions = mutableListOf<String>()
    private val name = "TocFixture-${UUID.randomUUID()}"
    private val book = Book(bookUrl = "fixture://$name", name = name, author = "Author", durChapterIndex = 4)
    private val row = Bookmark(System.currentTimeMillis() + 600000, name, "Author", 3, 27, "Match chapter", "Original", "Note")
    private lateinit var scenario: ActivityScenario<AboutActivity>; private lateinit var host: BookmarkFragment
    @Before fun setup() {
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.insert(row) }
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        scenario.onActivity {
            ViewModelProvider(it)[TocViewModel::class.java].bookData.value = book
            host = BookmarkFragment().apply { arguments = Bundle().apply { putString("tocBookmarks.session", querySession) } }; it.supportFragmentManager.beginTransaction().add(android.R.id.content, host, "toc-bookmarks").commitNow()
        }
        compose.waitUntil { host.model.state.value.loaded }
    }
    @After fun cleanup() {
        scenario.close(); runBlocking(Dispatchers.IO) {
            withTimeout(5000) { while (!File(context.filesDir, "toc-bookmark-state/$querySession.released").exists()) delay(10) }
            appDb.bookmarkDao.delete(row)
        }
        AtomicFile(File(context.filesDir, "toc-bookmark-state/$querySession.json")).delete()
        AtomicFile(File(context.filesDir, "toc-bookmark-state/$querySession.released")).delete()
        editorSessions.forEach { AtomicFile(File(context.filesDir, "bookmark-editor/$it.json")).delete() }
    }
    @Test fun hostSearchAndRecreationKeepOneOwnedCallbackAndLatestEditorMetadata() {
        scenario.onActivity { val shared = ViewModelProvider(it)[TocViewModel::class.java]; shared.searchKey = "Match"; shared.startBookmarkSearch("Match"); assertSame(host, shared.bookMarkCallBack) }
        compose.waitUntil { host.model.state.value.parameters?.search == "Match" && host.model.state.value.loaded }
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.update(row.copy(chapterPos = 88, content = "Latest note")) }
        compose.onNodeWithTag("toc-bookmarks-row-${row.time}").performTouchInput { longClick() }
        compose.waitUntil { var exists = false; scenario.onActivity { exists = it.supportFragmentManager.findFragmentByTag("toc-bookmark-editor") != null }; exists }
        lateinit var editor: BookmarkDialog
        scenario.onActivity { editor = it.supportFragmentManager.findFragmentByTag("toc-bookmark-editor") as BookmarkDialog; editorSessions += editor.requireArguments().getString("requestId")!! }
        compose.waitUntil { editor.model.state.value.loaded }
        compose.onNodeWithTag("bookmark-editor-content").assertTextContains("Latest note")
        scenario.recreate(); scenario.onActivity { host = it.supportFragmentManager.findFragmentByTag("toc-bookmarks") as BookmarkFragment
            assertSame(host, ViewModelProvider(it)[TocViewModel::class.java].bookMarkCallBack)
            assertEquals(1, it.supportFragmentManager.fragments.count { fragment -> fragment is BookmarkDialog }); assertNull(host.model.state.value.open) }
        compose.onNodeWithTag("bookmark-editor-cancel").performClick()
        assertEquals("Latest note", runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.single { it.time == row.time }.content })
    }
    @Test fun destroyingViewReleasesCallbackWithoutClearingAnotherOwner() {
        scenario.onActivity { activity ->
            val shared = ViewModelProvider(activity)[TocViewModel::class.java]
            val another = object : TocViewModel.BookmarkCallBack { override fun upBookmark(searchKey: String?) {} }
            shared.bookMarkCallBack = another
            activity.supportFragmentManager.beginTransaction().remove(host).commitNow()
            assertSame(another, shared.bookMarkCallBack)
        }
    }
}
