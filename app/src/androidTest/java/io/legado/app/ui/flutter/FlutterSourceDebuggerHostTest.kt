package io.legado.app.ui.flutter

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.*
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.rss.source.debug.RssSourceDebugActivity
import io.legado.app.utils.GSON
import java.io.File
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Normal Android entry points, rendered Flutter semantics, real HTTP/parser/Room and owned tasks. */
class FlutterSourceDebuggerHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val driver = FlutterUiTestDriver(instrumentation)

    @Before fun before() = driver.enableAccessibility()
    @After fun after() = driver.close()

    @Test
    fun bookQueryImeSelectionAndRunningRecreationKeepOneRequestThenCloseWritesTombstone() {
        FixtureServer().use { server ->
            val source = BookSource(
                bookSourceUrl = server.url + "/book-source",
                bookSourceName = "Flutter HTTP fixture",
                enabled = false,
                searchUrl = server.url + "/search",
                ruleSearch = SearchRule(bookList = ".entry", name = "a@text", bookUrl = "a@href"),
                ruleBookInfo = BookInfoRule(name = "h1@text", tocUrl = "a@href"),
                ruleToc = TocRule(chapterList = ".chapter", chapterName = "a@text", chapterUrl = "a@href"),
                ruleContent = ContentRule(content = ".body@text"),
            )
            val directory = File(context.filesDir, "book-source-debug")
            val before = directory.listFiles().orEmpty().map { it.name }.toSet()
            var owned = emptyList<File>()
            runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
            try {
                ActivityScenario.launch<BookSourceDebugActivity>(
                    android.content.Intent(context, BookSourceDebugActivity::class.java)
                        .putExtra("key", source.bookSourceUrl)
                ).use { scenario ->
                    driver.await("book snapshot loaded") {
                        var loaded = false
                        scenario.onActivity { loaded = it.viewModel.state.value.loaded }
                        loaded
                    }
                    lateinit var root: View
                    scenario.onActivity { root = it.window.decorView }
                    driver.awaitIdentifier("source-debug-query")
                    screenshot("book-help")
                    ImeBarrier(root).use { ime ->
                        driver.setText("source-debug-query", "fixture query") { ime.settled(true) }
                        // Commit text/IME semantics before dispatching selection exactly once.
                        ime.settled(true)
                        val field = requireNotNull(driver.node("source-debug-query"))
                        try {
                            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,
                                Bundle().apply {
                                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1)
                                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 7)
                                }))
                        } finally { field.recycle() }
                        fun selectionMatches(): Boolean {
                            var matches = false
                            scenario.onActivity {
                                val state = it.viewModel.state.value
                                matches = state.query == "fixture query" && state.queryStart == 1 && state.queryEnd == 7
                            }
                            val current = driver.node("source-debug-query") ?: return false
                            try {
                                return matches && current.isFocused && current.isEditable &&
                                    current.textSelectionStart == 1 && current.textSelectionEnd == 7
                            } finally { current.recycle() }
                        }
                        try {
                            driver.await("visible focused Flutter query and native selection both 1..7", ::selectionMatches)
                            ime.settled(true)
                            assertTrue("Selection changed after the committed IME/Flutter frame", selectionMatches())
                        } catch (failure: AssertionError) {
                            val diagnostic = JSONObject()
                            scenario.onActivity {
                                val state = it.viewModel.state.value
                                diagnostic.put("nativeStart", state.queryStart).put("nativeEnd", state.queryEnd)
                            }
                            driver.node("source-debug-query")?.let { current ->
                                diagnostic.put("focused", current.isFocused).put("editable", current.isEditable)
                                    .put("visible", current.isVisibleToUser).put("start", current.textSelectionStart)
                                    .put("end", current.textSelectionEnd)
                                current.recycle()
                            }
                            val directory = File(requireNotNull(context.getExternalFilesDir(null)), "source-debugger-acceptance")
                            check(directory.isDirectory || directory.mkdirs())
                            File(directory, "book-query-selection-failure.json").writeText(diagnostic.toString(2))
                            screenshot("book-query-selection-failure")
                            throw failure
                        }
                        screenshot("book-query-ime-selection")
                        assertTrue(server.paths.isEmpty())
                        instrumentation.runOnMainSync {
                            requireNotNull(ViewCompat.getWindowInsetsController(root)).hide(WindowInsetsCompat.Type.ime())
                        }
                        ime.settled(false)
                    }
                    driver.click("source-debug-run")
                    assertTrue("HTTP search was not reached", server.entered.await(10, TimeUnit.SECONDS))
                    assertNotNull(Debug.callback)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    assertEquals(1, server.paths.count { it == "/search" })
                    assertNotNull(Debug.callback)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    scenario.recreate()
                    driver.awaitIdentifier("source-debug-query")
                    scenario.onActivity { assertTrue(it.viewModel.state.value.running) }
                    assertEquals(1, server.paths.count { it == "/search" })
                    server.release.countDown()
                    try {
                        driver.await("four stage parser completed") {
                            var done = false
                            scenario.onActivity {
                                val state = it.viewModel.state.value
                                done = !state.running && state.issue == null && state.error == null &&
                                    listOf("︽搜索页解析完成", "︽详情页解析完成", "︽目录页解析完成", "︽正文页解析完成")
                                        .all(state.output::contains)
                            }
                            done
                        }
                    } catch (failure: AssertionError) {
                        // This Activity owns only the in-process HTTP fixture. Preserve bounded
                        // diagnostics before ActivityScenario cleanup closes its private draft.
                        val diagnostic = JSONObject().put("paths", server.paths.toList())
                        scenario.onActivity {
                            val state = it.viewModel.state.value
                            diagnostic.put("running", state.running)
                                .put("issue", state.issue?.name ?: JSONObject.NULL)
                                .put("error", state.error?.take(2_000) ?: JSONObject.NULL)
                                .put("output", state.output.takeLast(6_000))
                        }
                        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "source-debugger-acceptance")
                        check(directory.isDirectory || directory.mkdirs())
                        File(directory, "book-http-failure.json").writeText(diagnostic.toString(2))
                        throw failure
                    }
                    // Dart-backed WebBook content validates non-empty content before the
                    // terminal stage; unlike RSS, Book Debug does not log the raw body.
                    awaitOutputText("︽正文页解析完成")
                    screenshot("book-completed-output")
                    assertNull(Debug.callback)
                    assertEquals(listOf("/search", "/info", "/toc", "/content"), server.paths.toList())
                    driver.await("private book checkpoint") {
                        owned = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name !in before }
                        owned.isNotEmpty()
                    }
                    assertTrue(owned.all { it.exists() })
                    driver.click("source-debug-close")
                    driver.await("closed book checkpoint and tombstone") {
                        owned.all { !it.exists() && File(it.parentFile, it.name.removeSuffix(".json") + ".closed").exists() }
                    }
                    assertNull(Debug.callback)
                }
                runBlocking(Dispatchers.IO) {
                    assertEquals(GSON.toJson(source), GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl)))
                }
            } finally {
                server.release.countDown()
                deleteOwned(owned)
                runBlocking(Dispatchers.IO) { appDb.bookSourceDao.delete(source.bookSourceUrl) }
            }
        }
    }

    @Test
    fun rssCategoryRunsHttpThenRecreationAndExplicitCloseRetainPrivateRecordWithoutRerun() {
        FixtureServer().use { server ->
            server.release.countDown()
            val source = RssSource(
                sourceUrl = server.url + "/rss-source", sourceName = "Flutter RSS fixture", enabled = false,
                sortUrl = "Fixture::${server.url}/search",
                searchUrl = server.url + "/search", ruleArticles = ".entry", ruleTitle = "a@text",
                ruleLink = "a@href", ruleContent = ".body@html",
            )
            val directory = File(context.filesDir, "rss-source-debug")
            val before = directory.listFiles().orEmpty().map { it.name }.toSet()
            var owned = emptyList<File>()
            runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(source) }
            try {
                ActivityScenario.launch<RssSourceDebugActivity>(
                    android.content.Intent(context, RssSourceDebugActivity::class.java).putExtra("key", source.sourceUrl)
                ).use { scenario ->
                    driver.await("rss categories loaded") {
                        var loaded = false
                        scenario.onActivity { loaded = it.viewModel.state.value.loaded && it.viewModel.state.value.sorts.size == 1 }
                        loaded
                    }
                    assertTrue(server.paths.isEmpty())
                    hideDebuggerIme(scenario, driver)
                    // Use the visible category chooser, then its real Flutter row.
                    driver.click("source-debug-sorts")
                    driver.awaitIdentifier("source-debug-sort-0")
                    screenshot("rss-category-chooser")
                    driver.click("source-debug-sort-0")
                    driver.await("rss fixture parsed") {
                        var done = false
                        scenario.onActivity { done = !it.viewModel.state.value.running && it.viewModel.state.value.output.contains("Fixture body") }
                        done
                    }
                    captureRssOutputDiagnostic(scenario, "rss-before-output")
                    try {
                        awaitOutputText("Fixture body")
                    } catch (failure: AssertionError) {
                        captureRssOutputDiagnostic(scenario, "rss-output-failure")
                        throw failure
                    }
                    screenshot("rss-completed-output")
                    assertNull(Debug.callback)
                    val requests = server.paths.toList()
                    assertEquals(listOf("/search", "/info"), requests)
                    var output = ""
                    scenario.onActivity { output = it.viewModel.state.value.output }
                    scenario.recreate()
                    driver.awaitIdentifier("source-debug-query")
                    scenario.onActivity {
                        assertEquals(output, it.viewModel.state.value.output)
                        assertEquals("Fixture::${server.url}/search", it.viewModel.state.value.query)
                        assertFalse(it.viewModel.state.value.running)
                    }
                    assertEquals(requests, server.paths.toList())
                    driver.await("private rss checkpoint") {
                        owned = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name !in before }
                        owned.isNotEmpty()
                    }
                    driver.click("source-debug-close")
                }
                assertNull(Debug.callback)
                assertTrue("RSS close keeps its historical private record", owned.isNotEmpty() && owned.all { it.exists() })
                assertTrue(owned.none { File(it.parentFile, it.name.removeSuffix(".json") + ".closed").exists() })
                runBlocking(Dispatchers.IO) {
                    assertEquals(GSON.toJson(source), GSON.toJson(appDb.rssSourceDao.getByKey(source.sourceUrl)))
                }
            } finally {
                deleteOwned(owned)
                runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(source.sourceUrl) }
            }
        }
    }

    @Test
    fun bookCategoryChooserStartsOwnedRunAndBackCancelsItWithoutDeletingOtherDrafts() {
        FixtureServer().use { server ->
            val source = BookSource(
                bookSourceUrl = server.url + "/category-source", bookSourceName = "Category fixture",
                exploreUrl = "Fixture::${server.url}/search", enabled = false,
                ruleExplore = ExploreRule(bookList = ".entry", name = "a@text", bookUrl = "a@href"),
            )
            val directory = File(context.filesDir, "book-source-debug")
            val before = directory.listFiles().orEmpty().map { it.name }.toSet()
            var owned = emptyList<File>()
            runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
            try {
                ActivityScenario.launch<BookSourceDebugActivity>(
                    android.content.Intent(context, BookSourceDebugActivity::class.java)
                        .putExtra("key", source.bookSourceUrl)
                ).use { scenario ->
                    driver.await("book category loaded") {
                        var loaded = false
                        scenario.onActivity { loaded = it.viewModel.state.value.loaded && it.viewModel.state.value.sorts.size == 1 }
                        loaded
                    }
                    assertTrue(server.paths.isEmpty())
                    hideDebuggerIme(scenario, driver)
                    driver.click("source-debug-sorts")
                    driver.click("source-debug-sort-0")
                    assertTrue("Category HTTP request not reached", server.entered.await(10, TimeUnit.SECONDS))
                    assertNotNull(Debug.callback)
                    scenario.onActivity {
                        assertTrue(it.viewModel.state.value.running)
                        assertEquals("Fixture::${server.url}/search", it.viewModel.state.value.query)
                    }
                    driver.await("running category checkpoint") {
                        owned = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name !in before }
                        owned.isNotEmpty()
                    }
                    // The system Back reaches the real Flutter/Android host, not a direct VM call.
                    server.allowClientDisconnect()
                    androidx.test.espresso.Espresso.pressBackUnconditionally()
                    driver.await("Back releases owned global execution") { Debug.callback == null }
                    driver.await("Back tombstones only this book debug record") {
                        owned.all { !it.exists() && File(it.parentFile, it.name.removeSuffix(".json") + ".closed").exists() }
                    }
                    assertEquals(1, server.paths.size)
                    assertTrue(before.filter { it.endsWith(".json") }.all { File(directory, it).exists() })
                }
                runBlocking(Dispatchers.IO) {
                    assertEquals(GSON.toJson(source), GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl)))
                }
            } finally {
                server.release.countDown()
                deleteOwned(owned)
                runBlocking(Dispatchers.IO) { appDb.bookSourceDao.delete(source.bookSourceUrl) }
            }
        }
    }

    private fun captureRssOutputDiagnostic(scenario: ActivityScenario<RssSourceDebugActivity>, name: String) {
        val diagnostic = JSONObject()
        scenario.onActivity {
            val state = it.viewModel.state.value
            diagnostic.put("help", state.help).put("running", state.running)
                .put("loading", state.loading).put("closed", state.closed)
                .put("outputLength", state.output.length)
        }
        val nodes = org.json.JSONArray()
        val output = driver.node("source-debug-output")
        diagnostic.put("outputIdentifierPresent", output != null)
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        output?.let(pending::add)
        if (output == null) {
            listOf("source-debug-query", "source-debug-help", "source-debug-sorts").forEach { id ->
                driver.node(id)?.let(pending::add)
            }
        }
        while (pending.isNotEmpty() && nodes.length() < 300) {
            val current = pending.removeFirst()
            val bounds = android.graphics.Rect().also(current::getBoundsInScreen)
            nodes.put(JSONObject().put("identifier", current.viewIdResourceName ?: JSONObject.NULL)
                .put("bounds", bounds.toShortString()).put("visible", current.isVisibleToUser)
                .put("className", current.className?.toString() ?: JSONObject.NULL)
                .put("focused", current.isFocused).put("scrollable", current.isScrollable)
                .put("textLength", current.text?.length ?: 0)
                .put("containsFixtureBody", current.text?.contains("Fixture body") == true ||
                    current.contentDescription?.contains("Fixture body") == true))
            for (i in 0 until current.childCount) current.getChild(i)?.let(pending::add)
            current.recycle()
        }
        pending.forEach { it.recycle() }
        diagnostic.put("nodes", nodes)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "source-debugger-acceptance")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.json").writeText(diagnostic.toString(2))
        screenshot(name)
    }

    private fun awaitOutputText(expected: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        var scrolls = 0
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (driver.containsText("source-debug-output", expected)) return
            val output = driver.node("source-debug-output")
            var scrolling: AccessibilityNodeInfo? = output
            var didScroll = false
            val descendants = java.util.ArrayDeque<AccessibilityNodeInfo>()
            output?.let { descendants.add(AccessibilityNodeInfo.obtain(it)) }
            while (descendants.isNotEmpty()) {
                val node = descendants.removeFirst()
                if (node.isVisibleToUser && node.isScrollable) {
                    assertTrue("Debugger output exceeded 24 real scrolls", scrolls < 24)
                    didScroll = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    if (didScroll) scrolls++
                }
                if (!didScroll) for (i in 0 until node.childCount) node.getChild(i)?.let(descendants::add)
                node.recycle()
                if (didScroll) break
            }
            descendants.forEach { it.recycle() }
            if (didScroll) { scrolling?.recycle(); scrolling = null }
            while (scrolling != null) {
                if (scrolling.isVisibleToUser && scrolling.isScrollable) {
                    assertTrue("Debugger output exceeded 24 real scrolls", scrolls < 24)
                    didScroll = scrolling.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    if (didScroll) scrolls++
                    scrolling.recycle()
                    break
                }
                val parent = scrolling.parent
                scrolling.recycle()
                scrolling = parent
            }
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
            if (!didScroll) android.os.SystemClock.sleep(50)
        }
        assertTrue("Completed debugger output text was not visible", driver.containsText("source-debug-output", expected))
    }

    /** Fixture-only screenshots for inspecting the actual composed Flutter/IME colors and layout. */
    private fun screenshot(name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "source-debugger-acceptance")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Debugger acceptance screenshot unavailable"
        }
        try {
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun deleteOwned(files: List<File>) {
        files.forEach { body ->
            listOf(".json", ".json.bak", ".json.new", ".closed").forEach { suffix ->
                File(body.parentFile, body.name.removeSuffix(".json") + suffix).delete()
            }
        }
    }

    private inner class ImeBarrier(private val root: View) : AutoCloseable {
        private val running = mutableSetOf<WindowInsetsAnimationCompat>()
        init {
            instrumentation.runOnMainSync {
                ViewCompat.setWindowInsetsAnimationCallback(root,
                    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) running.add(animation)
                        }
                        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>) = insets
                        override fun onEnd(animation: WindowInsetsAnimationCompat) { running.remove(animation) }
                    })
            }
        }
        fun settled(visible: Boolean) {
            driver.await("debugger IME=$visible settled") {
                var ready = false
                instrumentation.runOnMainSync {
                    ready = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == visible && running.isEmpty()
                }
                ready
            }
            val committed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                root.postInvalidateOnAnimation()
            }
            assertTrue("Debugger viewport frame not committed", committed.await(5, TimeUnit.SECONDS))
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
        }
        override fun close() {
            instrumentation.runOnMainSync { ViewCompat.setWindowInsetsAnimationCallback(root, null) }
        }
    }

    private class FixtureServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:${socket.localPort}"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paths = CopyOnWriteArrayList<String>()
        @Volatile private var disconnectAllowed = false
        @Volatile private var failure: Throwable? = null
        fun allowClientDisconnect() { disconnectAllowed = true }
        private val worker = Thread {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().bufferedReader()
                    val path = input.readLine().orEmpty().split(" ").getOrNull(1)?.substringBefore('?').orEmpty()
                    while (!input.readLine().isNullOrEmpty()) {}
                    paths += path
                    if (path == "/search") {
                        entered.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "Fixture response was not released" }
                    }
                    val html = when (path) {
                        "/search" -> "<html><div class='entry'><a href='/info'>Fixture book</a></div></html>"
                        "/info" -> "<html><h1>Fixture book</h1><a href='/toc'>Table of contents</a><div class='body'>Fixture body</div></html>"
                        "/toc" -> "<html><div class='chapter'><a href='/content'>Fixture chapter</a></div></html>"
                        "/content" -> "<html><div class='body'>Fixture body</div></html>"
                        else -> error("Unexpected fixture request: $path")
                    }
                    val body = html.toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        write(body)
                        flush()
                    }
                }
            } catch (error: SocketException) {
                if (!socket.isClosed && !disconnectAllowed) failure = error
            } catch (error: Throwable) {
                failure = error
            }
        }.apply { isDaemon = true; start() }
        override fun close() {
            disconnectAllowed = true
            release.countDown()
            socket.close()
            worker.join(5000)
            check(!worker.isAlive)
            failure?.let { throw AssertionError("Debugger fixture server failed", it) }
        }
    }
}
