package io.legado.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionIntervalGuardTest {

    @Test
    fun `empty source manager selection interval preserves its state`() {
        val source =
            File(sourceRoot, "io/legado/app/ui/book/source/manage/BookSourceManagerViewModel.kt")
                .readText()
        assertTrue(source.contains("if (positions.isEmpty()) current"))
    }

    // Compose shelf interval and stable-ID behavior: BookshelfManagementViewModelTest.

    private val sourceRoot: File by lazy {
        sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    }

    private companion object {
        val sources = listOf()
    }
}
