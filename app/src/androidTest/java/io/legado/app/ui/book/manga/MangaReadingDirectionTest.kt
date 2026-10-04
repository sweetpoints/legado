package io.legado.app.ui.book.manga

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import io.legado.app.ci.closeAfterComposeExit
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.AppBrowserNavigationStore
import io.legado.app.data.repository.MangaNativeKind
import io.legado.app.data.repository.MangaNativePhase
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.readPreferenceSnapshot
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.model.ReadManga
import io.legado.app.ui.browser.BrowserNavigation
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.defaultSharedPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaReadingDirectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val accessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val source =
        BookSource(
            bookSourceUrl = "https://manga-${UUID.randomUUID()}.invalid",
            bookSourceName = "Manga direction fixture",
            bookSourceType = BookSourceType.image,
        )
    private val book =
        Book(
            bookUrl = "${source.bookSourceUrl}/book",
            tocUrl = "${source.bookSourceUrl}/toc",
            origin = source.bookSourceUrl,
            name = "Manga direction ${UUID.randomUUID()}",
            type = BookType.image,
            totalChapterNum = 3,
            durChapterIndex = 1,
            durChapterPos = 1,
            canUpdate = false,
        )
    private var scenario: ActivityScenario<ReadMangaActivity>? = null
    private var lastInput = "launch"

    @Before
    fun setUp() {
        instrumentation.uiAutomation.serviceInfo =
            instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        assertTrue(
            preferences
                .edit()
                .remove(PreferKey.mangaRightToLeft)
                .putBoolean(PreferKey.enableMangaHorizontalScroll, true)
                .putBoolean(PreferKey.hideMangaTitle, true)
                .putBoolean(PreferKey.disableClickScroll, false)
                .putBoolean(PreferKey.disableMangaScale, true)
                .putBoolean(PreferKey.disableHorizontalPageSnap, false)
                .putBoolean(PreferKey.disableMangaPageAnim, false)
                .putBoolean(PreferKey.enableMangaEInk, false)
                .putBoolean(PreferKey.enableMangaGray, false)
                .putBoolean(PreferKey.enableReadRecord, false)
                .putBoolean(PreferKey.syncBookProgress, false)
                .putBoolean(PreferKey.syncBookProgressPlus, false)
                .putInt(PreferKey.mangaPreDownloadNum, 0)
                .putInt(PreferKey.preDownloadNum, 0)
                .commit()
        )
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        repeat(3) { chapterIndex ->
            val chapter =
                BookChapter(
                    bookUrl = book.bookUrl,
                    url = "${source.bookSourceUrl}/chapter/$chapterIndex",
                    title = "Chapter ${chapterIndex + 1}",
                    index = chapterIndex,
                )
            appDb.bookChapterDao.insert(chapter)
            val content =
                (0 until 4).joinToString("\n") { pageIndex ->
                    val bitmap = Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(pageColor(chapterIndex, pageIndex))
                        val bytes =
                            ByteArrayOutputStream().use {
                                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                it.toByteArray()
                            }
                        BookHelp.writeImage(book, imageUrl(chapterIndex, pageIndex), bytes)
                    } finally {
                        bitmap.recycle()
                    }
                    "<img src=\"${imageUrl(chapterIndex, pageIndex)}\">"
                }
            assertTrue(BookHelp.saveContent(source, book, chapter, content))
        }
    }

    @After
    fun tearDown() {
        scenario?.closeAfterComposeExit(compose)
        scenario = null
        // Drain queued progress saves before removing this test's book.
        ReadManga.executor.submit {}.get(30, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            if (ReadManga.book?.bookUrl == book.bookUrl) {
                ReadManga.clearMangaChapter()
                ReadManga.book = null
                ReadManga.bookSource = null
            }
        }
        BookHelp.clearCache(book)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        assertTrue(
            preferences
                .edit()
                .clear()
                .apply {
                    savedPreferences.forEach { (key, value) -> putValue(key, value) }
                }
                .commit()
        )
        instrumentation.uiAutomation.serviceInfo =
            instrumentation.uiAutomation.serviceInfo.apply {
                flags = accessibilityFlags
            }
    }

    @Test
    fun chapterBrowserNativeActionDispatchesItsPreparedPayloadOnlyForTheCapturedReaderSession() {
        launchReader()
        val starts = CopyOnWriteArrayList<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className == WebViewActivity::class.java.name) {
                        starts += intent
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
        instrumentation.addMonitor(monitor)
        var ticket: String? = null
        val fullUrl =
            "https://manga.invalid/chapter/" + "opaque-path/".repeat(30_000) + ",{header:full}"
        try {
            scenario!!.onActivity { activity ->
                activity.viewModel.enqueueNative(MangaNativeKind.ChapterBrowser, fullUrl)
            }
            compose.waitUntil(15000) { starts.isNotEmpty() }
            assertEquals(1, starts.size)
            val intent = starts.single()
            assertEquals(setOf(BrowserNavigation.PREPARED_TICKET), intent.extras!!.keySet())
            ticket = requireNotNull(intent.getStringExtra(BrowserNavigation.PREPARED_TICKET))
            val request =
                runBlocking(Dispatchers.IO) { AppBrowserNavigationStore(context).read(ticket) }
            assertEquals(fullUrl, request.url)
            assertEquals("Chapter 2", request.title)
            assertEquals(source.bookSourceUrl, request.sourceOrigin)
            assertEquals(source.bookSourceName, request.sourceName)
            assertEquals(SourceType.book, request.sourceType)

            awaitActivity("browser action receipt accepted") { activity ->
                activity.viewModel.state.value.nativeRequests.any {
                    it.kind == MangaNativeKind.ChapterBrowser &&
                        it.imageUrl == fullUrl &&
                        it.preparedTicket == ticket &&
                        it.phase == MangaNativePhase.Complete
                }
            }
        } finally {
            ticket?.let { prepared ->
                runBlocking(Dispatchers.IO) { AppBrowserNavigationStore(context).abandon(prepared) }
            }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun loadingOldPagesCannotOverwriteRequestedChapterAndPosition() {
        launchReader()
        for (horizontal in listOf(true, false)) {
            if (!horizontal) {
                toggle(R.string.enable_manga_horizontal_scroll)
                awaitPage(2, 2)
            }
            val chapter = if (horizontal) 2 else 0
            val page = if (horizontal) 2 else 1
            scenario!!.onActivity { activity ->
                val oldItem = activity.viewModel.state.value.footerPage
                activity.viewModel.openChapter(chapter, page)
                assertTrue(activity.viewModel.state.value.loading)
                // Deliver a real stale viewport receipt while the replacement chapter is loading.
                oldItem?.let { activity.viewModel.currentItem(it) }
                assertEquals(chapter, ReadManga.durChapterIndex)
                assertEquals(page, ReadManga.durChapterPos)
            }
            awaitPage(chapter, page)
        }
        screenshot("manga-loading-preserves-requested-position")
    }

    @Test
    fun horizontalDefaultKeepsLeftToRightGesturesTapsAndKeys() {
        assertFalse(AppConfig.mangaRightToLeft)
        launchReader()
        assertLayout(horizontal = true, rightToLeft = false)
        swipe(0.85f, 0.5f, 0.15f, 0.5f)
        awaitPage(1, 2)
        swipe(0.15f, 0.5f, 0.85f, 0.5f)
        awaitPage(1, 1)
        tap(0.84f, 0.82f)
        awaitPage(1, 2)
        tap(0.16f, 0.82f)
        awaitPage(1, 1)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage(1, 1)
        assertLogicalKeys()
        screenshot("manga-direction-ltr")
    }

    @Test
    fun rightToLeftMovesRealPagesAcrossChaptersWithGesturesTapsAndKeys() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        assertLayout(horizontal = true, rightToLeft = true)
        swipe(0.15f, 0.5f, 0.85f, 0.5f)
        awaitPage(1, 2)
        swipe(0.85f, 0.5f, 0.15f, 0.5f)
        awaitPage(1, 1)
        tap(0.16f, 0.82f)
        awaitPage(1, 2)
        tap(0.84f, 0.82f)
        awaitPage(1, 1)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 1)
        assertLogicalKeys()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 3)
        swipe(0.15f, 0.5f, 0.85f, 0.5f)
        awaitPage(2, 0)
        assertLayout(horizontal = true, rightToLeft = true)
        swipe(0.85f, 0.5f, 0.15f, 0.5f)
        awaitPage(1, 3)
        chapterButton(R.string.next_chapter)
        awaitPage(2, 0)
        chapterButton(R.string.previous_chapter)
        awaitPage(1, 0)
        screenshot("manga-direction-rtl")
    }

    @Test
    fun menuToggleKeepsDisplayedPageAndRecreationAndReopenRestoreProgress() {
        launchReader()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        toggle(R.string.manga_right_to_left)
        assertTrue(AppConfig.mangaRightToLeft)
        awaitPage(1, 2)
        assertLayout(horizontal = true, rightToLeft = true)
        toggle(R.string.manga_right_to_left)
        assertFalse(AppConfig.mangaRightToLeft)
        awaitPage(1, 2)
        toggle(R.string.manga_right_to_left)
        awaitPage(1, 2)
        scenario!!.recreate()
        awaitPage(1, 2)
        assertLayout(horizontal = true, rightToLeft = true)
        scenario!!.closeAfterComposeExit(compose)
        scenario = null
        waitUntil("persisted chapter and page") {
            appDb.bookDao.getBook(book.bookUrl)?.let {
                it.durChapterIndex == 1 && it.durChapterPos == 2
            } == true
        }
        // Discard the singleton so reopening must recover the database progress.
        instrumentation.runOnMainSync {
            ReadManga.clearMangaChapter()
            ReadManga.book = null
        }
        launchReader(chapter = 1, page = 2)
        assertTrue(AppConfig.mangaRightToLeft)
        assertLayout(horizontal = true, rightToLeft = true)
    }

    @Test
    fun verticalModeKeepsForwardScrollingAndHidesDirectionOption() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        toggle(R.string.enable_manga_horizontal_scroll)
        assertLayout(horizontal = false, rightToLeft = false)
        awaitPage(1, 1)
        swipe(0.5f, 0.85f, 0.5f, 0.15f)
        awaitActivity("vertical swipe advances real content") {
            val page = visiblePage(it)
            page != null && page.chapterIndex * 4 + page.pageIndex > 5 && imageLoaded(it, page)
        }
        val forwardPage = currentPage()
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitActivity("vertical previous key moves back") {
            val page = visiblePage(it)
            page != null &&
                page.chapterIndex * 4 + page.pageIndex <
                    forwardPage.first * 4 + forwardPage.second &&
                imageLoaded(it, page)
        }
        val anchor = currentPage()
        toggle(R.string.enable_manga_horizontal_scroll)
        awaitPage(anchor.first, anchor.second)
        assertLayout(horizontal = true, rightToLeft = true)
    }

    @Test
    fun rightToLeftPagingWorksWithSnappingAndAnimationDisabled() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        toggle(R.string.disable_horizontal_page_snap)
        assertTrue(AppConfig.disableHorizontalPageSnap)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitPage(1, 1)
        toggle(R.string.disable_horizontal_page_snap)
        toggle(R.string.disable_manga_page_anim)
        assertTrue(AppConfig.disableMangaPageAnim)
        assertLogicalKeys()
        toggle(R.string.manga_right_to_left)
        awaitPage(1, 1)
        tap(0.84f, 0.82f)
        awaitPage(1, 2)
        toggle(R.string.manga_right_to_left)
        awaitPage(1, 2)
        tap(0.84f, 0.82f)
        awaitPage(1, 1)
        assertLayout(horizontal = true, rightToLeft = true)
    }

    @Test
    fun rightToLeftTraversesDefaultChapterTitleSeparatorsInBothDirections() {
        AppConfig.hideMangaTitle = false
        AppConfig.mangaRightToLeft = true
        launchReader()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 3)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitSeparator(1, 4)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitSeparator(2, -1)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(2, 0)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitSeparator(2, -1)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitSeparator(1, 4)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitPage(1, 3)
        assertLayout(horizontal = true, rightToLeft = true)
        screenshot("manga-direction-chapter-separators")
    }

    @Test
    fun settingsBackupRestoresDirectionAndLegacyBackupUsesLeftToRight() {
        val directory = File(context.cacheDir, "manga-direction-backup-${UUID.randomUUID()}")
        val oldIgnoreConfig = HashMap(BackupConfig.ignoreConfig)
        try {
            BackupConfig.ignoreConfig.clear()
            AppConfig.mangaRightToLeft = true
            writePreferenceSnapshot(context, directory.path, "config") {
                preferences.all.forEach { (key, value) -> putValue(key, value) }
            }
            assertEquals(
                true,
                readPreferenceSnapshot(context, directory.path, "config")
                    ?.get(PreferKey.mangaRightToLeft),
            )
            AppConfig.mangaRightToLeft = false
            BackupConfig.ignoreConfig["readConfig"] = true
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertFalse(AppConfig.mangaRightToLeft)
            BackupConfig.ignoreConfig.remove("readConfig")
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertTrue(AppConfig.mangaRightToLeft)
            launchReader()
            assertLayout(horizontal = true, rightToLeft = true)
            scenario!!.closeAfterComposeExit(compose)
            scenario = null
            writePreferenceSnapshot(context, directory.path, "config") {
                preferences.all
                    .filterKeys { it != PreferKey.mangaRightToLeft }
                    .forEach { (key, value) -> putValue(key, value) }
            }
            BackupConfig.ignoreConfig["readConfig"] = true
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertTrue(AppConfig.mangaRightToLeft)
            BackupConfig.ignoreConfig.remove("readConfig")
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertFalse(AppConfig.mangaRightToLeft)
            launchReader()
            assertLayout(horizontal = true, rightToLeft = false)
        } finally {
            BackupConfig.ignoreConfig.clear()
            BackupConfig.ignoreConfig.putAll(oldIgnoreConfig)
            directory.deleteRecursively()
        }
    }

    @Test
    fun progressTrackAndChapterButtonsFollowTheSelectedDirection() {
        launchReader()
        listOf(false, true).forEach { rightToLeft ->
            if (AppConfig.mangaRightToLeft != rightToLeft) {
                toggle(R.string.manga_right_to_left)
            }
            scenario!!.onActivity { it.viewModel.setMenu(true) }
            compose.waitForIdle()
            val previous =
                compose.onNodeWithTag("manga-previous-chapter").fetchSemanticsNode().boundsInRoot
            val next = compose.onNodeWithTag("manga-next-chapter").fetchSemanticsNode().boundsInRoot
            assertTrue(if (rightToLeft) next.left < previous.left else previous.left < next.left)
            assertTrue("Chapter controls must leave a usable progress slider", compose.onNodeWithTag("manga-progress").fetchSemanticsNode().boundsInRoot.width >= 48f)
            seekAtEdge(left = true)
            scenario!!.onActivity { it.viewModel.setMenu(false) }
            waitUntil("progress menu is actually removed before checking viewport pixels") {
                compose.onAllNodesWithTag("manga-progress")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            }
            awaitPage(1, if (rightToLeft) 3 else 0)
            scenario!!.onActivity { it.viewModel.setMenu(true) }
            seekAtEdge(left = false)
            scenario!!.onActivity { it.viewModel.setMenu(false) }
            waitUntil("progress menu is actually removed before checking viewport pixels") {
                compose.onAllNodesWithTag("manga-progress")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            }
            awaitPage(1, if (rightToLeft) 0 else 3)
            scenario!!.onActivity { it.viewModel.setMenu(false) }
        }
    }

    @Test
    fun rightToLeftBookBoundariesKeepFirstAndLastImageProgress() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        moveTo(0, 0)
        key(KeyEvent.KEYCODE_PAGE_UP)
        swipe(0.85f, 0.5f, 0.15f, 0.5f)
        awaitPage(0, 0)
        scenario!!.onActivity { assertEquals(0, it.viewModel.state.value.pageIndex) }

        moveTo(2, 3)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        var footerVisible = false
        awaitActivity("last image or existing end-of-book footer") { activity ->
            footerVisible = endFooterVisible(activity)
            val page = visiblePage(activity)
            ReadManga.durChapterIndex == 2 &&
                ReadManga.durChapterPos == 3 &&
                (footerVisible ||
                    page?.let {
                        it.chapterIndex == 2 && it.pageIndex == 3 && imageLoaded(activity, it)
                    } == true)
        }
        if (footerVisible) {
            repeat(2) {
                key(KeyEvent.KEYCODE_PAGE_DOWN)
                awaitActivity("end-of-book footer does not advance beyond the last image") {
                    endFooterVisible(it) &&
                        ReadManga.durChapterIndex == 2 &&
                        ReadManga.durChapterPos == 3
                }
            }
            key(KeyEvent.KEYCODE_PAGE_UP)
        }
        awaitPage(2, 3)
        scenario!!.onActivity { ReadManga.saveRead() }
        waitUntil("last image progress persisted without an out-of-range chapter") {
            appDb.bookDao.getBook(book.bookUrl)?.let {
                it.durChapterIndex == 2 && it.durChapterPos == 3
            } == true
        }
    }

    @Test
    fun actualPortraitAndLandscapeRotationKeepImageAndReadingDirection() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario!!.onActivity { originalOrientation = it.requestedOrientation }
        try {
            listOf(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to
                        Configuration.ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                )
                .forEach { (requested, expected) ->
                    scenario!!.onActivity { it.requestedOrientation = requested }
                    awaitActivity("actual orientation $expected and resized reader") {
                        val recycler = it.window.decorView
                        it.resources.configuration.orientation == expected &&
                            if (expected == Configuration.ORIENTATION_LANDSCAPE) {
                                recycler.width > recycler.height
                            } else {
                                recycler.height > recycler.width
                            }
                    }
                    awaitPage(1, 2)
                    assertLayout(horizontal = true, rightToLeft = true)
                    assertTrue(AppConfig.mangaRightToLeft)
                    screenshot("manga-direction-rotation-$expected")
                }
        } finally {
            scenario?.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    @Test
    fun menuAutoPageAndContinuousAutoScrollAdvanceRightToLeftImages() {
        AppConfig.mangaRightToLeft = true
        AppConfig.mangaAutoPageSpeed = 1
        launchReader()
        val afterAutoPage = advanceAutomatically(R.string.enable_auto_page_scroll)
        assertLayout(horizontal = true, rightToLeft = true)
        scenario!!.closeAfterComposeExit(compose)
        scenario = null
        ReadManga.executor.submit {}.get(30, TimeUnit.SECONDS)

        // The activity initializes its timer from this setting, in pixels per 16 ms for scrolling.
        AppConfig.mangaAutoPageSpeed = 16
        launchReader(afterAutoPage.first, afterAutoPage.second)
        advanceAutomatically(R.string.enable_auto_scroll)
        assertLayout(horizontal = true, rightToLeft = true)
        screenshot("manga-direction-auto-scroll")
    }

    private fun moveTo(chapter: Int, page: Int) {
        scenario!!.onActivity {
            ReadManga.setProgress(
                BookProgress(book)
                    .copy(
                        durChapterIndex = chapter,
                        durChapterPos = page,
                    )
            )
        }
        awaitPage(chapter, page)
    }

    private fun endFooterVisible(activity: ReadMangaActivity): Boolean = runCatching {
        val node = compose.onNodeWithText("暂无章节了！").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("manga-viewport").fetchSemanticsNode().boundsInRoot
        node.left <= viewport.center.x && node.right > viewport.center.x
    }
        .getOrDefault(false)

    private fun advanceAutomatically(menuId: Int): Pair<Int, Int> {
        val start = currentPage()
        var advanced: Pair<Int, Int>? = null
        val viewport = compose.onNodeWithTag("manga-viewport").fetchSemanticsNode().boundsInRoot
        val origin = IntArray(2)
        scenario!!.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val pixelX = origin[0] + viewport.center.x.toInt()
        val pixelY = origin[1] + viewport.center.y.toInt()
        scenario!!.onActivity { setAutomaticMenu(it, menuId, true) }
        try {
            // Continuous scrolling never becomes idle; stop from the same main-thread observation.
            waitUntil("automatic menu $menuId advances to a loaded image", captureOnFailure = false) {
                var activity: ReadMangaActivity? = null
                scenario!!.onActivity { activity = it }
                val host = checkNotNull(activity)
                val page = visiblePage(host)
                if (
                    page != null &&
                        page.chapterIndex * 4 + page.pageIndex > start.first * 4 + start.second &&
                        screenshotPixelMatches(page, pixelX, pixelY)
                ) {
                    advanced = page.chapterIndex to page.pageIndex
                    scenario!!.onActivity { setAutomaticMenu(it, menuId, false) }
                }
                advanced != null
            }
        } finally {
            scenario?.onActivity { setAutomaticMenu(it, menuId, false) }
        }
        return checkNotNull(advanced).also { (chapter, page) -> awaitPage(chapter, page) }
    }

    private fun screenshotPixelMatches(page: MangaReaderItem.Page, x: Int, y: Int): Boolean {
        // Continuous scrolling cannot become Compose-idle. Read actual display pixels directly.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
        return try {
            val actual = bitmap.getPixel(x, y)
            val expected = pageColor(page.chapterIndex, page.pageIndex)
            abs(Color.red(actual) - Color.red(expected)) <= 4 &&
                abs(Color.green(actual) - Color.green(expected)) <= 4 &&
                abs(Color.blue(actual) - Color.blue(expected)) <= 4
        } finally {
            bitmap.recycle()
        }
    }

    private fun setAutomaticMenu(activity: ReadMangaActivity, id: Int, enabled: Boolean) {
        val page = id == R.string.enable_auto_page_scroll
        val state = activity.viewModel.state.value
        if ((if (page) state.autoPage else state.autoScroll) != enabled) {
            activity.viewModel.setAutomaticPaging(page)
        }
        assertEquals(
            enabled,
            if (page) activity.viewModel.state.value.autoPage
            else activity.viewModel.state.value.autoScroll,
        )
    }

    private fun launchReader(chapter: Int = 1, page: Int = 1) {
        scenario =
            ActivityScenario.launch(
                Intent(context, ReadMangaActivity::class.java).putExtra("bookUrl", book.bookUrl)
            )
        awaitPage(chapter, page)
        awaitActivity("adjacent cached chapters loaded") {
            it.viewModel.state.value.items
                .filterIsInstance<MangaReaderItem.Page>()
                .map { page -> page.chapterIndex }
                .containsAll((chapter - 1..chapter + 1).filter { index -> index in 0..2 })
        }
        // Android displays its first-use fullscreen confirmation after the reader has loaded.
        // Acknowledge that real system UI before injecting page keys or gestures underneath it.
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            val confirmation =
                root.findAccessibilityNodeInfosByViewId("android:id/ok").firstOrNull {
                    it.isClickable
                } ?: root.findAccessibilityNodeInfosByText("Got it").firstOrNull { it.isClickable }
            confirmation?.let {
                assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
        }
        awaitActivity("reader focused after fullscreen confirmation") { it.hasWindowFocus() }
    }

    private fun assertLogicalKeys() {
        listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE)
            .forEach { nextKey ->
                key(nextKey)
                awaitPage(1, 2)
                key(KeyEvent.KEYCODE_VOLUME_UP)
                awaitPage(1, 1)
            }
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitPage(1, 1)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_UP)
        awaitPage(1, 1)
    }

    private fun assertLayout(horizontal: Boolean, rightToLeft: Boolean) {
        scenario!!.onActivity { activity ->
            val state = activity.viewModel.state.value
            assertEquals(horizontal, state.settings.horizontal)
            assertEquals(rightToLeft, state.settings.horizontal && state.settings.rightToLeft)
            val pages =
                state.items.filterIsInstance<MangaReaderItem.Page>().map {
                    it.chapterIndex * 4 + it.pageIndex
                }
            assertEquals("Compose items retain logical chapter/page order", pages.sorted(), pages)
        }
        compose.onNodeWithTag("manga-viewport").fetchSemanticsNode()
    }

    private fun toggle(id: Int) {
        val label =
            when (id) {
                R.string.enable_manga_horizontal_scroll -> R.string.enable_manga_horizontal_scroll
                R.string.manga_right_to_left -> R.string.manga_right_to_left
                R.string.disable_horizontal_page_snap -> R.string.disable_horizontal_page_snap
                R.string.disable_manga_page_anim -> R.string.disable_manga_page_anim
                else -> error("Missing Compose menu mapping: $id")
            }
        var expected = false
        scenario!!.onActivity {
            val settings = it.viewModel.state.value.settings
            expected =
                !when (id) {
                    R.string.enable_manga_horizontal_scroll -> settings.horizontal
                    R.string.manga_right_to_left -> settings.rightToLeft
                    R.string.disable_horizontal_page_snap -> settings.disableSnap
                    else -> settings.disablePageAnimation
                }
            it.viewModel.setMenu(true)
        }
        compose.onNodeWithText(context.getString(R.string.menu)).performClick()
        compose.onNodeWithText(context.getString(label)).performClick()
        scenario!!.onActivity { it.viewModel.setMenu(false) }
        waitUntil("setting committed") {
            var matched = false
            scenario!!.onActivity {
                val settings = it.viewModel.state.value.settings
                matched =
                    when (id) {
                        R.string.enable_manga_horizontal_scroll ->
                            settings.horizontal == expected &&
                                AppConfig.enableMangaHorizontalScroll == expected
                        R.string.manga_right_to_left ->
                            settings.rightToLeft == expected &&
                                AppConfig.mangaRightToLeft == expected
                        R.string.disable_horizontal_page_snap ->
                            settings.disableSnap == expected &&
                                AppConfig.disableHorizontalPageSnap == expected
                        else ->
                            settings.disablePageAnimation == expected &&
                                AppConfig.disableMangaPageAnim == expected
                    }
            }
            matched
        }
    }

    private fun chapterButton(id: Int) {
        scenario!!.onActivity { it.viewModel.setMenu(true) }
        compose
            .onNodeWithTag(
                if (id == R.string.previous_chapter) "manga-previous-chapter"
                else "manga-next-chapter"
            )
            .performClick()
        scenario!!.onActivity { it.viewModel.setMenu(false) }
    }

    private fun key(code: Int) {
        awaitActivity("reader window focused before key") { it.hasWindowFocus() }
        lastInput = KeyEvent.keyCodeToString(code)
        instrumentation.sendKeyDownUpSync(code)
    }

    private fun seekAtEdge(left: Boolean) {
        val bounds = compose.onNodeWithTag("manga-progress").fetchSemanticsNode().boundsInRoot
        scenario!!.onActivity {
            lastInput = "seek left=$left bounds=$bounds before=${it.viewModel.state.value.chapterIndex}/${it.viewModel.state.value.pageIndex}"
        }
        compose.onNodeWithTag("manga-progress").performTouchInput {
            // Slider semantics extend into the thumb's hit area beside chapter buttons.
            // Tap inside its track, retaining real touch dispatch and first/last-page assertions.
            click(Offset(width * if (left) .1f else .9f, height / 2f))
        }
        scenario!!.onActivity {
            lastInput += "; after=${it.viewModel.state.value.chapterIndex}/${it.viewModel.state.value.pageIndex}; command=${it.viewModel.state.value.scrollCommand}"
        }
    }

    private fun tap(x: Float, y: Float) = touch(x, y, x, y, steps = 0)

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) =
        touch(fromX, fromY, toX, toY, steps = 20)

    private fun touch(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int) {
        awaitActivity("reader window focused before touch") { it.hasWindowFocus() }
        lastInput = "touch $fromX,$fromY to $toX,$toY"
        val location = IntArray(2)
        var width = 0
        var height = 0
        scenario!!.onActivity {
            it.window.decorView.getLocationOnScreen(location)
            width = it.window.decorView.width
            height = it.window.decorView.height
        }
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    location[0] + width * (fromX + (toX - fromX) * fraction),
                    location[1] + height * (fromY + (toY - fromY) * fraction),
                    0,
                )
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        repeat(steps) { step ->
            SystemClock.sleep(20)
            send(MotionEvent.ACTION_MOVE, (step + 1f) / steps)
        }
        SystemClock.sleep(20)
        send(MotionEvent.ACTION_UP, 1f)
    }

    private fun visiblePage(activity: ReadMangaActivity): MangaReaderItem.Page? =
        activity.viewModel.state.value.footerPage

    private fun imageLoaded(activity: ReadMangaActivity, page: MangaReaderItem.Page): Boolean {
        // Inspect actual Compose-rendered pixels rather than accepting only an engine receipt.
        return runCatching {
                val pixels = compose.onNodeWithTag("manga-viewport").captureToImage().toPixelMap()
                val actual = pixels[pixels.width / 2, pixels.height / 2]
                val expected = pageColor(page.chapterIndex, page.pageIndex)
                abs(actual.red * 255 - Color.red(expected)) <= 4 &&
                    abs(actual.green * 255 - Color.green(expected)) <= 4 &&
                    abs(actual.blue * 255 - Color.blue(expected)) <= 4
            }
            .getOrDefault(false)
    }

    private fun currentPage(): Pair<Int, Int> {
        var page: MangaReaderItem.Page? = null
        scenario!!.onActivity { page = visiblePage(it) }
        return checkNotNull(page).let { it.chapterIndex to it.pageIndex }
    }

    private fun awaitPage(chapter: Int, index: Int) {
        awaitActivity("displayed chapter $chapter page $index and matching reader position") {
            activity ->
            val page = visiblePage(activity)
            ReadManga.book?.bookUrl == book.bookUrl &&
                ReadManga.durChapterIndex == chapter &&
                ReadManga.durChapterPos == index &&
                page?.chapterIndex == chapter &&
                page.pageIndex == index &&
                imageLoaded(activity, page)
        }
    }

    private fun awaitSeparator(chapter: Int, index: Int) {
        waitUntil("chapter $chapter separator $index") {
            runCatching {
                    val node =
                        compose
                            .onNodeWithTag("manga-item:$chapter:$index")
                            .fetchSemanticsNode()
                            .boundsInRoot
                    val viewport =
                        compose.onNodeWithTag("manga-viewport").fetchSemanticsNode().boundsInRoot
                    node.left <= viewport.center.x && node.right > viewport.center.x
                }
                .getOrDefault(false)
        }
    }

    private fun awaitActivity(message: String, condition: (ReadMangaActivity) -> Boolean) {
        waitUntil(message) {
            var activity: ReadMangaActivity? = null
            scenario!!.onActivity { activity = it }
            condition(checkNotNull(activity))
        }
    }

    private fun waitUntil(message: String, captureOnFailure: Boolean = true, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.mainClock.advanceTimeByFrame()
                condition()
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Preserve the original state diagnostics and failure assertion below.
        }
        var state = "activity closed"
        if (scenario != null) {
            if (captureOnFailure) screenshot("manga-direction-failure-${testName.methodName}")
            scenario!!.onActivity { activity ->
                state =
                    "focus=${activity.hasWindowFocus()}, state=${activity.viewModel.state.value}"
            }
        }
        assertTrue(
            "Manga direction did not reach $message; reader is " +
                "${ReadManga.durChapterIndex}/${ReadManga.durChapterPos}; last=$lastInput; $state",
            condition(),
        )
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageUrl(chapter: Int, page: Int) =
        "${source.bookSourceUrl}/images/$chapter-$page.png"

    private fun pageColor(chapter: Int, page: Int) =
        Color.rgb(40 + chapter * 60, 50 + page * 40, 90)

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> remove(key)
        }
    }
}
