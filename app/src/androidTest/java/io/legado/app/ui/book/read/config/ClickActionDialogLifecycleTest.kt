package io.legado.app.ui.book.read.config

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.preferences.ClickActionRegion
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClickActionDialogLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun pickerRestoresAcrossRecreationAndRealCloseReleasesCounterAndRestoresMenuOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.defaultSharedPreferences
        val previous = ClickActionRegion.entries.associate { it.key to preferences.all[it.key] }
        val previousHelp = LocalConfig.all["readMenuHelpVersion"]
        val file = File.createTempFile("click-action-reader-", ".txt", context.cacheDir)
        file.writeText(
            (0..60).joinToString("\n") { "Reader content for click-region dialog lifecycle $it." }
        )
        val book =
            Book(
                    bookUrl = file.absolutePath,
                    originName = file.name,
                    name = file.name,
                    charset = "UTF-8",
                    type = BookType.local or BookType.text,
                    totalChapterNum = 1,
                )
                .apply { setPageAnim(PageAnim.noAnim) }
        var reader: ActivityScenario<ReadBookActivity>? = null
        try {
            LocalConfig.edit().putInt("readMenuHelpVersion", 1).commit()
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(
                BookChapter(
                    bookUrl = book.bookUrl,
                    url = "click-region-chapter",
                    title = "Click regions",
                    start = 0L,
                    end = file.length(),
                )
            )
            reader =
                ActivityScenario.launch(
                    Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)
                )
            reader.onActivity { activity ->
                activity.supportFragmentManager.fragments
                    .filterIsInstance<ClickActionConfigDialog>()
                    .forEach { it.dismiss() }
            }
            await(reader) {
                ReadBook.book?.bookUrl == book.bookUrl &&
                    ReadBook.curTextChapter?.isCompleted == true &&
                    it.bottomDialog == 0
            }
            lateinit var oldActivity: ReadBookActivity
            reader.onActivity {
                oldActivity = it
                preferences
                    .edit()
                    .apply { ClickActionRegion.entries.forEach { region -> putInt(region.key, 1) } }
                    .commit()
                ClickActionConfigDialog().show(it.supportFragmentManager, "click-lifecycle")
            }
            await(reader) { it.bottomDialog == 1 }
            compose.onNodeWithTag("click-action-region-BottomRight").performClick()
            reader.recreate()
            await(reader) {
                it.bottomDialog == 1 &&
                    it.supportFragmentManager.findFragmentByTag("click-lifecycle")?.view != null
            }
            assertEquals(0, oldActivity.bottomDialog)
            assertEquals(1, preferences.getInt(ClickActionRegion.MiddleCenter.key, -99))
            compose.onNodeWithTag("click-action-option-1").assertIsSelected()
            compose.onNodeWithTag("click-action-picker-cancel").performClick()
            reader.onActivity {
                val dialog =
                    it.supportFragmentManager.findFragmentByTag("click-lifecycle")
                        as ClickActionConfigDialog
                dialog.dismiss()
                dialog.dismiss()
            }
            await(reader) { it.bottomDialog == 0 }
            assertEquals(0, preferences.getInt(ClickActionRegion.MiddleCenter.key, -99))
            assertEquals(1, preferences.getInt(ClickActionRegion.BottomRight.key, -99))
        } finally {
            reader?.close()
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
            LocalConfig.edit()
                .apply {
                    if (previousHelp == null) remove("readMenuHelpVersion")
                    else putInt("readMenuHelpVersion", previousHelp as Int)
                }
                .commit()
            preferences
                .edit()
                .apply {
                    previous.forEach { (key, value) ->
                        when (value) {
                            null -> remove(key)
                            is Int -> putInt(key, value)
                            is String -> putString(key, value)
                        }
                    }
                }
                .commit()
        }
    }

    private fun await(
        reader: ActivityScenario<ReadBookActivity>,
        predicate: (ReadBookActivity) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var success = false
            reader.onActivity { success = predicate(it) }
            if (success) return
            SystemClock.sleep(25)
        }
        error("Reader lifecycle condition timed out")
    }
}
