package io.legado.app.ui.book.toc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocExpansionPersistenceLifecycleTest {

    @Test
    fun `toc preference outlives the directory view model and updates active readers`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt"
        ).readText()
        val setBlock = source.substringAfter("fun setTocExpanded")
            .substringBefore("fun startChapterListSearch")

        assertTrue(setBlock.contains("globalExecutor.execute"))
        assertTrue(setBlock.contains("repository.expanded(book.bookUrl, expanded)"))
        assertTrue(setBlock.contains("updateActiveReaderBooks(book.bookUrl, expanded)"))
    }

    @Test fun ownedDirectorySnapshotCannotMutateActiveReaderReadConfig() {
        val reader = io.legado.app.data.entities.Book(readConfig = io.legado.app.data.entities.Book.ReadConfig(reverseToc = true, tocExpanded = true))
        val snapshot = io.legado.app.data.repository.ownedTocBook(reader)
        snapshot.setTocExpanded(false)
        org.junit.Assert.assertTrue(reader.getTocExpanded())
        org.junit.Assert.assertFalse(snapshot.getTocExpanded())
        org.junit.Assert.assertTrue(snapshot.getReverseToc())
    }
    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
