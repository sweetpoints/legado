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

    private fun ownedNode(scenario: ActivityScenario<AboutActivity>, id: String): android.view.accessibility.AccessibilityNodeInfo? {
        val node = driver.node("bookmark-editor-$id") ?: return null
        val outside = driver.node("bookmark-editor-outside")
        val window = android.graphics.Rect()
        scenario.onActivity { host ->
            (host.supportFragmentManager.findFragmentByTag("bookmark-test") as? BookmarkDialog)
                ?.dialog?.window?.decorView?.getGlobalVisibleRect(window)
        }
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val owned = outside != null && node.windowId == outside.windowId && android.graphics.Rect.intersects(window, bounds)
        outside?.recycle()
        if (owned) return node
        node.recycle(); return null
    }
    private fun until(deadline: Long, description: String, condition: () -> Boolean) {
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue(description, condition())
    }
    private data class WindowObservation(
        val decorIdentity: Int,
        val focusIdentity: Int?,
        val bounds: android.graphics.Rect,
        val width: Int,
        val height: Int,
        val attached: Boolean,
        val windowFocused: Boolean,
        val imeVisible: Boolean?,
        val imeBottom: Int?,
        val inputActive: Boolean,
        val acceptingText: Boolean,
    )
    private fun observation(scenario: ActivityScenario<AboutActivity>): WindowObservation? {
        var result: WindowObservation? = null
        scenario.onActivity { host ->
            val window = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as? BookmarkDialog)?.dialog?.window
                ?: return@onActivity
            val decor = window.decorView
            val bounds = android.graphics.Rect()
            if (!decor.getGlobalVisibleRect(bounds)) return@onActivity
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
            val focus = window.currentFocus
            val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            result = WindowObservation(System.identityHashCode(decor), focus?.let(System::identityHashCode), bounds,
                decor.width, decor.height, decor.isAttachedToWindow, decor.hasWindowFocus(),
                insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()),
                insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom,
                focus != null && manager.isActive(focus), manager.isAcceptingText)
        }
        return result
    }
    private fun frame(scenario: ActivityScenario<AboutActivity>, deadline: Long, requireInputConnection: Boolean = false) {
        // Observe two Choreographer turns without invalidating, requesting layout or
        // replacing a host draw callback. Works on the API26 minimum as well.
        until(deadline, "Owned viewport, IME and input connection stable across two natural animation turns") {
            val before = observation(scenario) ?: return@until false
            if (!before.attached || !before.windowFocused || before.width <= 0 || before.height <= 0 ||
                before.imeVisible == null || before.imeBottom == null) return@until false
            if (requireInputConnection && (!before.inputActive || !before.acceptingText)) return@until false
            val latch = java.util.concurrent.CountDownLatch(1)
            var decor: android.view.View? = null
            val second = Runnable { latch.countDown() }
            val first = Runnable { decor?.postOnAnimation(second) }
            scenario.onActivity { host ->
                decor = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!.decorView
                decor!!.postOnAnimation(first)
            }
            val observed = try {
                val remaining = deadline - SystemClock.uptimeMillis()
                remaining > 0 && latch.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
            } finally {
                scenario.onActivity { decor?.removeCallbacks(first); decor?.removeCallbacks(second) }
            }
            observed && observation(scenario) == before
        }
    }
    private fun actual(scenario: ActivityScenario<AboutActivity>, id: String, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        ownedNode(scenario, id)?.let(nodes::add) ?: return null
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser && node.isEnabled && predicate(node)) { nodes.forEach { it.recycle() }; return node }
            for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
            node.recycle()
        }
        return null
    }
    private fun click(scenario: ActivityScenario<AboutActivity>, id: String, deadline: Long = SystemClock.uptimeMillis() + 30_000) {
        var action: android.view.accessibility.AccessibilityNodeInfo? = null
        until(deadline, "Owned $id advertises CLICK") {
            action = actual(scenario, id) { it.isClickable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK } }
            action != null
        }
        try { assertTrue(action!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) }
        finally { action?.recycle() }
    }
    private fun reveal(scenario: ActivityScenario<AboutActivity>, key: String, deadline: Long = SystemClock.uptimeMillis() + 30_000) {
        until(deadline, "Visible owned bookmark field $key without changing IME") {
            ownedNode(scenario, key)?.let { it.recycle(); return@until true }
            val root = ownedNode(scenario, "outside") ?: return@until false
            val windowId = root.windowId
            var top = root
            while (true) {
                val parent = top.parent ?: break
                if (parent.windowId != windowId) { parent.recycle(); break }
                top.recycle(); top = parent
            }
            val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>(); nodes.add(top)
            var scroll: android.view.accessibility.AccessibilityNodeInfo? = null
            val direction = if (key == "text") android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (scroll == null && node.isVisibleToUser && node.isEnabled && node.isScrollable && node.actionList.any { it.id == direction }) {
                    scroll = node
                } else {
                    for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
                    node.recycle()
                }
            }
            if (scroll != null) {
                try { assertTrue(scroll.performAction(direction)) } finally { scroll.recycle() }
                frame(scenario, deadline)
            }
            false
        }
    }
    private fun input(scenario: ActivityScenario<AboutActivity>, key: String, text: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        reveal(scenario, key, deadline)
        val alreadyFocused = actual(scenario, key) { it.isEditable && it.isFocused }
        if (alreadyFocused == null) click(scenario, key, deadline) else alreadyFocused.recycle()
        fun connectionReady(): Boolean {
            var ready = false
            scenario.onActivity { host ->
                val window = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).requireDialog().window!!
                val focus = window.currentFocus
                val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                ready = focus != null && focus.isAttachedToWindow && focus.hasWindowFocus() && manager.isActive(focus) && manager.isAcceptingText
            }
            return ready
        }
        until(deadline, "Owned focused editable field and real native input connection") {
            val field = actual(scenario, key) { it.isFocused && it.isEditable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT } }
            val ready = field != null && connectionReady(); field?.recycle(); ready
        }
        frame(scenario, deadline, requireInputConnection = true)
        // A platform frame can replace its AX node. Re-read after that boundary.
        val field = actual(scenario, key) { it.isFocused && it.isEditable && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT } }
        assertNotNull("Fresh input target remains owned and ready", field)
        assertTrue("Input connection remains active before the single text action", connectionReady())
        try { assertTrue(field!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })) } finally { field?.recycle() }
        until(deadline, "Native and actual bookmark $key input ACK") {
            var same = false
            scenario.onActivity { host -> val state = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as BookmarkDialog).model.state.value
                same = (if (key == "text") state.bookText else state.content) == text }
            val node = actual(scenario, key) { it.isEditable }
            val actual = node?.let { try { it.text?.toString() == text } finally { it.recycle() } } ?: false
            same && actual
        }
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
            var node: android.view.accessibility.AccessibilityNodeInfo? = null
            until(SystemClock.uptimeMillis() + 30_000, "Owned focused note advertises selection action") {
                node = actual(scenario, "content") { it.isEditable && it.isFocused && it.actionList.any { action -> action.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION } }
                node != null
            }
            try { assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,
                android.os.Bundle().apply {
                    putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 5)
                    putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
                })) } finally { node?.recycle() }
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
            reveal(scenario, "text"); assertEquals("Draft text", driver.text("bookmark-editor-text"))
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
            click(scenario, "cancel")
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
            click(scenario, "confirm")
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
            await(scenario); click(scenario, "delete"); await(scenario, true)
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
            fun dialogPresent(): Boolean {
                var present = false
                scenario.onActivity { present = it.supportFragmentManager.findFragmentByTag("bookmark-test") != null }
                return present
            }
            fun imeVisible(): Boolean? {
                var visible: Boolean? = null
                scenario.onActivity { host ->
                    val decor = (host.supportFragmentManager.findFragmentByTag("bookmark-test") as? BookmarkDialog)?.dialog?.window?.decorView
                    if (decor != null) visible = androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                }; return visible
            }
            val deadline = SystemClock.uptimeMillis() + 30_000
            until(deadline, "Owned IME state known before real Back") { !dialogPresent() || imeVisible() != null }
            if (dialogPresent() && imeVisible() == true) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                until(deadline, "Real Back hides owned IME or cancels bookmark Dialog") { !dialogPresent() || imeVisible() == false }
            }
            if (dialogPresent()) instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await(scenario, true)
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
            click(scenario, "retry")
            await(scenario); driver.await("actual original ticket chapter") { driver.text("bookmark-editor-chapter") == bookmark.chapterName }
            click(scenario, "confirm"); await(scenario, true)
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
            click(scenario, "outside")
            await(scenario, true)
            runBlocking(Dispatchers.IO) { assertTrue(appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).isEmpty()) }
        }
    }

}
