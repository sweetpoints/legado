package io.legado.app.model.webBook

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import org.junit.Test
import org.junit.Assert.*

class BookSearchScopeModelTest {
    @Test fun originalUiAliasPreservesSingleSourceEncodingAndUnpersistedScopeUpdates() {
        val source = BookSource(bookSourceUrl = "https://example.invalid/source", bookSourceName = "Example: name")
        val scope = io.legado.app.ui.book.search.SearchScope(source)
        assertTrue(scope is SearchScope); assertEquals("Example name::https://example.invalid/source", scope.toString()); assertEquals(listOf("Example name"), scope.displayNames); assertTrue(scope.isSource())
        scope.update("One,Two", postValue = false, save = false); assertFalse(scope.isSource()); assertFalse(scope.isAll()); assertEquals(listOf("One", "Two"), scope.displayNames)
        scope.update("", postValue = false, save = false); assertTrue(scope.isAll())
    }
    @Test fun sharedFilterRetainsObjectIdentityAndIgnoresIntroWhileTreatingRegexLikeTextLiterally() {
        val literal = SearchBook(bookUrl = "literal", name = ".* pattern", intro = "irrelevant")
        val kept = SearchBook(bookUrl = "kept", name = "Kept", intro = ".* pattern")
        val books = listOf(literal, kept)
        assertSame(books, filterBookSearchResults(books, " \r\n\t"))
        assertEquals(listOf(kept), filterBookSearchResults(books, ".*\n.*\n")); assertSame(kept, filterBookSearchResults(books, ".*").single())
    }
}
