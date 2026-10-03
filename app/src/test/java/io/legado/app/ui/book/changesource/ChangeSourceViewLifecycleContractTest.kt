package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.SearchBook
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSourceViewLifecycleContractTest {

    // Book lifecycle and UI interactions: BookSourceViewModelTest and BookSourceComposeTest.
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
        val viewModels =
            listOf(
                appSource("book/read/ReadBookViewModel.kt")
                    .section("fun changeTo(", "/**\n     * 自动换源"),
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

        val audioMigration =
            appSource("book/audio/AudioPlayViewModel.kt")
                .section("fun changeTo(", "fun removeFromBookshelf")
        assertTrue(
            audioMigration.indexOf("repository.changeSource(") <
                audioMigration.indexOf("onSuccess()")
        )

        val readActivity =
            appSource("book/read/ReadBookActivity.kt")
                .section("override fun changeTo(", "override fun replaceContent")
        val audioActivity =
            appSource("book/audio/AudioPlayActivity.kt")
                .section("override fun changeTo(", "override fun finish")
        val infoActivity =
            appSource("book/info/BookInfoActivity.kt")
                .section("override fun changeTo(", "override fun coverChangeTo")
        val mangaActivity =
            appSource("book/manga/ReadMangaActivity.kt")
                .section("override fun changeTo(", "override fun updateColorFilter")

        assertTrue(readActivity.contains("viewModel.changeTo(book, toc, onSuccess)"))
        assertTrue(audioActivity.contains("viewModel.changeTo(source, book, toc, onSuccess)"))
        assertTrue(infoActivity.contains("viewModel.changeTo(source, book, toc, onSuccess)"))
        assertTrue(mangaActivity.contains("viewModel.changeTo(book, toc, onSuccess)"))
        val audioTextMigration =
            appSource("book/audio/AudioPlayViewModel.kt")
                .section("internal fun changeToText(", "fun removeFromBookshelf")
        assertTrue(
            audioTextMigration.indexOf("repository.changeToText(") <
                audioTextMigration.indexOf("onSuccess()")
        )
        val audioRepository =
            appSource("book/audio/AudioPlayRepository.kt")
                .section("suspend fun changeToText(", "suspend fun removeFromBookshelf")
        assertTrue(audioRepository.contains("replaceBookAfterSourceChange(oldBook, book, toc)"))
        listOf(readActivity).forEach { changeTo ->
            assertTrue(
                changeTo.indexOf("appDb.bookDao.insert(book)") < changeTo.indexOf("onSuccess()")
            )
        }
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
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
