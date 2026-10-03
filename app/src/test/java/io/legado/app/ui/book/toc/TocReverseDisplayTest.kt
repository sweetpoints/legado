package io.legado.app.ui.book.toc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TocReverseDisplayTest {

    @Test
    fun `reverse display reaches the chapter host in reverse order`() {
        val viewModel = source("app/src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt")

        val chapters =
            (0..3).map {
                io.legado.app.data.entities.BookChapter(index = it, title = "Chapter $it")
            }
        val state = io.legado.app.model.book.toc.TocListState()
        state.setFullChapters(chapters, false)
        org.junit.Assert.assertEquals(
            listOf("chapter:0", "chapter:1", "chapter:2", "chapter:3"),
            state.showNormal(1).map { it.key },
        )
        state.setFullChapters(chapters, false, reverseDisplay = true)
        org.junit.Assert.assertEquals(
            listOf("chapter:3", "chapter:2", "chapter:1", "chapter:0"),
            state.showNormal(1).map { it.key },
        )
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
