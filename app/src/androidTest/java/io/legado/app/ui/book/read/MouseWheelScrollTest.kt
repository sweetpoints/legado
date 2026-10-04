package io.legado.app.ui.book.read

import io.legado.app.ci.lazyItem
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Observer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.eventObservable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MouseWheelScrollTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences =
        listOf(
                PreferKey.mouseWheelPage,
                PreferKey.mouseWheelScrollSpeed,
                PreferKey.pageTouchSlop,
            )
            .associateWith {
                preferences.all[it]
            }
    private lateinit var file: File
    private lateinit var book: Book
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var upConfigObserver: Observer<ArrayList<Int>>? = null
    // This is the offset used by ContentTextView.drawPage, not a duplicate speed calculation.
    private val pageOffset =
        ContentTextView::class.java.getDeclaredField("pageOffset").apply { isAccessible = true }

    @Before
    fun setUp() {
        assertTrue(
            preferences
                .edit()
                .putBoolean(PreferKey.mouseWheelPage, true)
                .putInt(PreferKey.pageTouchSlop, 11)
                .remove(PreferKey.mouseWheelScrollSpeed)
                .commit()
        )
        file = File.createTempFile("mouse-wheel-", ".txt", context.cacheDir)
        file.writeText(
            (0 until 300).joinToString("\n") {
                "Line $it: deterministic local reader text for mouse wheel scrolling."
            }
        )
        book =
            Book(
                bookUrl = file.absolutePath,
                originName = file.name,
                name = file.name,
                charset = "UTF-8",
                type = BookType.local or BookType.text,
                totalChapterNum = 1,
            )
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(
            BookChapter(
                bookUrl = book.bookUrl,
                url = "wheel-chapter",
                title = "Mouse wheel fixture",
                start = 0L,
                end = file.length(),
            )
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
        upConfigObserver?.let { observer ->
            instrumentation.runOnMainSync {
                eventObservable<ArrayList<Int>>(EventBus.UP_CONFIG).removeObserver(observer)
            }
        }
        upConfigObserver = null
        TextFile.clear()
        if (::book.isInitialized) appDb.bookDao.delete(book)
        if (::file.isInitialized) file.delete()
        val editor = preferences.edit()
        savedPreferences.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        assertTrue(editor.commit())
    }

    @Test
    fun defaultHalfAndDoubleSpeedMoveRealContentAndSwitchStopsMovement() {
        launchReader(PageAnim.scrollPageAnim)
        assertEquals(100, AppConfig.mouseWheelScrollSpeed)
        assertScrollDelta(-50) { dispatchScroll(it, -1f) }
        setSpeed(50)
        assertScrollDelta(-25) { dispatchScroll(it, -1f) }
        setSpeed(200)
        assertScrollDelta(-100) { dispatchScroll(it, -1f) }
        assertScrollDelta(100) { dispatchScroll(it, 1f) }
        assertTrue(preferences.edit().putBoolean(PreferKey.mouseWheelPage, false).commit())
        assertScrollDelta(0) { dispatchScroll(it, -1f) }
        assertTrue(preferences.edit().putBoolean(PreferKey.mouseWheelPage, true).commit())
        assertScrollDelta(-100) { dispatchScroll(it, -1f) }
        setSpeed(1)
        assertScrollDelta(-5) { dispatchScroll(it, -1f) }
        setSpeed(500)
        assertScrollDelta(-200) { dispatchScroll(it, -1f) }
    }

    @Test
    fun rotaryUsesScrollAxisAndInvalidOrHorizontalEventsDoNotMoveContent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        launchReader(PageAnim.scrollPageAnim)
        assertScrollDelta(-50) { dispatchScroll(it, -1f) }
        setSpeed(200)
        assertScrollDelta(-100) {
            dispatchScroll(it, -1f, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL)
        }
        assertScrollDelta(0) { activity ->
            dispatchScroll(activity, 0f)
            dispatchScroll(activity, -1f, axis = MotionEvent.AXIS_HSCROLL)
            dispatchScroll(activity, Float.NaN)
            dispatchScroll(activity, Float.POSITIVE_INFINITY)
            dispatchScroll(
                activity,
                -1f,
                InputDevice.SOURCE_ROTARY_ENCODER,
                MotionEvent.AXIS_VSCROLL,
            )
        }
        assertTrue(preferences.edit().putBoolean(PreferKey.mouseWheelPage, false).commit())
        assertScrollDelta(0) {
            dispatchScroll(it, -1f, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL)
        }
    }

    @Test
    fun pagedModeKeepsOnePagePerDebouncedBurstAtDifferentSpeeds() {
        launchReader(PageAnim.noAnim)
        listOf(50, 100, 200, 400).forEach { speed ->
            setSpeed(speed)
            val before = currentPage()
            scenario!!.onActivity { activity ->
                repeat(3) { dispatchScroll(activity, -1f) }
                assertEquals(
                    "Wheel paging remains delayed",
                    before,
                    activity.readerView.curPage.textPage.index,
                )
            }
            awaitReader { it.readerView.curPage.textPage.index == before + 1 }
            SystemClock.sleep(350)
            assertEquals("Speed $speed must not multiply page turns", before + 1, currentPage())
        }
        val before = currentPage()
        scenario!!.onActivity {
            dispatchScroll(it, 0f)
            dispatchScroll(it, -1f, axis = MotionEvent.AXIS_HSCROLL)
            dispatchScroll(it, Float.NaN)
        }
        SystemClock.sleep(350)
        assertEquals("Ignored axes must not accidentally turn backwards", before, currentPage())
        scenario!!.onActivity { dispatchScroll(it, 1f) }
        awaitReader { it.readerView.curPage.textPage.index == before - 1 }
    }

    @Test
    fun composeSpeedControlPersistsAcrossRecreationAndFollowsTheWheelSwitch() {
        launchReader(PageAnim.scrollPageAnim)
        scenario!!.onActivity {
            MoreConfigDialog().showNow(it.supportFragmentManager, "mouse-wheel-settings")
        }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithTag("more-reader-settings-list")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.lazyItem("more-reader-settings-list", "more-reader-setting-${PreferKey.mouseWheelScrollSpeed}")
        compose
            .onNodeWithTag("more-reader-slider-${PreferKey.mouseWheelScrollSpeed}")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(200f)) }
        awaitReader { AppConfig.mouseWheelScrollSpeed == 200 }
        assertEquals(200, preferences.getInt(PreferKey.mouseWheelScrollSpeed, 0))
        screenshot("mouse-wheel-speed-settings")
        scenario!!.recreate()
        awaitReader { it.bottomDialog == 1 && AppConfig.mouseWheelScrollSpeed == 200 }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithTag("more-reader-settings-list")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.lazyItem("more-reader-settings-list", "more-reader-setting-${PreferKey.mouseWheelScrollSpeed}")
        assertEquals(200, AppConfig.mouseWheelScrollSpeed)
        compose.lazyItem("more-reader-settings-list", "more-reader-setting-${PreferKey.mouseWheelPage}")
            .performClick()
        awaitReader { !AppConfig.mouseWheelPage }
        compose
            .onNodeWithTag("more-reader-slider-${PreferKey.mouseWheelScrollSpeed}")
            .assertIsNotEnabled()
        compose.onNodeWithTag("more-reader-setting-${PreferKey.mouseWheelPage}").performClick()
        awaitReader { AppConfig.mouseWheelPage }
        scenario!!.onActivity {
            (it.supportFragmentManager.findFragmentByTag("mouse-wheel-settings")
                    as MoreConfigDialog)
                .dismissNow()
        }
        awaitReader { it.bottomDialog == 0 && !it.readerView.curPage.textPage.isMsgPage }
        assertScrollDelta(-100) { dispatchScroll(it, -1f) }
    }

    @Test
    fun numberPickerSelectionIsFencedToTheReaderDialogThatOpenedIt() {
        launchReader(PageAnim.scrollPageAnim)
        val receivedConfigurationEvents = CopyOnWriteArrayList<ArrayList<Int>>()
        val eventReceived = CountDownLatch(1)
        val eventObserver =
            Observer<ArrayList<Int>> { event ->
                if (event == arrayListOf(4)) {
                    receivedConfigurationEvents += event
                    eventReceived.countDown()
                }
            }
        upConfigObserver = eventObserver
        instrumentation.runOnMainSync {
            eventObservable<ArrayList<Int>>(EventBus.UP_CONFIG).observeForever(eventObserver)
        }
        scenario!!.onActivity {
            MoreConfigDialog().showNow(it.supportFragmentManager, "number-owner-settings")
        }
        awaitReader { it.bottomDialog == 1 }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithTag("more-reader-settings-list")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        compose.lazyItem("more-reader-settings-list", "more-reader-setting-${PreferKey.pageTouchSlop}")
            .performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("number-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("number-input").performScrollTo().performTextReplacement("57")
        val staleConfirmAction =
            compose
                .onNodeWithTag("number-confirm")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!

        scenario!!.recreate()
        awaitReader { it.bottomDialog == 1 }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithTag("more-reader-settings-list")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("number-input").assertDoesNotExist()
        instrumentation.runOnMainSync {
            assertTrue("Captured old picker confirm", staleConfirmAction())
        }
        SystemClock.sleep(300)
        assertEquals(
            "A disposed picker cannot change the preference",
            11,
            preferences.getInt(PreferKey.pageTouchSlop, 0),
        )
        assertTrue(
            "A disposed picker cannot post reader configuration",
            receivedConfigurationEvents.isEmpty(),
        )

        compose.lazyItem("more-reader-settings-list", "more-reader-setting-${PreferKey.pageTouchSlop}")
            .performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("number-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("number-input").performScrollTo().performTextReplacement("42")
        compose.onNodeWithTag("number-confirm").performScrollTo().performClick()
        awaitReader { preferences.getInt(PreferKey.pageTouchSlop, 0) == 42 }
        assertEquals(42, AppConfig.pageTouchSlop)
        assertTrue(
            "The current picker emits the original reader update",
            eventReceived.await(5, TimeUnit.SECONDS),
        )
        assertEquals(listOf(arrayListOf(4)), receivedConfigurationEvents.toList())

        scenario!!.onActivity {
            (it.supportFragmentManager.findFragmentByTag("number-owner-settings")
                    as MoreConfigDialog)
                .dismissNow()
        }
        awaitReader { it.bottomDialog == 0 }
    }

    private fun launchReader(pageAnim: Int) {
        book.setPageAnim(pageAnim)
        appDb.bookDao.update(book)
        scenario =
            ActivityScenario.launch(
                Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)
            )
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments
                .filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.dismiss() }
        }
        awaitReader {
            val page = it.readerView.curPage.textPage
            ReadBook.book?.bookUrl == book.bookUrl &&
                ReadBook.curTextChapter?.isCompleted == true &&
                !page.isMsgPage &&
                page.lineSize > 0 &&
                page.height > 400 &&
                it.bottomDialog == 0
        }
        scenario!!.onActivity { assertEquals(pageAnim == PageAnim.scrollPageAnim, it.isScroll) }
    }

    private val ReadBookActivity.readerView: ReadView
        get() = findViewById(R.id.read_view)

    private fun setSpeed(speed: Int) {
        assertTrue(preferences.edit().putInt(PreferKey.mouseWheelScrollSpeed, speed).commit())
    }

    private fun assertScrollDelta(expected: Int, input: (ReadBookActivity) -> Unit) {
        scenario!!.onActivity { activity ->
            val page = activity.readerView.curPage
            val text = page.findViewById<ContentTextView>(R.id.content_text_view)
            val beforeOffset = pageOffset.getInt(text)
            val beforePage = page.textPage.index
            input(activity)
            assertEquals(
                "Rendered content displacement",
                expected,
                pageOffset.getInt(text) - beforeOffset,
            )
            assertEquals(
                "Small wheel movements stay within the current page",
                beforePage,
                page.textPage.index,
            )
        }
    }

    private fun dispatchScroll(
        activity: ReadBookActivity,
        value: Float,
        source: Int = InputDevice.SOURCE_MOUSE,
        axis: Int = MotionEvent.AXIS_VSCROLL,
    ) {
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                x = activity.readerView.width / 2f
                y = activity.readerView.height / 2f
                setAxisValue(axis, value)
            }
        val now = SystemClock.uptimeMillis()
        val event =
            MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_SCROLL,
                1,
                arrayOf(properties),
                arrayOf(coordinates),
                0,
                0,
                1f,
                1f,
                0,
                0,
                source,
                0,
            )
        try {
            activity.dispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun currentPage(): Int {
        var index = -1
        scenario!!.onActivity { index = it.readerView.curPage.textPage.index }
        return index
    }

    private fun awaitReader(condition: (ReadBookActivity) -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                var ready = false
                scenario!!.onActivity { ready = condition(it) }
                ready
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Preserve the original state diagnostics and failure assertion below.
        }
        throw AssertionError("Reader did not reach the expected wheel test state")
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
}
