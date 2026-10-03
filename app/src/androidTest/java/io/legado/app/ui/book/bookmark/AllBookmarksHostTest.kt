package io.legado.app.ui.book.bookmark

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.FileBookmarkEditorRepository
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class AllBookmarksHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val original = Bookmark(System.currentTimeMillis() + 400000, "Fixture-${UUID.randomUUID()}", "Author", 8, 23, "Chapter", "Original", "Note")
    private val sessions = mutableListOf<String>(); private lateinit var scenario: ActivityScenario<AllBookmarkActivity>
    @Before fun setup() {
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.insert(original) }
        scenario = ActivityScenario.launch(Intent(context, AllBookmarkActivity::class.java))
        compose.waitUntil { var loaded = false; scenario.onActivity { loaded = it.model.state.value.loaded }; loaded }
        compose.onNodeWithTag("all-bookmarks-list").performScrollToNode(hasTestTag("all-bookmarks-row-${original.time}"))
    }
    @After fun cleanup() {
        scenario.close(); runBlocking(Dispatchers.IO) { appDb.bookmarkDao.delete(original) }
        sessions.forEach { File(context.filesDir, "bookmark-editor/$it.json").delete() }
    }
    private fun editor(): BookmarkDialog {
        var found: BookmarkDialog? = null
        compose.waitUntil { scenario.onActivity { found = it.supportFragmentManager.findFragmentByTag("all-bookmark-editor") as? BookmarkDialog }; found != null }
        val dialog = checkNotNull(found); sessions += dialog.requireArguments().getString("requestId")!!
        compose.waitUntil { dialog.model.state.value.loaded }; return dialog
    }
    @Test fun missingBookClickOpensActualEditDialogWithLatestRoomMetadataAndDeleteEnabled() {
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.update(original.copy(chapterPos = 74, content = "Latest note")) }
        compose.onNodeWithTag("all-bookmarks-row-${original.time}").performClick(); val dialog = editor()
        assertEquals(74, runBlocking(Dispatchers.IO) { FileBookmarkEditorRepository(context).load(dialog.requireArguments().getString("requestId")!!).seed.chapterPos })
        compose.onNodeWithTag("bookmark-editor-content").assertTextContains("Latest note")
        compose.onNodeWithTag("bookmark-editor-delete").assertIsEnabled()
        compose.onNodeWithTag("bookmark-editor-cancel").performClick()
        assertEquals("Latest note", runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.single { it.time == original.time }.content })
    }
    @Test fun longPressOpensDialogAndRecreationKeepsItWithoutRepeatingNavigation() {
        compose.onNodeWithTag("all-bookmarks-row-${original.time}").performTouchInput { longClick() }; editor()
        scenario.recreate(); compose.onNodeWithTag("bookmark-editor-content").assertExists()
        scenario.onActivity { assertEquals(1, it.supportFragmentManager.fragments.count { fragment -> fragment is BookmarkDialog }); assertNull(it.model.state.value.effect) }
        compose.onNodeWithTag("bookmark-editor-cancel").performClick()
        assertTrue(runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.any { it.time == original.time } })
    }
}
