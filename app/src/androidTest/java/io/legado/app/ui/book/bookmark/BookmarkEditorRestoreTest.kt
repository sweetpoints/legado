package io.legado.app.ui.book.bookmark

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.FileBookmarkEditorRepository
import io.legado.app.ui.about.AboutActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BookmarkEditorRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = mutableListOf<String>(); private val bookmarks = mutableListOf<Bookmark>()
    @After fun cleanup() = runBlocking(Dispatchers.IO) {
        appDb.bookmarkDao.delete(*bookmarks.toTypedArray()); ids.forEach { File(context.filesDir, "bookmark-editor/$it.json").delete() }
    }
    private fun await(scenario: ActivityScenario<AboutActivity>, closed: Boolean = false) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("bookmark-test") as? BookmarkDialog
                ready = if (closed) dialog == null else dialog?.model?.state?.value?.loaded == true
            }
            if (ready) return; SystemClock.sleep(25)
        }
        throw AssertionError("Bookmark editor did not restore/close")
    }
    @Test fun fullMetadataAndLargeTextUseDiskIdAndRealRecreationKeepsNoteDraftSelectionAndFocus() {
        val bookmark = Bookmark(System.nanoTime(), "Book-${UUID.randomUUID()}", "Author", 3, 15, "Chapter", "Large ".repeat(20000), "Original note")
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = BookmarkDialog(bookmark, 0); assertEquals(setOf("requestId"), dialog.requireArguments().keySet()); ids += dialog.requireArguments().getString("requestId")!!
                assertTrue(dialog.requireArguments().toString().length < 500); dialog.showNow(activity.supportFragmentManager, "bookmark-test")
            }
            await(scenario); compose.onNodeWithTag("bookmark-editor-text").performScrollTo().performTextReplacement("Draft text")
            compose.onNodeWithTag("bookmark-editor-content").performScrollTo().performTextReplacement("Draft note")
            compose.onNodeWithTag("bookmark-editor-content").performTextInputSelection(TextRange(2, 5))
            scenario.recreate(); await(scenario)
            compose.onNodeWithTag("bookmark-editor-chapter").assertTextEquals("Chapter")
            compose.onNodeWithTag("bookmark-editor-text").assertTextContains("Draft text"); compose.onNodeWithTag("bookmark-editor-content").assertTextContains("Draft note").assertIsFocused()
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog
                assertEquals(ids.single(), dialog.requireArguments().getString("requestId")); assertEquals(2, dialog.model.state.value.contentStart); assertEquals(5, dialog.model.state.value.contentEnd)
            }
            val disk = runBlocking { FileBookmarkEditorRepository(context).load(ids.single()) }
            assertEquals(bookmark, disk.seed.bookmark()); assertEquals("Draft text", disk.bookText); assertEquals("Draft note", disk.content)
            compose.onNodeWithTag("bookmark-editor-cancel").performClick(); await(scenario, true)
            runBlocking(Dispatchers.IO) { assertTrue(appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).isEmpty()) }
        }
    }
    @Test fun actualConfirmInsertsCompleteBookmarkAndLegacyParcelableConvertsToSmallRequest() {
        val bookmark = Bookmark(System.nanoTime(), "Book-${UUID.randomUUID()}", "Author", 4, 21, "Chapter four", "Original", "Note")
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = BookmarkDialog().apply { arguments = android.os.Bundle().apply { putParcelable("bookmark", bookmark); putInt("editPos", 0) } }
                dialog.showNow(activity.supportFragmentManager, "bookmark-test"); ids += dialog.requireArguments().getString("requestId")!!
                assertFalse(dialog.requireArguments().containsKey("bookmark"))
            }
            await(scenario); compose.onNodeWithTag("bookmark-editor-text").performTextReplacement("")
            compose.onNodeWithTag("bookmark-editor-content").performScrollTo().performTextReplacement(" Edited ")
            compose.onNodeWithTag("bookmark-editor-confirm").performClick(); await(scenario, true)
            runBlocking(Dispatchers.IO) { assertEquals(listOf(bookmark.copy(bookText = "", content = " Edited ")), appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor)) }
        }
    }
    @Test fun missingParametersDismissWithoutCreatingDatabaseBookmark() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = BookmarkDialog(); dialog.showNow(activity.supportFragmentManager, "bookmark-test"); ids += dialog.requireArguments().getString("requestId")!!
            }
            await(scenario, true)
        }
    }
}
