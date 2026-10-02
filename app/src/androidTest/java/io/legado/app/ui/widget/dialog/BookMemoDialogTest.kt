package io.legado.app.ui.widget.dialog

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookMemo
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadMenu
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BookMemoDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedMemoPref = preferences.all[PreferKey.showBookMemo] as? Boolean
    private val savedComic = ReadBookConfig.isComic
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private lateinit var file: File
    private lateinit var book: Book
    private var archive: File? = null
    private var scenario: ActivityScenario<ReadBookActivity>? = null

    @Before fun setUp() {
        preferences.edit().remove(PreferKey.showBookMemo).commit()
        file = File.createTempFile("memo-reader-", ".txt", context.cacheDir)
        file.writeText((1..40).joinToString("\n") { "Line $it: a book with an independent memo." })
        book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
            .apply { setPageAnim(PageAnim.noAnim) }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "memo-chapter",
            title = "Memo chapter", start = 0L, end = file.length()))
        instrumentation.runOnMainSync { ReadBookConfig.isComic = false }
    }

    @After fun cleanUp() {
        scenario?.close()
        appDb.bookDao.delete(book)
        appDb.bookDao.delete(book.copy(bookUrl = book.bookUrl + "-changed"))
        file.delete()
        archive?.parentFile?.deleteRecursively()
        TextFile.clear()
        instrumentation.runOnMainSync { ReadBookConfig.isComic = savedComic }
        preferences.edit().apply {
            if (savedMemoPref == null) remove(PreferKey.showBookMemo)
            else putBoolean(PreferKey.showBookMemo, savedMemoPref)
        }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
    }

    @Test fun defaultOffMenuMarkdownDraftRotationSaveAndClear() {
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl))
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.dismiss() }
        }
        await {
            ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage && it.bottomDialog == 0
        }
        scenario!!.onActivity {
            it.findViewById<ReadMenu>(R.id.read_menu).runMenuIn(false)
            assertEquals(View.GONE, it.findViewById<View>(R.id.ll_memo).visibility)
        }
        preferences.edit().putBoolean(PreferKey.showBookMemo, true).commit()
        scenario!!.onActivity {
            it.findViewById<ReadMenu>(R.id.read_menu).runMenuIn(false)
        }
        for (id in listOf(R.id.ll_catalog, R.id.ll_read_aloud, R.id.ll_font, R.id.ll_setting, R.id.ll_memo)) {
            onView(withId(id)).check(matches(isCompletelyDisplayed()))
        }
        screenshot("book-memo-five-buttons")
        onView(withId(R.id.ll_memo)).perform(click())
        await { memoDialog(it)?.let { dialog -> ViewModelProvider(dialog)[BookMemoViewModel::class.java].state.value.loaded } == true }
        scenario!!.onActivity {
            val window = memoDialog(it)!!.requireDialog().window!!
            val screenHeight = context.resources.displayMetrics.heightPixels
            assertTrue("Memo uses a half-height panel", window.attributes.height in (screenHeight * .4f).toInt()..(screenHeight * .6f).toInt())
        }
        compose.onNodeWithTag("memo-edit-save").performClick()
        val markdown = "# 备忘标题\n\n**重要内容**\n\n- 第一条\n- 第二条"
        compose.onNodeWithTag("memo-editor").performTextReplacement(markdown)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scenario!!.recreate()
        await { memoDialog(it)?.let { dialog -> ViewModelProvider(dialog)[BookMemoViewModel::class.java].state.value.let { state -> state.loaded && state.editing && state.draft == markdown } } == true }
        compose.onNodeWithTag("memo-edit-save").performClick()
        await { memoDialog(it)?.let { dialog -> ViewModelProvider(dialog)[BookMemoViewModel::class.java].state.value.let { state -> !state.editing && state.memo?.content == markdown } } == true }
        compose.onNodeWithText("重要内容").assertIsDisplayed()
        assertEquals(markdown, appDb.bookMemoDao.get(book.bookUrl)!!.content)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("重要内容").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val rendered = layouts.single().layoutInput.text
        assertTrue("Markdown bold must reach the actual Compose text layout", rendered.spanStyles.any {
            it.item.fontWeight == FontWeight.Bold && rendered.text.substring(it.start, it.end) == "重要内容"
        })
        screenshot("book-memo-markdown")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { it.findViewById<ReadView>(R.id.read_view).width > it.findViewById<ReadView>(R.id.read_view).height &&
            memoDialog(it)?.dialog?.window?.attributes?.height?.let { height ->
                val screenHeight = context.resources.displayMetrics.heightPixels
                height in (screenHeight * .4f).toInt()..(screenHeight * .6f).toInt()
            } == true }
        compose.onNodeWithText("重要内容").assertIsDisplayed()
        compose.onNodeWithTag("memo-edit-save").assertIsDisplayed()
        compose.onNodeWithTag("memo-clear-cancel").assertIsDisplayed()
        val contentHeight = compose.onNodeWithTag("memo-scroll").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Landscape keeps readable memo space", contentHeight >= 48 * context.resources.displayMetrics.density)
        screenshot("book-memo-landscape")
        compose.onNodeWithTag("memo-clear-cancel").performClick()
        compose.onNodeWithTag("memo-confirm").performClick()
        await { memoDialog(it)?.let { dialog -> ViewModelProvider(dialog)[BookMemoViewModel::class.java].state.value.memo?.content == "" } == true }
        compose.onNodeWithTag("memo-empty").assertTextEquals(context.getString(R.string.book_memo_empty))
        assertEquals("", appDb.bookMemoDao.get(book.bookUrl)!!.content)
    }

    @Test fun actualLanArchiveRestoresMemoWithoutResurrectingClearedContent() = runBlocking {
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = it != BackupConfig.bookshelfContentKey }
        BackupConfig.ignoreConfig["localBook"] = false
        appDb.bookMemoDao.save(book.bookUrl, "# Saved in the archive", 100)
        val backup = Backup.backupForLanTransferLocked(context).also { archive = it }
        ZipFile(backup).use { zip ->
            val entry = checkNotNull(zip.getEntry("bookMemo.json"))
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val memos = GSON.fromJsonArray<BookMemo>(json).getOrThrow()
            assertEquals("# Saved in the archive", memos.single { it.bookUrl == book.bookUrl }.content)
        }
        appDb.bookDao.delete(book)
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertEquals("# Saved in the archive", appDb.bookMemoDao.get(book.bookUrl)!!.content)
        val changed = book.copy(bookUrl = book.bookUrl + "-changed")
        appDb.bookDao.replace(book, changed)
        appDb.bookMemoDao.save(changed.bookUrl, "New memo after changing source", 101)
        // Restoring the old shelf URL also replaces the row through its name/author index.
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertNull(appDb.bookMemoDao.get(changed.bookUrl))
        assertEquals("New memo after changing source", appDb.bookMemoDao.get(book.bookUrl)!!.content)
        appDb.bookMemoDao.save(book.bookUrl, "", 102)
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertEquals("", appDb.bookMemoDao.get(book.bookUrl)!!.content)
    }

    private fun memoDialog(activity: ReadBookActivity) =
        activity.supportFragmentManager.fragments.filterIsInstance<BookMemoDialog>().firstOrNull()

    private fun await(condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Book memo did not reach the expected state")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
