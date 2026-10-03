package io.legado.app.ui.book.toc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocReverseNoDiffTest {

    @Test
    fun `full list replacement bypasses diff and suppresses intermediate callback`() {
        val adapter = source("app/src/main/java/io/legado/app/base/adapter/DiffRecyclerAdapter.kt")

        assertTrue(adapter.contains("fun setItemsNoDiff(items: List<ITEM>)"))
        assertTrue(adapter.contains("suppressNextListChange = true"))
        assertTrue(adapter.contains("asyncListDiffer.submitList(null)"))
        assertTrue(adapter.contains("asyncListDiffer.submitList(items.toMutableList())"))
    }

    @Test
    fun `only explicit table reorder selects no diff path`() {
        val viewModel = source("app/src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt")

        val chapters = (0..3).map { io.legado.app.data.entities.BookChapter(index = it, title = "Chapter $it") }
        val state = io.legado.app.model.book.toc.TocListState()
        state.setFullChapters(chapters, false); org.junit.Assert.assertEquals(listOf("chapter:0", "chapter:1", "chapter:2", "chapter:3"), state.showNormal(1).map { it.key })
        state.setFullChapters(chapters, false, reverseDisplay = true)
        org.junit.Assert.assertEquals(listOf("chapter:3", "chapter:2", "chapter:1", "chapter:0"), state.showNormal(1).map { it.key })
        assertTrue(viewModel.contains("replaceAll: Boolean = false"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
