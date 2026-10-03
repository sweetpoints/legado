package io.legado.app.ui.components.cover

import io.legado.app.data.entities.BookshelfBook
import org.junit.Assert.*
import org.junit.Test

class CoverGroupContentTest {
    @Test
    fun customCoverAndEmptyGroupsUseOneCover() {
        assertEquals(
            "custom",
            (createCoverGroupContent("custom", listOf(book(1))) as CoverGroupContent.Single)
                .request
                .path,
        )
        assertNull(
            (createCoverGroupContent(null, emptyList()) as CoverGroupContent.Single).request.path
        )
    }

    @Test
    fun gridUsesOnlyFirstFourInCallerOrder() {
        val grid =
            createCoverGroupContent(null, listOf(book(5), book(4), book(3), book(2), book(1)))
                as CoverGroupContent.Grid
        assertEquals(listOf("5", "4", "3", "2"), grid.previews.map { it?.name })
    }

    @Test
    fun missingSlotsRemainNullRatherThanRepeatingBooks() {
        val grid = createCoverGroupContent("  ", listOf(book(1))) as CoverGroupContent.Grid
        assertEquals(4, grid.previews.size)
        assertEquals("1", grid.previews[0]?.name)
        assertTrue(grid.previews.drop(1).all { it == null })
    }

    @Test
    fun groupPreviewKeepsBookPersistedCoverAndSourcePolicy() {
        val grid =
            createCoverGroupContent(
                null,
                listOf(book(1).copy(persistedCoverUrl = "/persisted.jpg")),
            )
                as CoverGroupContent.Grid
        assertEquals("/persisted.jpg", grid.previews[0]?.path)
        assertNull(grid.previews[0]?.sourceOrigin)
    }

    private fun book(index: Int) =
        BookshelfBook(
            "url$index",
            "source$index",
            "$index",
            "author",
            "cover$index",
            null,
            0,
            0,
            false,
            0,
            0,
            0,
        )
}
