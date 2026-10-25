package io.legado.app.ui.book.bookmark

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.FileBookmarkEditorRepository
import io.legado.app.ui.about.AboutActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookmarkEditorRestoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = io.legado.app.ui.flutter.FlutterUiTestDriver(instrumentation)
    @Before fun accessibility() { driver.enableAccessibility() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = mutableListOf<String>()
    private val bookmarks = mutableListOf<Bookmark>()

    @After
    fun cleanup() {
        driver.close()
        runBlocking(Dispatchers.IO) {
            appDb.bookmarkDao.delete(*bookmarks.toTypedArray())
            ids.forEach { File(context.filesDir, "bookmark-editor/$it.json").delete() }
        }
    }

    private fun await(scenario: ActivityScenario<AboutActivity>, closed: Boolean = false) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("bookmark-test")
                        as? BookmarkDialog
                ready = if (closed) dialog == null else dialog?.model?.state?.value?.loaded == true
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Bookmark editor did not restore/close")
    }

    private fun reveal(key: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        driver.await("visible bookmark field $key") {
            driver.node("bookmark-editor-$key")?.let { it.recycle(); return@await true }
            val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            var scrolled = false
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!scrolled && node.isVisibleToUser && node.isScrollable) scrolled = node.performAction(if (key == "text") android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
                node.recycle()
            }
            false
        }
    }
    private fun input(scenario: ActivityScenario<AboutActivity>, key: String, text: String) {
        reveal(key); driver.setText("bookmark-editor-$key", text)
        driver.await("native bookmark $key input ACK") {
            var same = false
            scenario.onActivity { host -> val state = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).model.state.value
                same = (if (key == "text") state.bookText else state.content) == text }
            same
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }

    @Test
    fun fullMetadataAndLargeTextUseDiskIdAndRealRecreationKeepsNoteDraftSelectionAndFocus() {
        val bookmark =
            Bookmark(
                System.nanoTime(),
                "Book-${UUID.randomUUID()}",
                "Author",
                3,
                15,
                "Chapter",
                "Large ".repeat(20000),
                "Original note",
            )
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = BookmarkDialog(bookmark, 0)
                assertEquals(setOf("requestId"), dialog.requireArguments().keySet())
                ids += dialog.requireArguments().getString("requestId")!!
                assertTrue(dialog.requireArguments().toString().length < 500)
                dialog.showNow(activity.supportFragmentManager, "bookmark-test")
            }
            await(scenario)
            // Initial giant text is a private-file checkpoint; real edits remain independent.
            val initial = runBlocking { FileBookmarkEditorRepository(context).load(ids.single()) }
            assertEquals(bookmark.bookText, initial.bookText)
            input(scenario, "text", "Draft text")
            input(scenario, "content", "Draft note")
            val node = requireNotNull(driver.node("bookmark-editor-content"))
            try { assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,
                android.os.Bundle().apply {
                    putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 5)
                    putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
                })) } finally { node.recycle() }
            driver.await("native and actual reverse note selection") {
                var native = false
                scenario.onActivity { host -> val value = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).model.state.value
                    native = value.contentStart == 5 && value.contentEnd == 2 && value.focused == "content" }
                val actual = driver.node("bookmark-editor-content")?.let { try { it.isFocused && it.textSelectionStart == 5 && it.textSelectionEnd == 2 } finally { it.recycle() } } ?: false
                native && actual
            }
            // Read the durable repository receipt rather than assuming debounce elapsed.
            driver.await("owned bookmark draft durable before recreate") {
                val value = runBlocking { FileBookmarkEditorRepository(context).load(ids.single()) }
                value.bookText == "Draft text" && value.content == "Draft note"
            }
            scenario.recreate()
            await(scenario)
            driver.await("actual restored note and raw selection") {
                driver.node("bookmark-editor-content")?.let { try { it.text?.toString() == "Draft note" && it.isFocused && it.textSelectionStart == 5 && it.textSelectionEnd == 2 } finally { it.recycle() } } ?: false
            }
            assertEquals("Chapter", driver.text("bookmark-editor-chapter"))
            reveal("text"); assertEquals("Draft text", driver.text("bookmark-editor-text"))
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("bookmark-test")
                        as BookmarkDialog
                assertEquals(ids.single(), dialog.requireArguments().getString("requestId"))
                assertEquals(5, dialog.model.state.value.contentStart)
                assertEquals(2, dialog.model.state.value.contentEnd)
            }
            val disk = runBlocking { FileBookmarkEditorRepository(context).load(ids.single()) }
            assertEquals(bookmark, disk.seed.bookmark())
            assertEquals("Draft text", disk.bookText)
            assertEquals("Draft note", disk.content)
            driver.click("bookmark-editor-cancel")
            await(scenario, true)
            runBlocking(Dispatchers.IO) {
                assertTrue(
                    appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).isEmpty()
                )
            }
        }
    }

    @Test
    fun actualConfirmInsertsCompleteBookmarkAndLegacyParcelableConvertsToSmallRequest() {
        val bookmark =
            Bookmark(
                System.nanoTime(),
                "Book-${UUID.randomUUID()}",
                "Author",
                4,
                21,
                "Chapter four",
                "Original",
                "Note",
            )
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog =
                    BookmarkDialog().apply {
                        arguments =
                            android.os.Bundle().apply {
                                putParcelable("bookmark", bookmark)
                                putInt("editPos", 0)
                            }
                    }
                dialog.showNow(activity.supportFragmentManager, "bookmark-test")
                ids += dialog.requireArguments().getString("requestId")!!
                assertFalse(dialog.requireArguments().containsKey("bookmark"))
            }
            await(scenario)
            input(scenario, "text", "")
            input(scenario, "content", " Edited ")
            driver.click("bookmark-editor-confirm")
            await(scenario, true)
            runBlocking(Dispatchers.IO) {
                assertEquals(
                    listOf(bookmark.copy(bookText = "", content = " Edited ")),
                    appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor),
                )
            }
        }
    }

    @Test
    fun missingParametersDismissWithoutCreatingDatabaseBookmark() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = BookmarkDialog()
                dialog.showNow(activity.supportFragmentManager, "bookmark-test")
                ids += dialog.requireArguments().getString("requestId")!!
            }
            await(scenario, true)
        }
    }
    @Test fun existingBookmarkDeleteUsesActualControlAndPreservesOtherRows() {
        val owned = Bookmark(System.nanoTime(), "Delete-${UUID.randomUUID()}", "Author", 3, 11, "Chapter", "Original", "Note")
        val other = owned.copy(time = owned.time + 1, bookName = "Other-${UUID.randomUUID()}")
        bookmarks += owned; bookmarks += other
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.insert(owned, other) }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host -> val dialog = BookmarkDialog(owned, 0)
                ids += dialog.requireArguments().getString("requestId")!!
                dialog.showNow(host.supportFragmentManager, "bookmark-test") }
            await(scenario); driver.click("bookmark-editor-delete"); await(scenario, true)
            runBlocking(Dispatchers.IO) {
                assertTrue(appDb.bookmarkDao.getByBook(owned.bookName, owned.bookAuthor).isEmpty())
                assertEquals(listOf(other), appDb.bookmarkDao.getByBook(other.bookName, other.bookAuthor))
            }
        }
    }
    @Test fun nativeBackDiscardsEditedDraftWithoutSavingAndKeepsParentAlive() {
        val bookmark = Bookmark(System.nanoTime(), "Back-${UUID.randomUUID()}", "Author", 2, 9, "Chapter", "Original", "Note")
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host -> val dialog = BookmarkDialog(bookmark)
                ids += dialog.requireArguments().getString("requestId")!!
                dialog.showNow(host.supportFragmentManager, "bookmark-test") }
            await(scenario); input(scenario, "content", "Unsaved note")
            scenario.onActivity { host ->
                val window = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            driver.await("owned bookmark IME hidden") {
                var hidden = false
                scenario.onActivity { host ->
                    val decor = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!.decorView
                    hidden = androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
                }; hidden
            }
            androidx.test.espresso.Espresso.pressBack(); await(scenario, true)
            scenario.onActivity { assertFalse(it.isFinishing) }
            runBlocking(Dispatchers.IO) { assertTrue(appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).isEmpty()) }
        }
    }
    @Test fun missingTicketRetryLoadsActualOriginalTicketAndCommitsFullMetadataOnce() {
        val bookmark = Bookmark(System.nanoTime(), "Retry-${UUID.randomUUID()}", "Author", 8, 19, "Retry chapter", "Original", "Note")
        bookmarks += bookmark
        val ticket = UUID.randomUUID().toString(); ids += ticket
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host -> BookmarkDialog().apply { arguments = android.os.Bundle().apply { putString("requestId", ticket) } }
                .showNow(host.supportFragmentManager, "bookmark-test") }
            driver.awaitIdentifier("bookmark-editor-retry")
            val draft = io.legado.app.data.repository.BookmarkEditorDraft(io.legado.app.data.repository.BookmarkEditorSeed.from(bookmark))
            runBlocking(Dispatchers.IO) {
                val file = File(context.filesDir, "bookmark-editor/$ticket.json"); file.parentFile!!.mkdirs()
                file.writeText(io.legado.app.utils.GSON.toJson(draft))
            }
            // Invoke only the actual Retry button/descendant, never a backdrop ancestor.
            driver.await("owned bookmark Retry CLICK") {
                val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                driver.node("bookmark-editor-retry")?.let(queue::add) ?: return@await false
                var clicked = false
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (!clicked && node.isVisibleToUser && node.isEnabled && node.isClickable && node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK })
                        clicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                    node.recycle()
                }; clicked
            }
            await(scenario); driver.await("actual original ticket chapter") { driver.text("bookmark-editor-chapter") == bookmark.chapterName }
            driver.click("bookmark-editor-confirm"); await(scenario, true)
            runBlocking(Dispatchers.IO) { assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor)) }
            val finished = runBlocking { FileBookmarkEditorRepository(context).load(ticket) }; assertTrue(finished.finished)
            scenario.recreate()
            scenario.onActivity { host -> BookmarkDialog().apply { arguments = android.os.Bundle().apply { putString("requestId", ticket) } }
                .showNow(host.supportFragmentManager, "bookmark-test") }
            await(scenario, true)
            runBlocking(Dispatchers.IO) { assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor)) }
        }
    }

    @Test fun actualBackdropCancelsDraftImmediatelyWithoutSaving() {
        val bookmark = Bookmark(System.nanoTime(), "Backdrop-${UUID.randomUUID()}", "Author", 1, 2, "Chapter", "Original", "Note")
        bookmarks += bookmark
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { host -> val dialog = BookmarkDialog(bookmark)
                ids += dialog.requireArguments().getString("requestId")!!
                dialog.showNow(host.supportFragmentManager, "bookmark-test") }
            await(scenario); input(scenario, "content", "Discard on backdrop")
            val outside = driver.bounds("bookmark-editor-outside")
            val heading = driver.bounds("bookmark-editor-heading")
            val window = android.graphics.Rect()
            scenario.onActivity { host -> (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!.decorView.getGlobalVisibleRect(window) }
            assertTrue(outside.intersect(window)); assertTrue(heading.top > outside.top + 8)
            driver.touchDown(outside.exactCenterX(), (outside.top + heading.top) / 2f).close()
            await(scenario, true)
            runBlocking(Dispatchers.IO) { assertTrue(appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).isEmpty()) }
        }
    }

}
