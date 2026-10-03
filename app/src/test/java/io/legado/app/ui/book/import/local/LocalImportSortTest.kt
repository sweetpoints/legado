package io.legado.app.ui.book.import.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalImportSortTest {
    @Test
    fun filteringKeepsDirectoriesFirstAndUsesNaturalNamesForEqualMetadata() {
        val rows =
            listOf(
                LocalImportRow("ten", "book10.epub", false, 1, 1, false),
                LocalImportRow("two", "book2.epub", false, 1, 1, false),
                LocalImportRow("directory", "book-directory", true, 0, 0, false),
                LocalImportRow("other", "different.txt", false, 9, 9, false),
            )
        assertEquals(
            listOf("directory", "two", "ten"),
            sortedImportRows(rows, "book", 0).map { it.id },
        )
        assertEquals(
            listOf("directory", "other", "two", "ten"),
            sortedImportRows(rows, "", 1).map { it.id },
        )
        assertEquals(
            listOf("directory", "other", "two", "ten"),
            sortedImportRows(rows, "", 2).map { it.id },
        )
    }
}
