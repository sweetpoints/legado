package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeSourceViewLifecycleContractTest {

    @Test
    fun `search results stay in the view model across view recreation`() {
        val source = source("ChangeBookSourceViewModel.kt")
            .section("val searchDataFlow", "override fun onCleared")

        assertTrue(source.contains(".shareIn("))
        assertTrue(source.contains("scope = viewModelScope"))
        assertTrue(source.contains("started = SharingStarted.Lazily"))
        assertTrue(source.contains("replay = 1"))
    }

    @Test
    fun `dialog collectors are cancelled with the current view`() {
        val book = source("ChangeBookSourceDialog.kt").section("private fun initLiveData()", "private fun showChangeSourceLoading")
        assertTrue(book.contains("val owner = viewLifecycleOwner")); assertTrue(book.contains("owner.lifecycleScope.launch"))
        assertTrue(book.contains("owner.lifecycle.currentStateFlow")); assertFalse(book.contains("\n        lifecycleScope.launch"))
        assertTrue(book.contains("owner.repeatOnLifecycle(STARTED)"))
        // Chapter behavior is covered independently by ChapterSourceViewModelTest/ChapterSourceComposeTest.
    }

    @Test
    fun `progress replay is not discarded when a view restarts`() {
        val dialog = source("ChangeBookSourceDialog.kt")
        val progress = dialog.section(
            "viewModel.changeSourceProgress",
            "appDb.bookSourceDao.flowEnabledGroups()",
        )

        assertFalse(progress.contains(".drop("))
        assertTrue(progress.contains("if (count == 0 && name.isEmpty())"))
        assertTrue(progress.contains("callBack?.oldBook?.originName"))
    }

    @Test
    fun `book source dialog locates the current source after first results`() {
        val dialog = source("ChangeBookSourceDialog.kt")
        val collector = dialog.section(
            "viewModel.searchDataFlow",
            "viewModel.changeSourceProgress",
        )

        assertTrue(dialog.contains("private var autoScrollCurrentSource = true"))
        assertTrue(collector.contains("if (autoScrollCurrentSource && it.isNotEmpty())"))
        assertTrue(collector.contains("binding.recyclerView.post"))
        assertTrue(collector.contains("if (scrollToDurSource()) autoScrollCurrentSource = false"))
        assertTrue(dialog.contains("autoScrollCurrentSource = true"))
        assertTrue(
            dialog.section("private fun scrollToDurSource(): Boolean", "override fun changeTo")
                .contains("if (searchBook.bookUrl == oldBookUrl)")
        )
    }

    @Test
    fun `current book source keeps a subtle background and check mark`() {
        val adapter = source("ChangeBookSourceAdapter.kt")
        assertTrue(adapter.contains("viewSelectedBackground"))
        assertTrue(adapter.contains("ColorUtils.withAlpha(context.accentColor, 0.1f)"))
        assertTrue(adapter.contains("ivChecked.visible()"))
    }

    @Test
    fun `pending business results wait for the current host to resume`() {
        val book = source("ChangeBookSourceDialog.kt").section("viewModel.changeSourceResult.observe(owner)", "viewModel.changeSourceProgress")
        assertTrue(book.contains("withStateAtLeast(RESUMED)")); assertTrue(book.indexOf("withStateAtLeast(RESUMED)") < book.indexOf("event.take()"))
        // Chapter behavior is covered independently by ChapterSourceViewModelTest/ChapterSourceComposeTest.
        // Actual RESUMED host delivery/cancellation is also exercised by ChapterSourceComposeTest.
    }

    @Test
    fun `deleting the current source waits for a successful replacement`() {
        val bookDialog = source("ChangeBookSourceDialog.kt")
        val viewModel = source("ChangeBookSourceViewModel.kt")

        listOf(bookDialog).forEach { dialog ->
            val deleteSource = dialog.section(
                "override fun deleteSource",
                "override fun setBookScore",
            )

            assertTrue(deleteSource.contains("viewModel.autoChangeSource("))
            assertTrue(deleteSource.contains(", searchBook)"))
            assertTrue(deleteSource.contains("else {\n            viewModel.del(searchBook)"))
            assertTrue(dialog.contains("SourceChangeCompletion("))
            assertTrue(dialog.contains("completion::success"))
        }

        val autoChange = viewModel.section("fun autoChangeSource", "fun setBookScore")
        val delete = viewModel.section("fun del", "fun autoChangeSource")
        val loading = bookDialog.section(
            "private fun showChangeSourceLoading",
            "private val startStopMenuItem",
        )
        assertTrue(delete.contains("Coroutine.async"))
        assertFalse(delete.contains("execute {"))
        assertTrue(autoChange.contains("deleteAfterChange: SearchBook"))
        assertTrue(autoChange.contains("it.origin != deleteAfterChange.origin"))
        assertTrue(autoChange.contains("deleteAfterChange = deleteAfterChange"))
        assertTrue(loading.contains("dialog.setCancelable(cancelable)"))
        assertTrue(loading.contains("if (cancelable)"))
        assertTrue(loading.contains("viewModel.cancelChangeSource()"))
        // Chapter behavior is covered independently by ChapterSourceViewModelTest/ChapterSourceComposeTest.
    }

    @Test
    fun `old source deletion runs once only after migration success`() {
        val source = SearchBook(origin = "old")
        val deleted = mutableListOf<SearchBook>()
        val completion = SourceChangeCompletion(source, deleted::add)

        assertTrue(deleted.isEmpty())
        completion.success()
        completion.success()
        assertEquals(listOf(source), deleted)

        SourceChangeCompletion(null, deleted::add).success()
        assertEquals(listOf(source), deleted)
    }

    @Test
    fun `hosts acknowledge source changes only from successful migration callbacks`() {
        val viewModels = listOf(
            appSource("book/read/ReadBookViewModel.kt")
                .section("fun changeTo(", "/**\n     * 自动换源"),
            appSource("book/audio/AudioPlayViewModel.kt")
                .section("fun changeTo(", "fun removeFromBookshelf"),
            appSource("book/info/BookInfoViewModel.kt")
                .section("fun changeTo(", "fun saveBook"),
            appSource("book/manga/ReadMangaViewModel.kt")
                .section("fun changeTo(", "private fun checkLocalBookFileExist"),
        )
        viewModels.forEach { changeTo ->
            assertTrue(changeTo.contains("onSuccess: () -> Unit"))
            assertTrue(changeTo.contains(".onSuccess {\n            onSuccess()"))
            assertFalse(changeTo.contains(".onFinally {\n            onSuccess()"))
        }

        val readActivity = appSource("book/read/ReadBookActivity.kt")
            .section("override fun changeTo(", "override fun replaceContent")
        val audioActivity = appSource("book/audio/AudioPlayActivity.kt")
            .section("override fun changeTo(", "override fun finish")
        val infoActivity = appSource("book/info/BookInfoActivity.kt")
            .section("override fun changeTo(", "override fun coverChangeTo")
        val mangaActivity = appSource("book/manga/ReadMangaActivity.kt")
            .section("override fun changeTo(", "override fun updateColorFilter")

        assertTrue(readActivity.contains("viewModel.changeTo(book, toc, onSuccess)"))
        assertTrue(audioActivity.contains("viewModel.changeTo(source, book, toc, onSuccess)"))
        assertTrue(infoActivity.contains("viewModel.changeTo(source, book, toc, onSuccess)"))
        assertTrue(mangaActivity.contains("viewModel.changeTo(book, toc, onSuccess)"))
        listOf(readActivity, audioActivity).forEach { changeTo ->
            assertTrue(
                changeTo.indexOf("appDb.bookDao.insert(book)") <
                    changeTo.indexOf("onSuccess()")
            )
        }
    }

    @Test
    fun `adapter delete confirmation is released with the recycler view`() {
        val adapter = source("ChangeBookSourceAdapter.kt")
        val detach = adapter.section(
            "override fun onDetachedFromRecyclerView",
            "interface CallBack",
        )

        assertTrue(adapter.contains("if (deleteSourceDialog == null)"))
        assertTrue(adapter.contains("if (deleteSourceDialog === dialog)"))
        assertTrue(detach.contains("deleteSourceDialog?.dismiss()"))
        assertTrue(detach.contains("deleteSourceDialog = null"))
    }

    @Test
    fun `view model owns asynchronous results instead of fragment callbacks`() {
        val bookViewModel = source("ChangeBookSourceViewModel.kt"); val bookDialog = source("ChangeBookSourceDialog.kt")
        assertTrue(bookViewModel.contains("searchFinishData.postValue(PendingEvent(")); assertTrue(bookViewModel.contains("changeSourceResult.value = PendingEvent("))
        assertFalse(bookViewModel.contains("searchFinishCallback")); assertFalse(bookDialog.contains("viewModel.getToc(book,"))
        // Chapter behavior is covered independently by ChapterSourceViewModelTest/ChapterSourceComposeTest.
        // Chapter behavior is covered independently by ChapterSourceViewModelTest/ChapterSourceComposeTest.
    }

    @Test
    fun `per view adapter observers are released`() {
        val bookDestroy = source("ChangeBookSourceDialog.kt").section("override fun onDestroyView()", "private fun showTitle()")
        assertTrue(bookDestroy.contains("unregisterAdapterDataObserver")); assertTrue(bookDestroy.contains("binding.recyclerView.adapter = null"))
        assertTrue(bookDestroy.contains("searchFinishDialog?.dismiss()")); assertTrue(bookDestroy.contains("waitDialog?.dismiss()"))
        // Chapter behavior is covered independently by ChapterSourceViewModelTest/ChapterSourceComposeTest.
    }

    @Test
    fun `pending result can be inspected before one-time delivery`() {
        val event = PendingEvent("result")

        assertEquals("result", event.peek())
        assertEquals("result", event.peek())
        assertEquals("result", event.take())
        assertNull(event.peek())
        assertNull(event.take())
    }

    @Test
    fun `search prompt stays pending until the dialog finishes`() {
        val prompt = source("ChangeBookSourceDialog.kt").section("private fun showEmptySearchGroupDialog(", "private fun showChangeSourceLoading")
        assertTrue(prompt.contains("if (event.peek() != true)")); assertTrue(prompt.contains("onCancelled { event.take() }"))
        assertTrue(prompt.contains("if (searchFinishDialog === dialog)"))
        // Compose prompt interaction and rotation are covered by ChapterSourceComposeTest.
    }

    private fun source(fileName: String): String {
        return appSource("book/changesource/$fileName")
    }

    private fun appSource(relativePath: String): String {
        return projectFile("src/main/java/io/legado/app/ui/$relativePath")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) {
            "Missing section $startMarker .. $endMarker"
        }
        return substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
