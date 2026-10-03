package io.legado.app.ui.menu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBookPopupActionMigrationTest {

    @Test
    fun `reader source actions use Compose without losing capability gates`() {
        val source = readProjectFile(READ_MENU).replace(Regex("\\s+"), " ")
        val screen = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadMenuScreen.kt")
        assertFalse(source.contains("ViewReadMenuBinding"))
        assertTrue(screen.contains("DropdownMenu("))
        assertOrdered(
            source,
            "val hasLogin = ReadBook.bookSource?.hasLogin() == true",
            "val canPay =",
            "hasLogin && ReadBook.curTextChapter?.isVip == true && ReadBook.curTextChapter?.isPay != true",
            "if (hasLogin)",
            "R.string.login",
            "if (canPay)",
            "R.string.chapter_pay",
            "R.string.edit_book_source",
            "R.string.disable_book_source",
            "\"login\" -> callBack.showLogin()",
            "\"chapterPay\" -> callBack.payAction()",
            "\"editSource\" -> callBack.openSourceEditActivity()",
            "\"disableSource\" -> callBack.disableSource()",
        )
        assertFalse(sequenceOf(File(SOURCE_MENU), File("app/$SOURCE_MENU")).any(File::isFile))
    }

    @Test
    fun `reader long press menus preserve all change and refresh actions in Compose`() {
        val source = readProjectFile(READ_BOOK_ACTIVITY).replace(Regex("\\s+"), " ")
        assertFalse(source.contains("private fun showChangeSourceMenu"))
        assertFalse(source.contains("private fun showRefreshMenu"))
        assertOrdered(
            source,
            "ReaderPopup.ChangeSource -> listOf(",
            "R.string.chapter_change_source",
            "R.string.batch_chapter_change_source",
            "R.string.book_change_source",
            "ReaderPopup.Refresh -> listOf(",
            "R.string.menu_refresh_dur",
            "R.string.menu_refresh_after",
            "R.string.menu_refresh_all",
            "R.string.menu_refresh_resources",
            "override fun readerPopupAction",
            "\"chapter\" -> showChapterChangeSource()",
            "\"batchChapter\" -> showChapterChangeSource(batchMode = true)",
            "\"book\" -> showBookChangeSource()",
            "\"dur\" -> refreshDurChapter()",
            "\"after\" -> refreshAfterChapters()",
            "\"all\" -> refreshAllChapters()",
        )
        listOf(CHANGE_SOURCE_MENU, REFRESH_MENU).forEach { path ->
            assertFalse(sequenceOf(File(path), File("app/$path")).any(File::isFile))
        }
    }

    @Test
    fun `reader source actions preserve dialog inputs and menu dismissal`() {
        val source = readProjectFile(READ_BOOK_ACTIVITY)
        val book =
            section(
                source,
                "private fun showBookChangeSource()",
                "private fun showChapterChangeSource(batchMode: Boolean = false)",
            )
        val chapter =
            section(
                source,
                "private fun showChapterChangeSource(batchMode: Boolean = false)",
                "private fun refreshDurChapter()",
            )

        assertOrdered(
            book,
            "binding.readMenu.runMenuOut()",
            "ReadBook.book?.let",
            "ChangeBookSourceDialog(it.name, it.author)",
        )
        assertOrdered(
            chapter,
            "lifecycleScope.launch",
            "val book = ReadBook.book ?: return@launch",
            "appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)",
            "binding.readMenu.runMenuOut()",
            "ChangeChapterSourceDialog(",
            "batchMode = batchMode",
        )
    }

    @Test
    fun `reader refresh actions preserve review reset and cache behavior`() {
        val source = readProjectFile(READ_BOOK_ACTIVITY)
        val current =
            section(
                source,
                "private fun refreshDurChapter()",
                "private fun refreshAfterChapters()",
            )
        val after =
            section(
                source,
                "private fun refreshAfterChapters()",
                "private fun refreshAllChapters()",
            )
        val all =
            section(
                source,
                "private fun refreshAllChapters()",
                "override fun onCompatOptionsItemSelected",
            )
        val refreshContentEvent =
            section(
                source,
                "observeEvent<Boolean>(EventBus.REFRESH_BOOK_CONTENT)",
                "observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC)",
            )

        assertOrdered(
            current,
            "resetReviewSummaryState()",
            "if (ReadBook.bookSource == null)",
            "upContent()",
            "ReadBook.curTextChapter = null",
            "binding.readView.upContent()",
            "viewModel.refreshContentDur(it)",
        )
        assertOrdered(
            after,
            "resetReviewSummaryState()",
            "if (ReadBook.bookSource == null)",
            "upContent()",
            "ReadBook.clearTextChapter()",
            "binding.readView.upContent()",
            "viewModel.refreshContentAfter(it)",
        )
        assertOrdered(
            all,
            "if (ReadBook.bookSource == null)",
            "resetReviewSummaryState()",
            "upContent()",
            "refreshContentAll(it)",
        )
        assertContains(refreshContentEvent, "refreshDurChapter()")
        assertFalse(refreshContentEvent.contains("viewModel.refreshContentDur"))
    }

    @Test
    fun `toolbar clicks route through the same reader actions`() {
        val source = readProjectFile(READ_BOOK_ACTIVITY)

        val host = readProjectFile(READ_MENU).replace(Regex("\\s+"), " ")
        assertContains(host, "longPress && id == R.id.menu_change_source")
        assertContains(host, "openPopup(ReaderPopup.ChangeSource)")
        assertContains(host, "longPress && id == R.id.menu_refresh")
        assertContains(host, "openPopup(ReaderPopup.Refresh)")
        assertContains(host, "else if (!longPress) callBack.readerToolbarAction(id)")
        assertContains(source, "R.id.menu_change_source -> showBookChangeSource()")
        assertContains(source, "R.id.menu_refresh -> refreshDurChapter()")
        listOf(
                "R.id.menu_book_change_source",
                "R.id.menu_chapter_change_source",
                "R.id.menu_refresh_dur",
                "R.id.menu_refresh_after",
                "R.id.menu_refresh_all",
            )
            .forEach { assertFalse(source.contains(it)) }
    }

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue(
            "ReadBookActivity.kt should contain $start before $end",
            startIndex >= 0 && endIndex > startIndex,
        )
        return source.substring(startIndex, endIndex)
    }

    private fun assertOrdered(source: String, vararg snippets: String) {
        var previous = -1
        snippets.forEach { snippet ->
            val current = source.indexOf(snippet, previous + 1)
            assertTrue(
                "ReadBookActivity.kt should contain $snippet after the previous action",
                current > previous,
            )
            previous = current
        }
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("ReadBookActivity.kt should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?.replace("\r\n", "\n")
            .orEmpty()

    private companion object {
        const val READ_BOOK_ACTIVITY =
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        const val READ_MENU = "src/main/java/io/legado/app/ui/book/read/ReadMenu.kt"
        const val CHANGE_SOURCE_MENU = "src/main/res/menu/book_read_change_source.xml"
        const val REFRESH_MENU = "src/main/res/menu/book_read_refresh.xml"
        const val SOURCE_MENU = "src/main/res/menu/book_read_source.xml"
    }
}
