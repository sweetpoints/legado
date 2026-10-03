package io.legado.app.ui.book.source.manage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceViewModelGroupContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/data/repository/BookSourceGroupRepository.kt")
            .readText()
            .replace("\r\n", "\n")
            .replace(Regex("\\s+"), " ")
    }

    @Test
    fun `group deletion delegates to exact group update`() {
        assertTrue(source.contains("renameGroupExact"))
        assertFalse(source.contains("source.removeGroup(group)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
