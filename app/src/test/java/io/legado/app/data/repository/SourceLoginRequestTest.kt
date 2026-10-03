package io.legado.app.data.repository

import io.legado.app.constant.BookType
import org.junit.Assert.*
import org.junit.Test

class SourceLoginRequestTest {
    @Test
    fun exactReaderTypesUseLiveReaderContextWithoutAKey() {
        listOf(BookType.text, BookType.audio, BookType.video).forEach { type ->
            assertTrue(sourceLoginUsesReader(type))
            assertFalse(sourceLoginRequiresKey(SourceLoginRequest(type)))
        }
    }

    @Test
    fun combinedOrNonReaderTypeKeepsOriginalSourceKeyBranch() {
        listOf(0, BookType.image, BookType.text or BookType.audio).forEach { type ->
            assertFalse(sourceLoginUsesReader(type))
            assertTrue(sourceLoginRequiresKey(SourceLoginRequest(type)))
        }
    }

    @Test
    fun entryKeepsSourceTypeBookIdentityAndLargeKeysExactly() {
        val key = "https://" + "K".repeat(2000000)
        val request = SourceLoginRequest(type = "rssSource", key = key, bookUrl = "exact-book")
        assertEquals(key, request.key)
        assertEquals("rssSource", request.type)
        assertEquals("exact-book", request.bookUrl)
        assertTrue(sourceLoginRequiresKey(request))
    }
}
