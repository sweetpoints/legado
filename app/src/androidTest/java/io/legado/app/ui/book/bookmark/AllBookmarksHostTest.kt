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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AllBookmarksHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val original =
        Bookmark(
            System.currentTimeMillis() + 400000,
            "Fixture-${UUID.randomUUID()}",
            "Author",
            8,
            23,
            "Chapter",
            "Original",
            "Note",
        )
    private val sessions = mutableListOf<String>()
    private val actual = AllBookmarksFlutterRobot()
    private lateinit var scenario: ActivityScenario<AllBookmarkActivity>

    @Before
    fun setup() {
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.insert(original) }
        scenario = ActivityScenario.launch(Intent(context, AllBookmarkActivity::class.java))
        compose.waitUntil {
            var loaded = false
            scenario.onActivity { loaded = it.model.state.value.loaded }
            loaded
        }
        actual.ready()
        actual.reveal("all-bookmarks-row-${original.time}")
    }

    @After
    fun cleanup() {
        scenario.close()
        actual.close()
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.delete(original) }
        sessions.forEach { File(context.filesDir, "bookmark-editor/$it.json").delete() }
    }

    private fun editor(): BookmarkDialog {
        var found: BookmarkDialog? = null
        compose.waitUntil {
            scenario.onActivity {
                found =
                    it.supportFragmentManager.findFragmentByTag("all-bookmark-editor")
                        as? BookmarkDialog
            }
            found != null
        }
        val dialog = checkNotNull(found)
        sessions += dialog.requireArguments().getString("requestId")!!
        compose.waitUntil { dialog.model.state.value.loaded }
        return dialog
    }

    @Test
    fun missingBookClickOpensActualEditDialogWithLatestRoomMetadataAndDeleteEnabled() {
        runBlocking(Dispatchers.IO) {
            appDb.bookmarkDao.update(original.copy(chapterPos = 74, content = "Latest note"))
        }
        actual.click("all-bookmarks-row-${original.time}")
        val dialog = editor()
        assertEquals(
            74,
            runBlocking(Dispatchers.IO) {
                FileBookmarkEditorRepository(context)
                    .load(dialog.requireArguments().getString("requestId")!!)
                    .seed
                    .chapterPos
            },
        )
        actual.driver.await("Actual editor displays latest Room note") {
            actual.driver.text("bookmark-editor-content").contains("Latest note")
        }
        val delete = checkNotNull(actual.driver.node("bookmark-editor-delete"))
        try { assertTrue(delete.isEnabled) } finally { delete.recycle() }
        actual.click("bookmark-editor-cancel")
        assertEquals(
            "Latest note",
            runBlocking(Dispatchers.IO) {
                appDb.bookmarkDao.all.single { it.time == original.time }.content
            },
        )
    }

    @Test
    fun longPressOpensDialogAndRecreationKeepsItWithoutRepeatingNavigation() {
        actual.longPress("all-bookmarks-row-${original.time}")
        editor()
        scenario.recreate()
        actual.driver.awaitIdentifier("bookmark-editor-content")
        scenario.onActivity {
            assertEquals(
                1,
                it.supportFragmentManager.fragments.count { fragment ->
                    fragment is BookmarkDialog
                },
            )
            assertNull(it.model.state.value.effect)
        }
        actual.click("bookmark-editor-cancel")
        assertTrue(
            runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.any { it.time == original.time } }
        )
    }

    @Test
    fun normalFlutterEntryReflectsLiveRoomUpdateAndRecreateWithoutWritingBookmark() {
        val latest = original.copy(chapterName = "Updated chapter", content = "Fresh Room note")
        val projection = io.legado.app.data.repository.AllBookmarksRow(latest.time, latest.bookName,
            latest.bookAuthor, latest.chapterName, latest.bookText, latest.content)
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.update(latest) }
        var nativeAck = false
        var phase = "live-room-content"
        fun nativeProjectionReady(): Boolean {
            scenario.onActivity { nativeAck = it.model.state.value.rows.singleOrNull { row -> row.id == original.time } == projection }
            return nativeAck
        }
        try {
            actual.driver.await("Actual bookmark row reflects Room update") {
                nativeProjectionReady() && actual.plainLabel("all-bookmarks-content-${original.time}") == "Fresh Room note"
            }
            scenario.recreate()
            actual.ready()
            actual.reveal("all-bookmarks-row-${original.time}")
            phase = "recreated-chapter"
            actual.driver.await("Recreated actual bookmark chapter") {
                nativeProjectionReady() && actual.plainLabel("all-bookmarks-chapter-${original.time}") == "Updated chapter"
            }
            assertEquals(latest, runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.single { it.time == original.time } })
        } catch (failure: Throwable) {
            runCatching { android.util.Log.e("AllBookmarksLabel", "phase=$phase nativeProjectionAck=$nativeAck ${actual.plainLabelObservation}") }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }

    @Test
    fun flutterExportUsesNativeDirectoryContractWritesFullJsonAndMarkdownAndCancelWritesNothing() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = File(context.cacheDir, "all-bookmarks-export-${UUID.randomUUID()}").apply { mkdirs() }
        val latest = original.copy(bookText = "Full Unicode 😀\n" + "body".repeat(50000), content = "Complete note")
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.update(latest) }
        val requests = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.component?.className != io.legado.app.ui.file.HandleFileActivity::class.java.name) return null
                requests += Intent(intent)
                assertEquals(io.legado.app.ui.file.HandleFileContract.DIR, intent.getIntExtra("mode", -1))
                assertNotNull(intent.getStringExtra("value"))
                if (requests.size == 3) return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,
                    Intent().setData(android.net.Uri.fromFile(directory)).putExtra("value", intent.getStringExtra("value")))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            for ((index, format) in listOf("json", "md").withIndex()) {
                actual.click("all-bookmarks-menu"); actual.click("all-bookmarks-export-$format")
                actual.driver.await("Native $format export completes once") {
                    var settled = false
                    scenario.onActivity { settled = !it.model.state.value.exporting && it.model.state.value.effect == null && it.model.waitingDirectory() == null }
                    settled && requests.size == index + 1 && directory.listFiles().orEmpty().any { it.extension == format }
                }
            }
            val json = checkNotNull(directory.listFiles()?.single { it.extension == "json" })
            val exported = io.legado.app.utils.GSON.fromJson(json.readText(), Array<Bookmark>::class.java).single { it.time == original.time }
            assertEquals(latest, exported)
            val markdown = checkNotNull(directory.listFiles()?.single { it.extension == "md" }).readText()
            assertTrue(markdown.contains(latest.bookText)); assertTrue(markdown.contains(latest.content))
            val files = directory.listFiles().orEmpty().map { it.name }.toSet()
            actual.click("all-bookmarks-menu"); actual.click("all-bookmarks-export-json")
            actual.driver.await("Canceled directory ticket consumed without export") {
                var settled = false
                scenario.onActivity { settled = !it.model.state.value.exporting && it.model.state.value.effect == null && it.model.waitingDirectory() == null }
                settled && requests.size == 3
            }
            assertEquals(files, directory.listFiles().orEmpty().map { it.name }.toSet())
            assertEquals(latest, runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.single { it.time == original.time } })
        } finally { instrumentation.removeMonitor(monitor); directory.deleteRecursively() }
    }

    @Test
    fun actualNativeDirectoryChoicesBackCancelClearsTicketAndRecreateDoesNotRelaunch() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val launches = java.util.concurrent.atomic.AtomicInteger()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.component?.className == io.legado.app.ui.file.HandleFileActivity::class.java.name) {
                    launches.incrementAndGet()
                    assertEquals(io.legado.app.ui.file.HandleFileContract.DIR, intent.getIntExtra("mode", -1))
                    assertNotNull(intent.getStringExtra("value"))
                }
                return null // Observe only: the normal native choices Activity really opens.
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            actual.click("all-bookmarks-menu"); actual.click("all-bookmarks-export-json")
            val readyDeadline = android.os.SystemClock.uptimeMillis() + 20_000
            var readiness = "not observed"
            var readyPhase = "native-dialog-ready"
            var observedHost = java.lang.ref.WeakReference<io.legado.app.ui.file.HandleFileActivity>(null)
            var observedWindow = java.lang.ref.WeakReference<android.view.Window>(null)
            var observedCaller = java.lang.ref.WeakReference<AllBookmarkActivity>(null)
            scenario.onActivity { observedCaller = java.lang.ref.WeakReference(it) }
            fun afterBackSnapshot(): String {
                var native = "nativeHostCollected=true"
                instrumentation.runOnMainSync {
                    val host = observedHost.get()
                    val window = observedWindow.get()
                    val state = host?.choicesModel?.state?.value
                    val decor = window?.decorView
                    val insets = decor?.let(androidx.core.view.ViewCompat::getRootWindowInsets)
                    val phase = state?.phase?.takeIf { it in setOf("Choices", "Manual", "Native", "Saving", "Uploading", "Result") } ?: "unknown"
                    native = "nativeObservedUptime=${android.os.SystemClock.uptimeMillis()} nativeHostPresent=${host != null} nativeLifecycle=${host?.lifecycle?.currentState} " +
                        "nativeFinishing=${host?.isFinishing} nativeDestroyed=${host?.isDestroyed} nativeStateSaved=${host?.supportFragmentManager?.isStateSaved} " +
                        "nativeLoaded=${state?.loaded} nativeFinished=${state?.finished} nativeBusy=${state?.busy} nativePhase=$phase " +
                        "nativeRequiresRetry=${state?.requiresRetry} nativeError=${state?.error != null} nativeIssue=${state?.issue != null} " +
                        "nativeResultPresent=${state?.result != null} nativePending=${state?.pending != null} " +
                        "dialogWindowPresent=${window != null} dialogAttached=${decor?.isAttachedToWindow} dialogShown=${decor?.isShown} " +
                        "dialogFocused=${decor?.hasWindowFocus()} nativeActivityFocused=${host?.window?.decorView?.hasWindowFocus()} " +
                        "dialogImeVisible=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())} " +
                        "dialogImeBottom=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}"
                }
                var caller = "callerCollected=true"
                instrumentation.runOnMainSync {
                    val host = observedCaller.get()
                    val pickerValue = observedHost.get()?.intent?.getStringExtra("value")
                    val outstanding = host?.model?.waitingDirectory()
                    caller = "callerObservedUptime=${android.os.SystemClock.uptimeMillis()} callerPresent=${host != null} callerLifecycle=${host?.lifecycle?.currentState} " +
                        "callerFinishing=${host?.isFinishing} callerDestroyed=${host?.isDestroyed} " +
                        "callerWindowFocused=${host?.window?.decorView?.hasWindowFocus()} callerStateSaved=${host?.supportFragmentManager?.isStateSaved} " +
                        "exporting=${host?.model?.state?.value?.exporting} effect=${host?.model?.state?.value?.effect != null} " +
                        "directoryTicketPresent=${outstanding != null} nativeCallerValuePresent=${pickerValue != null} " +
                        "outstandingMatchesNativeValue=${outstanding != null && outstanding == pickerValue}"
                }
                val active = instrumentation.uiAutomation.rootInActiveWindow
                val root = try {
                    "activeRootPresent=${active != null} activePackageMatches=${active?.packageName == context.packageName} " +
                        "activeClass=${active?.className} activeWindow=${active?.windowId}"
                } finally { active?.recycle() }
                return "$native $caller $root"
            }
            fun readyDialog(): android.view.View? {
                val nodes = compose.onAllNodesWithTag("handle-file-dialog").fetchSemanticsNodes()
                val node = nodes.singleOrNull() ?: run { readiness = "dialogNodes=${nodes.size}"; return null }
                val root = node.root as? androidx.compose.ui.platform.ViewRootForTest
                    ?: run { readiness = "viewRoot=false"; return null }
                val cancel = compose.onAllNodes(hasText(root.view.context.getString(io.legado.app.R.string.cancel)) and hasClickAction())
                    .fetchSemanticsNodes().any {
                        it.root === node.root && !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                    }
                var result: android.view.View? = null
                instrumentation.runOnMainSync {
                    var owner: android.content.Context = root.view.context
                    var contexts = 0
                    while (owner is android.content.ContextWrapper && owner !is io.legado.app.ui.file.HandleFileActivity && contexts++ < 8)
                        owner = owner.baseContext
                    val picker = owner as? io.legado.app.ui.file.HandleFileActivity
                    var parent: android.view.View? = root.view
                    var window: android.view.Window? = null
                    var parents = 0
                    while (parent != null && parents++ < 32) {
                        if (parent is androidx.compose.ui.window.DialogWindowProvider) { window = parent.window; break }
                        parent = parent.parent as? android.view.View
                    }
                    val decor = window?.decorView
                    val state = picker?.choicesModel?.state?.value
                    val resumed = picker?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                    val hostReady = picker != null && !picker.isFinishing && !picker.isDestroyed && !picker.supportFragmentManager.isStateSaved
                    val ownerReady = root.isLifecycleInResumedState && !root.hasPendingMeasureOrLayout
                    val ready = resumed && hostReady && state?.loaded == true && !state.busy && state.phase == "Choices" &&
                        !state.finished && !state.requiresRetry && state.error == null && state.issue == null &&
                        ownerReady && cancel && decor != null && root.view.rootView === decor &&
                        decor.isAttachedToWindow && decor.isLaidOut && decor.isShown && decor.hasWindowFocus()
                    readiness = "picker=${picker != null} resumed=$resumed hostReady=$hostReady loaded=${state?.loaded} busy=${state?.busy} " +
                        "choices=${state?.phase == "Choices"} finished=${state?.finished} retry=${state?.requiresRetry} " +
                        "error=${state?.error != null} issue=${state?.issue != null} ownerReady=$ownerReady cancelEnabled=$cancel " +
                        "dialogWindow=${decor != null} attached=${decor?.isAttachedToWindow} laidOut=${decor?.isLaidOut} " +
                        "shown=${decor?.isShown} dialogFocused=${decor?.hasWindowFocus()} activityFocused=${picker?.window?.decorView?.hasWindowFocus()}"
                    if (ready) {
                        result = decor
                        observedHost = java.lang.ref.WeakReference<io.legado.app.ui.file.HandleFileActivity>(picker)
                        observedWindow = java.lang.ref.WeakReference<android.view.Window>(window)
                    }
                }
                return result
            }
            fun withinReady(description: String, condition: () -> Boolean) {
                val remaining = readyDeadline - android.os.SystemClock.uptimeMillis()
                check(remaining > 0) { "Original native choices readiness deadline expired" }
                try { compose.waitUntil(timeoutMillis = remaining, condition = condition) }
                catch (_: androidx.compose.ui.test.ComposeTimeoutException) { error("Timed out waiting for $description") }
            }
            try {
                var decor: android.view.View? = null
                withinReady("resumed loaded interactive owned native directory dialog") {
                    decor = readyDialog(); decor != null
                }
                readyPhase = "native-dialog-natural-frame"
                val observed = checkNotNull(decor)
                val frames = java.util.concurrent.CountDownLatch(1)
                instrumentation.runOnMainSync {
                    observed.postOnAnimation { observed.postOnAnimation { frames.countDown() } }
                }
                check(frames.await((readyDeadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    "Native dialog natural frames exceeded original readiness budget"
                }
                readyPhase = "native-dialog-fresh-before-back"
                withinReady("fresh interactive native directory dialog after natural frames") { readyDialog() === observed }
                assertEquals(1, launches.get())
                // One real Back only after the actual floating dialog is interactive.
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                // Diagnostic only: one immediate sample, plus the failure sample below.
                // No polling, extra Back, state mutation or change to the original waits.
                runCatching { android.util.Log.i("AllBookmarksPicker", "afterSingleBack ${afterBackSnapshot()}") }
                readyPhase = "parent-after-single-back"
                // This boundary still belongs to the native Compose dialog. Its
                // test frame clock must be serviced until cancellation/effects
                // return to Flutter; use the standard waiter with the same 30s
                // wall-clock budget and the unchanged actual identifier predicate.
                compose.waitUntil(timeoutMillis = 30_000) {
                    actual.driver.node("all-bookmarks-list")?.let { it.recycle(); true } == true
                }
                actual.driver.await("Native directory cancel consumes ticket without starting export") {
                    var settled = false
                    scenario.onActivity { settled = it.model.waitingDirectory() == null && !it.model.state.value.exporting && it.model.state.value.effect == null }
                    settled
                }
                scenario.recreate(); actual.ready()
                scenario.onActivity {
                    assertNull(it.model.waitingDirectory()); assertNull(it.model.state.value.effect)
                    assertFalse(it.model.state.value.exporting)
                }
                assertEquals(1, launches.get())
                assertEquals(original, runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.single { it.time == original.time } })
            } catch (failure: Throwable) {
                runCatching {
                    android.util.Log.e("AllBookmarksPicker", "phase=$readyPhase lastReady=[$readiness] ${afterBackSnapshot()}")
                }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test
    fun actualScrollRestoresSameBookmarkGeometryFromRetainedModelCheckpoint() {
        val fixtures = (1..30).map { index -> original.copy(time = original.time + index,
            chapterIndex = index + 20, chapterName = "Scroll chapter $index") }
        runBlocking(Dispatchers.IO) { appDb.bookmarkDao.insert(*fixtures.toTypedArray()) }
        try {
            val target = "all-bookmarks-row-${fixtures.last().time}"
            actual.reveal(target)
            var previous: Pair<Pair<Int, Int>, android.graphics.Rect>? = null
            var captured: Pair<Pair<Int, Int>, android.graphics.Rect>? = null
            actual.driver.await("Real scroll geometry and native checkpoint acknowledge together") {
                var checkpoint = 0 to 0
                scenario.onActivity { checkpoint = it.model.state.value.scroll to it.model.state.value.offset }
                val node = actual.driver.node(target) ?: return@await false
                val rectangle = try { android.graphics.Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
                val next = checkpoint to rectangle
                val stable = checkpoint.first > 0 && next == previous
                previous = next
                if (stable) captured = next
                stable
            }
            val before = checkNotNull(captured)
            scenario.recreate(); actual.ready()
            actual.driver.await("Recreated actual row retains the acknowledged physical scroll offset") {
                val node = actual.driver.node(target) ?: return@await false
                val rectangle = try { android.graphics.Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
                kotlin.math.abs(rectangle.top - before.second.top) <= 2 &&
                    kotlin.math.abs(rectangle.bottom - before.second.bottom) <= 2
            }
            scenario.onActivity {
                assertEquals(before.first.first, it.model.state.value.scroll)
                assertEquals(before.first.second, it.model.state.value.offset)
            }
            assertEquals(fixtures, runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.filter { it.time in fixtures.map { row -> row.time } } })
        } finally { runBlocking(Dispatchers.IO) { appDb.bookmarkDao.delete(*fixtures.toTypedArray()) } }
    }

    @Test
    fun nativeExportFailureKeepsPageAndFreshRetryUsesAnotherTicketWithoutReplay() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = File(context.cacheDir, "all-bookmarks-retry-${UUID.randomUUID()}").apply { mkdirs() }
        val notDirectory = File(directory, "not-a-directory").apply { writeText("unchanged") }
        val requests = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.component?.className != io.legado.app.ui.file.HandleFileActivity::class.java.name) return null
                requests += Intent(intent)
                val target = if (requests.size == 1) notDirectory else directory
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,
                    Intent().setData(android.net.Uri.fromFile(target)).putExtra("value", intent.getStringExtra("value")))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            actual.click("all-bookmarks-menu"); actual.click("all-bookmarks-export-json")
            actual.driver.await("Failed real FileDoc export releases only its own ticket") {
                var settled = false
                scenario.onActivity { settled = !it.isFinishing && !it.model.state.value.exporting && it.model.state.value.effect == null && it.model.waitingDirectory() == null }
                settled && requests.size == 1
            }
            assertEquals("unchanged", notDirectory.readText())
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "json" })
            scenario.recreate(); actual.ready(); assertEquals(1, requests.size)
            actual.click("all-bookmarks-menu"); actual.click("all-bookmarks-export-json")
            actual.driver.await("Fresh real export writes after failure without replaying the old effect") {
                var settled = false
                scenario.onActivity { settled = !it.model.state.value.exporting && it.model.state.value.effect == null && it.model.waitingDirectory() == null }
                settled && requests.size == 2 && directory.listFiles().orEmpty().any { it.extension == "json" }
            }
            assertNotEquals(requests[0].getStringExtra("value"), requests[1].getStringExtra("value"))
            assertEquals(original, runBlocking(Dispatchers.IO) { appDb.bookmarkDao.all.single { it.time == original.time } })
        } finally { instrumentation.removeMonitor(monitor); directory.deleteRecursively() }
    }
}
