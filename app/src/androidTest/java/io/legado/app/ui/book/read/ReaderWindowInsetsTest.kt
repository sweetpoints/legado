package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Rect
import android.view.View
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.App
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.navigationBarHeight
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual window, Compose host and native page geometry, without a fixed status-bar height. */
@RunWith(AndroidJUnit4::class)
class ReaderWindowInsetsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var fixture: Book? = null
    private var file: File? = null
    private val savedHelp = LocalConfig.all["readMenuHelpVersion"]
    private val savedHideStatusBar = ReadBookConfig.hideStatusBar
    private val savedBodyToCutout = ReadBookConfig.readBodyToLh
    private val prefs = context.defaultSharedPreferences
    private val savedCutoutPadding = prefs.all[PreferKey.paddingDisplayCutouts]
    private var savedHeaderMode: Int? = null
    private var savedFooterMode: Int? = null

    @Before
    fun setUp() {
        compose.waitUntil(30000) {
            compose.mainClock.advanceTimeByFrame()
            (context.applicationContext as App).initialization.isCompleted
        }
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).commit()
        val text = File.createTempFile("reader-insets-", ".txt", context.cacheDir).also { file = it }
        text.writeText((0..80).joinToString("\n") { "Window inset regression paragraph $it." })
        val book = Book(
            bookUrl = text.absolutePath,
            originName = text.name,
            name = text.name,
            charset = "UTF-8",
            type = BookType.local or BookType.text,
            totalChapterNum = 1,
        ).apply { setPageAnim(PageAnim.noAnim) }
        fixture = book
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(
            bookUrl = book.bookUrl,
            url = "reader-insets-chapter",
            title = "Window insets",
            start = 0L,
            end = text.length(),
        ))
        scenario = ActivityScenario.launch(
            Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)
        )
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.dismiss() }
        }
        awaitReader()
        scenario!!.onActivity { activity ->
            savedHeaderMode = ReadTipConfig.headerMode
            savedFooterMode = ReadTipConfig.footerMode
            ReadTipConfig.headerMode = 2
            ReadTipConfig.footerMode = 1
            activity.findViewById<ReadView>(R.id.read_view).upStyle()
        }
    }

    @After
    fun tearDown() {
        scenario?.onActivity {
            ReadBookConfig.hideStatusBar = savedHideStatusBar
            ReadBookConfig.readBodyToLh = savedBodyToCutout
            savedHeaderMode?.let { mode -> ReadTipConfig.headerMode = mode }
            savedFooterMode?.let { mode -> ReadTipConfig.footerMode = mode }
        }
        scenario?.close()
        fixture?.let { book ->
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
        }
        file?.delete()
        TextFile.clear()
        prefs.edit().apply {
            if (savedCutoutPadding == null) remove(PreferKey.paddingDisplayCutouts)
            else putBoolean(PreferKey.paddingDisplayCutouts, savedCutoutPadding as Boolean)
        }.commit()
        LocalConfig.edit().apply {
            if (savedHelp == null) remove("readMenuHelpVersion")
            else putInt("readMenuHelpVersion", savedHelp as Int)
        }.commit()
    }

    @Test
    fun nativeCanvasAndChromeOwnVisibleAndHiddenInsetsBeforeAndAfterRecreation() {
        for (hideStatusBar in listOf(false, true, false)) {
            scenario!!.onActivity { activity ->
                ReadBookConfig.hideStatusBar = hideStatusBar
                activity.upSystemUiVisibility()
                activity.findViewById<ReadView>(R.id.read_view).upStyle()
            }
            assertGeometry(hideStatusBar)
        }
        scenario!!.recreate()
        awaitReader()
        scenario!!.onActivity { activity ->
            ReadTipConfig.headerMode = 2
            ReadTipConfig.footerMode = 1
            activity.setupSystemBar()
            activity.upSystemUiVisibility()
            activity.findViewById<ReadView>(R.id.read_view).upStyle()
        }
        assertGeometry(false)
    }

    @Test
    fun cutoutChoicesPreserveNativeBodyEdgesWithVisibleAndHiddenSystemBars() {
        for (bodyToCutout in listOf(false, true)) {
            for (padCutout in listOf(false, true)) {
                prefs.edit().putBoolean(PreferKey.paddingDisplayCutouts, padCutout).commit()
                scenario!!.onActivity { ReadBookConfig.readBodyToLh = bodyToCutout }
                // Apply the same window mode path used when the user changes this setting.
                scenario!!.recreate()
                awaitReader()
                for (hidden in listOf(false, true, false)) {
                    scenario!!.onActivity { activity ->
                        ReadBookConfig.hideStatusBar = hidden
                        ReadTipConfig.headerMode = 2
                        ReadTipConfig.footerMode = 1
                        activity.upSystemUiVisibility()
                        val reader = activity.findViewById<ReadView>(R.id.read_view)
                        reader.upStyle()
                        ViewCompat.requestApplyInsets(reader)
                    }
                    assertGeometry(hidden)
                }
            }
        }
    }

    private fun awaitReader() {
        compose.waitUntil(30000) {
            compose.mainClock.advanceTimeByFrame()
            var ready = false
            scenario!!.onActivity { activity ->
                val reader = activity.findViewById<ReadView>(R.id.read_view)
                val chapter = ReadBook.curTextChapter
                ready = ReadBook.book?.bookUrl == fixture?.bookUrl &&
                    chapter?.isCompleted == true && chapter.chapter.bookUrl == fixture?.bookUrl &&
                    reader.curPage.textPage.textChapter === chapter && !reader.curPage.textPage.isMsgPage &&
                    activity.bottomDialog == 0
            }
            ready
        }
    }

    private fun assertGeometry(hidden: Boolean) {
        compose.waitUntil(30000) {
            compose.mainClock.advanceTimeByFrame()
            var settled = false
            scenario!!.onActivity { activity ->
                val decor = activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor) ?: return@onActivity
                val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                val caption = insets.getInsets(WindowInsetsCompat.Type.captionBar())
                val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val reader = activity.findViewById<ReadView>(R.id.read_view)
                val background = Rect()
                if (ReadBookConfig.isNineBgImg) reader.curPage.getChildAt(0).background?.getPadding(background)
                val avoidCutout = AppConfig.paddingDisplayCutouts || !ReadBookConfig.readBodyToLh
                val visibleTop = !hidden || activity.isInMultiWindow
                val expectedTop = maxOf(background.top,
                    if (avoidCutout && !visibleTop) cutout.top else 0) +
                    if (visibleTop) maxOf(bars.top, caption.top,
                        if (avoidCutout) cutout.top else 0) else 0
                val expectedLeft = maxOf(background.left, navigation.left,
                    if (avoidCutout) cutout.left else 0)
                val expectedRight = reader.width - maxOf(background.right, navigation.right,
                    if (avoidCutout) cutout.right else 0)
                val navigationHeight = if (!ReadBookConfig.hideNavigationBar) insets.navigationBarHeight else 0
                val expectedBottom = reader.height - navigationHeight - maxOf(background.bottom,
                    if (avoidCutout) (cutout.bottom - insets.navigationBarHeight).coerceAtLeast(0) else 0)
                val body = reader.curPage.findViewById<View>(R.id.content_text_view)
                val bodyPosition = IntArray(2)
                body.getLocationInWindow(bodyPosition)
                settled = insets.isVisible(WindowInsetsCompat.Type.statusBars()) ==
                    (!hidden || activity.isInMultiWindow) &&
                    reader.curPage.contentViewTop == expectedTop.toFloat() &&
                    bodyPosition[0] == expectedLeft && bodyPosition[1] == expectedTop &&
                    bodyPosition[0] + body.width == expectedRight &&
                    bodyPosition[1] + body.height == expectedBottom
                if (settled) File(context.getExternalFilesDir("ui-regression"), "reader-insets-geometry.txt")
                    .appendText("bodyToCutout=${ReadBookConfig.readBodyToLh} padCutout=${AppConfig.paddingDisplayCutouts} hidden=$hidden cutout=$cutout navigation=$navigation caption=$caption body=${bodyPosition.toList()} ${body.width}x${body.height} expected=[$expectedLeft,$expectedTop,$expectedRight,$expectedBottom]\n")
            }
            settled
        }
        val host = compose.onNodeWithTag("reader-host").fetchSemanticsNode().boundsInRoot
        scenario!!.onActivity { activity ->
            val decor = activity.window.decorView
            val content = decor.findViewById<View>(android.R.id.content)
            val reader = activity.findViewById<ReadView>(R.id.read_view)
            for ((name, view) in listOf("Activity content" to content, "Native canvas" to reader)) {
                val position = IntArray(2)
                view.getLocationInWindow(position)
                assertEquals("$name must start at the window left", 0, position[0])
                assertEquals("$name must start at the window top", 0, position[1])
                assertEquals("$name must retain the full window width", decor.width, view.width)
                assertEquals("$name must retain the full window height", decor.height, view.height)
            }
            assertTrue("The real reader must have a nonempty canvas", reader.curPage.isCanvasReady)
            assertEquals("Compose host uses the native window origin X", 0f, host.left, 1f)
            assertEquals("Compose host uses the native window origin Y", 0f, host.top, 1f)
            assertEquals("Compose host retains the window width", decor.width.toFloat(), host.width, 1f)
            assertEquals("Compose host retains the window height", decor.height.toFloat(), host.height, 1f)
        }
    }
}
