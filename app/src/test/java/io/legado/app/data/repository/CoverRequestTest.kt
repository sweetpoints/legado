package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.image.CoverTitleKey
import io.legado.app.model.CoverFontSizes
import org.junit.Assert.*
import org.junit.Test

class CoverRequestTest {
    @Test
    fun sourceAndBookSnapshotDoNotFollowMutableEntities() {
        val search =
            SearchBook(
                name = "title",
                author = "author",
                origin = "https://source.test",
                coverUrl = "https://source.test/cover",
            )
        val request = CoverRequest.from(search, true)
        search.name = "changed"
        search.coverUrl = "changed"
        assertEquals("title", request.name)
        assertEquals("https://source.test/cover", request.path)
        assertEquals("https://source.test", request.sourceOrigin)
        assertTrue(request.loadOnlyWifi)
    }

    @Test
    fun persistedLocalCoverPrecedesOtherCoversAndOmitsCredentials() {
        val book =
            Book(
                name = "title",
                coverUrl = "https://source.test/cover",
                customCoverUrl = "https://source.test/custom",
                origin = "https://source.test",
            )
        book.persistedCoverUrl = "/local/persisted.jpg"
        val request = CoverRequest.from(book)
        assertEquals("/local/persisted.jpg", request.path)
        assertNull(request.sourceOrigin)
    }

    @Test
    fun crossOriginCustomCoverOmitsSourceWhileSameOriginRetainsIt() {
        val book = Book(origin = "https://source.test", customCoverUrl = "https://other.test/cover")
        assertNull(CoverRequest.from(book).sourceOrigin)
        book.customCoverUrl = "https://source.test/custom"
        assertEquals("https://source.test", CoverRequest.from(book).sourceOrigin)
    }

    @Test
    fun punctuationAndBlankPathUseSameCoverRules() {
        assertNull(CoverRequest(path = "  ").normalizedPath)
        assertEquals("Title Author", normalizeComposeCoverText(" Title, Author ", false))
        assertEquals("Title, Author", normalizeComposeCoverText(" Title, Author ", true))
    }

    @Test
    fun titleKeySeparatesAmbiguousNamesDimensionsColorsFontsAndDirections() {
        val key = CoverTitleKey("ab", "c", 90, 120, false, true, true, 1, 2, null, "font-a")
        assertNotEquals(key, key.copy(name = "a", author = "bc"))
        assertNotEquals(key, key.copy(width = 180))
        assertNotEquals(key, key.copy(backgroundColor = 3))
        assertNotEquals(key, key.copy(horizontal = true))
        assertNotEquals(key, key.copy(adaptive = false))
        assertNotEquals(key, key.copy(fontKey = "font-b"))
        assertNotEquals(key, key.copy(sizes = CoverFontSizes(100, 80, 100, 80)))
    }
}
