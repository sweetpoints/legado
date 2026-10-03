package io.legado.app.data.repository

import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreResultsProjectionTest {
    @Test
    fun parserEntityMutationCannotChangePublishedRowOrCompleteMetadata() {
        val parsed =
            SearchBook(
                bookUrl = "https://book/one",
                origin = "source",
                name = "Original",
                author = "Author",
                kind = "Kind one,Kind two",
                intro = "  Original intro  ",
                tocUrl = "https://toc/one",
                variable = "{\"token\":\"original\"}",
            )
        parsed.infoHtml = "Original info HTML"
        parsed.tocHtml = "Original toc HTML"
        val row = exploreResultsRow(parsed)
        parsed.name = "Changed"
        parsed.variable = "{\"token\":\"changed\"}"
        parsed.infoHtml = "Changed HTML"
        val restored = GSON.fromJsonObject<SearchBook>(row.metadata).getOrThrow()
        assertEquals("Original", row.name)
        assertEquals("Original", restored.name)
        assertEquals("{\"token\":\"original\"}", restored.variable)
        assertEquals("https://toc/one", restored.tocUrl)
        assertEquals("Original info HTML", restored.infoHtml)
        assertEquals("Original toc HTML", restored.tocHtml)
    }

    @Test
    fun identityMatchesOriginalUrlDeduplicationAndDoesNotNormalizeInput() {
        val first = exploreResultsRow(SearchBook(bookUrl = "https://book/one", name = "First"))
        val duplicate =
            exploreResultsRow(SearchBook(bookUrl = "https://book/one", name = "Changed"))
        val different = exploreResultsRow(SearchBook(bookUrl = "https://book/one ", name = "First"))
        assertEquals(first.key, duplicate.key)
        assertEquals(64, first.key.length)
        assertNotEquals(first.key, different.key)
    }

    @Test
    fun membershipRetainsUrlAndExactNameAuthorMatchingIncludingBlankAuthor() {
        val row = exploreResultsRow(SearchBook(bookUrl = "book", name = "Name", author = "Author"))
        assertTrue(exploreResultsInShelf(row, setOf("book")))
        assertTrue(exploreResultsInShelf(row, setOf("Name-Author")))
        assertFalse(exploreResultsInShelf(row, setOf("Name")))
        val noAuthor = row.copy(author = "")
        assertTrue(exploreResultsInShelf(noAuthor, setOf("Name")))
        assertFalse(exploreResultsInShelf(noAuthor, setOf("Name-Author")))
    }
}
