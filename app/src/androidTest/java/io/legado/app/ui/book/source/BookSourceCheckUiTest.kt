package io.legado.app.ui.book.source

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookSourceCheckUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val group = "Check UI $id"
    private val sources =
        (0..2).map {
            BookSource(
                bookSourceUrl = "https://check.invalid/$id/$it",
                bookSourceName = "Check fixture $it",
                bookSourceGroup = if (it < 2) group else "Other $id",
                bookSourceComment = "Original comment",
            )
        }
    private val savedHelp = LocalConfig.all["bookSourceHelpVersion"]
    private val preferences = context.defaultSharedPreferences
    private val savedShowStatus = preferences.all[PreferKey.showSourceCheckStatus] as? Boolean
    private val savedBlockNavigation = preferences.all[PreferKey.blockSourceNavigation] as? Boolean
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private var archive: File? = null
    private var scenario: ActivityScenario<BookSourceActivity>? = null
    private val savedFlags =
        listOf(
            CheckSource.checkDomain,
            CheckSource.checkSearch,
            CheckSource.checkDiscovery,
            CheckSource.checkInfo,
            CheckSource.checkCategory,
            CheckSource.checkContent,
        )

    @Before
    fun setup() {
        preferences.edit().remove(PreferKey.showSourceCheckStatus).commit()
        preferences.edit().remove(PreferKey.blockSourceNavigation).commit()
        LocalConfig.edit().putInt("bookSourceHelpVersion", 1).commit()
        appDb.bookSourceDao.insert(*sources.toTypedArray())
        scenario = ActivityScenario.launch(BookSourceActivity::class.java)
    }

    @After
    fun cleanup() {
        Debug.currentCheckSession()?.let { CheckSource.stop(context, it) }
        waitUntil { !Debug.isChecking }
        scenario?.close()
        archive?.parentFile?.deleteRecursively()
        appDb.bookSourceDao.delete(*sources.toTypedArray())
        LocalConfig.edit()
            .apply {
                if (savedHelp is Int) putInt("bookSourceHelpVersion", savedHelp)
                else remove("bookSourceHelpVersion")
            }
            .commit()
        preferences
            .edit()
            .apply {
                if (savedShowStatus == null) remove(PreferKey.showSourceCheckStatus)
                else putBoolean(PreferKey.showSourceCheckStatus, savedShowStatus)
                if (savedBlockNavigation == null) remove(PreferKey.blockSourceNavigation)
                else putBoolean(PreferKey.blockSourceNavigation, savedBlockNavigation)
            }
            .commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
        CheckSource.checkDomain = savedFlags[0]
        CheckSource.checkSearch = savedFlags[1]
        CheckSource.checkDiscovery = savedFlags[2]
        CheckSource.checkInfo = savedFlags[3]
        CheckSource.checkCategory = savedFlags[4]
        CheckSource.checkContent = savedFlags[5]
    }

    @Test
    fun bookshelfCountsTrackCurrentSourcesAndShelfChangesWithoutLosingSelection() {
        val books =
            (0..4).map { index ->
                Book(
                    bookUrl = "https://count.invalid/$id/$index",
                    name = "Count $id $index",
                    origin = sources[if (index == 2) 1 else 0].bookSourceUrl,
                    type =
                        BookType.text or
                            when (index) {
                                3 -> BookType.notShelf
                                4 -> BookType.local
                                else -> 0
                            },
                )
            }
        try {
            scenario!!.onActivity { it.managerModel.query("group:$group") }
            awaitItems(sources.take(2).map { it.bookSourceUrl })
            awaitBookshelfCounts(0, 0)
            scenario!!.onActivity {
                it.managerModel.toggle(sources[0].bookSourceUrl)
            }
            appDb.bookDao.insert(*books.toTypedArray())
            awaitBookshelfCounts(2, 1)
            screenshot("source-bookshelf-two-and-one")
            books[0].origin = sources[1].bookSourceUrl
            appDb.bookDao.update(books[0])
            awaitBookshelfCounts(1, 2)
            appDb.bookDao.delete(books[1])
            awaitBookshelfCounts(0, 2)
            scenario!!.onActivity {
                val state = it.managerModel.state.value
                assertEquals(setOf(sources[0].bookSourceUrl), state.selected)
                assertEquals(
                    sources.take(2).map { source -> source.bookSourceUrl },
                    state.rows.map { row -> row.url },
                )
            }
            books[3].type = BookType.text
            appDb.bookDao.update(books[3])
            awaitBookshelfCounts(1, 2)
            books[2].type = BookType.text or BookType.notShelf
            appDb.bookDao.update(books[2])
            awaitBookshelfCounts(1, 1)
            scenario!!.onActivity { it.managerModel.query(sources[0].bookSourceUrl) }
            awaitItems(listOf(sources[0].bookSourceUrl))
            scenario!!.recreate()
            awaitItems(listOf(sources[0].bookSourceUrl))
            awaitBookshelfCounts(1)
            appDb.bookDao.delete(books[3])
            awaitBookshelfCounts(0)
            screenshot("source-bookshelf-zero-filtered-recreated")
        } finally {
            appDb.bookDao.delete(*books.toTypedArray())
        }
    }

    private fun awaitBookshelfCounts(vararg expected: Int) = waitUntil {
        var matches = false
        scenario!!.onActivity { activity ->
            val state = activity.managerModel.state.value
            matches =
                expected.indices.all { index ->
                    state.rows.getOrNull(index)?.let { row ->
                        (state.counts[row.url] ?: 0) == expected[index]
                    } == true
                }
        }
        matches
    }

    @Test
    fun statusFilterIntersectsGroupAndSurvivesRecreation() {
        val dao = appDb.bookSourceDao
        val queued = dao.beginCheck(sources.map { dao.getBookSourcePart(it.bookSourceUrl)!! })
        dao.completeCheck(queued[0], true, "", 1)
        dao.completeCheck(queued[2], true, "", 1)
        toggleStatus()
        scenario!!.onActivity {
            it.managerModel.query("group:$group")
            it.managerModel.status("PASSED")
        }
        awaitItems(listOf(sources[0].bookSourceUrl))
        screenshot("source-check-filter")
        scenario!!.recreate()
        awaitItems(listOf(sources[0].bookSourceUrl))
        scenario!!.onActivity { it.managerModel.status("NEEDS_CHECK") }
        awaitItems(listOf(sources[1].bookSourceUrl))
        screenshot("source-check-filter-needed")
        toggleStatus()
        awaitItems(sources.take(2).map { it.bookSourceUrl })
        assertStatusVisibility(false)
        screenshot("source-check-hidden")
        scenario!!.recreate()
        awaitItems(sources.take(2).map { it.bookSourceUrl })
        assertStatusVisibility(false)
    }

    @Test
    fun defaultOffStatusAndNavigationTogglesAreIncludedInRealSettingsBackup(): Unit = runBlocking {
        scenario!!.onActivity {
            it.managerModel.query("group:$group")
        }
        awaitItems(sources.take(2).map { it.bookSourceUrl })
        assertFalse(AppConfig.showSourceCheckStatus)
        assertFalse(AppConfig.blockSourceNavigation)
        scenario!!.onActivity { it.managerModel.blockNavigation() }
        waitUntil { AppConfig.blockSourceNavigation }
        assertTrue(AppConfig.blockSourceNavigation)
        assertStatusVisibility(false)
        screenshot("source-check-default-hidden")
        toggleStatus()
        assertTrue(AppConfig.showSourceCheckStatus)
        assertStatusVisibility(true)
        screenshot("source-check-shown")
        scenario!!.close()
        scenario = null
        BackupConfig.contentKeys.forEach {
            BackupConfig.ignoreConfig[it] = it != BackupConfig.settingContentKey
        }
        val backup = Backup.backupForLanTransferLocked(context).also { archive = it }
        ZipFile(backup).use { zip ->
            val entry = checkNotNull(zip.getEntry("config.xml"))
            val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            assertTrue(xml.contains("name=\"showSourceCheckStatus\" value=\"true\""))
            assertTrue(xml.contains("name=\"blockSourceNavigation\" value=\"true\""))
        }
        AppConfig.showSourceCheckStatus = false
        AppConfig.blockSourceNavigation = false
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertTrue(AppConfig.showSourceCheckStatus)
        assertTrue(AppConfig.blockSourceNavigation)
        scenario = ActivityScenario.launch(BookSourceActivity::class.java)
        waitUntil {
            var restored = false
            scenario!!.onActivity {
                restored =
                    it.managerModel.state.value.showStatus &&
                        it.managerModel.state.value.blockNavigation
            }
            restored
        }
    }

    private fun toggleStatus() {
        scenario!!.onActivity { it.managerModel.showStatus() }
        instrumentation.waitForIdleSync()
    }

    private fun assertStatusVisibility(shown: Boolean) = waitUntil {
        var matches = false
        scenario!!.onActivity { matches = it.managerModel.state.value.showStatus == shown }
        matches
    }

    @Test
    fun realServicePersistsFailureAndSuccessWithoutChangingGroups() = runBlocking {
        CheckSource.checkDomain = false
        CheckSource.checkDiscovery = false
        CheckSource.checkInfo = false
        CheckSource.checkCategory = false
        CheckSource.checkContent = false
        CheckSource.checkSearch = true // Missing search rule fails without a network request.
        val dao = appDb.bookSourceDao
        val url = sources[0].bookSourceUrl
        var session = Debug.tryStartCheckSession()!!
        CheckSource.start(context, listOf(dao.getBookSourcePart(url)!!), session)
        waitUntil { !Debug.isChecking(session) }
        assertEquals("FAILED", dao.getCheckState(url)!!.status)
        assertEquals(group, dao.getBookSource(url)!!.bookSourceGroup)
        assertEquals("Original comment", dao.getBookSource(url)!!.bookSourceComment)
        CheckSource.checkSearch = false
        session = Debug.tryStartCheckSession()!!
        CheckSource.start(context, listOf(dao.getBookSourcePart(url)!!), session)
        waitUntil { !Debug.isChecking(session) }
        assertEquals("PASSED", dao.getCheckState(url)!!.status)
    }

    private fun awaitItems(expected: List<String>) {
        waitUntil {
            var actual = emptyList<String>()
            var loaded = false
            scenario!!.onActivity {
                val state = it.managerModel.state.value
                actual = state.rows.map { row -> row.url }
                loaded = !state.loading
            }
            loaded && actual == expected
        }
        expected.forEach { key ->
            compose.onNodeWithTag("source-manager-row:$key").assertIsDisplayed()
        }
    }

    @Test
    fun composeSearchAndHiddenSelectionKeepVisibleBatchCount() {
        compose.onNodeWithTag("source-manager-search").performTextReplacement("group:$group")
        awaitItems(sources.take(2).map { it.bookSourceUrl })
        scenario!!.onActivity { it.managerModel.toggle(sources[0].bookSourceUrl) }
        compose
            .onNodeWithTag("source-manager-search")
            .performTextReplacement(sources[1].bookSourceUrl)
        awaitItems(listOf(sources[1].bookSourceUrl))
        scenario!!.onActivity {
            assertEquals(setOf(sources[0].bookSourceUrl), it.managerModel.state.value.selected)
        }
        scenario!!.recreate()
        awaitItems(listOf(sources[1].bookSourceUrl))
        scenario!!.onActivity {
            assertEquals(setOf(sources[0].bookSourceUrl), it.managerModel.state.value.selected)
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue("Condition did not become true", predicate())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val frameCommitted = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        scenario!!.onActivity { activity ->
            val decor = activity.window.decorView
            assertTrue(
                "Screenshot requires the hardware-rendered window",
                decor.isHardwareAccelerated,
            )
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            // A settled View hierarchy does not mean RenderThread has submitted its new frame.
            decor.viewTreeObserver.registerFrameCommitCallback { frameCommitted.countDown() }
            decor.postInvalidateOnAnimation()
        }
        assertTrue("Filtered frame was not committed", frameCommitted.await(5, TimeUnit.SECONDS))
        val copied = CountDownLatch(1)
        var copyResult = PixelCopy.ERROR_UNKNOWN
        scenario!!.onActivity { activity ->
            // Read the submitted window buffer, not a possibly older compositor screenshot.
            PixelCopy.request(
                activity.window,
                bitmap,
                { result ->
                    copyResult = result
                    copied.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
        }
        val directory = File(context.getExternalFilesDir(null), "ui-regression").apply { mkdirs() }
        bitmap.useBitmap {
            assertTrue("Filtered frame was not copied", copied.await(5, TimeUnit.SECONDS))
            assertEquals("Unable to capture the rendered window", PixelCopy.SUCCESS, copyResult)
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            recycle()
        }
    }
}
