package io.legado.app.ui.book.manage

import io.legado.app.utils.mergeFilteredOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfManageOrderTest {

    @Test
    fun `filtered drag preserves hidden slots and uses current rows`() {
        val allItems = listOf(
            Item("a", "a-latest"),
            Item("x", "x-latest"),
            Item("b", "b-latest"),
            Item("y", "y-latest"),
        )
        val orderedItems = listOf(
            Item("missing", "missing"),
            Item("y", "y-stale"),
            Item("x", "x-stale"),
            Item("y", "y-duplicate"),
        )

        val result = mergeFilteredOrder(allItems, orderedItems) { it.key }

        assertEquals(
            listOf("a-latest", "y-latest", "b-latest", "x-latest"),
            result.map(Item::value),
        )
    }

    // BookshelfManagementRepositoryTest and BookshelfManagementRoomTest execute actual fresh-ID
    // order transactions; gesture commit/cancel behavior is checked by the management VM/UI tests.

    private data class Item(val key: String, val value: String)

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
}
