package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import org.junit.Test
import org.junit.Assert.*

class BookSearchResultTest {
    @Test fun completeProjectionRetainsMergedOriginsAndMetadataWithoutMutableEntityAliasing() {
        val original = SearchBook("url", "source", "Name", 7, "Title", "Author", "kind", "cover", "intro", "100", "latest", "toc", 42, "variables", 3, "words", 10, 20).apply { addOrigin("other"); infoHtml = "info"; tocHtml = "toc-html" }
        val result = BookSearchResult.from(original); original.name = "changed"; original.origins.clear(); original.infoHtml = "changed"
        assertEquals("Title", result.name); assertEquals(listOf("source", "other"), result.origins)
        val copy = result.toSearchBook(); assertEquals("info", copy.infoHtml); assertEquals("toc-html", copy.tocHtml); assertEquals("variables", copy.variable); assertEquals(10, copy.chapterWordCount); assertEquals(20, copy.respondTime); assertEquals("words", copy.chapterWordCountText); assertEquals(42L, copy.time); assertEquals(7, copy.type); assertEquals("toc", copy.tocUrl); assertEquals("intro", copy.intro)
        copy.origins.clear(); copy.name = "mutable"; assertEquals("Title", result.name); assertEquals(2, result.origins.size)
    }
    @Test fun stableIdentityMatchesLegacyNameAuthorAndAvoidsConcatenationCollision() {
        val first = BookSearchResult.from(SearchBook("a", name = "ab", author = "c")); val same = BookSearchResult.from(SearchBook("b", name = "ab", author = "c")); val other = BookSearchResult.from(SearchBook("c", name = "a", author = "bc"))
        assertEquals(first.id, same.id); assertNotEquals(first.id, other.id)
        assertNotEquals(first, same) // Full content equality observes metadata despite legacy entity URL-only equals.
    }
    @Test fun immutableFilteringMatchesLegacyPlainTextRulesAndRetainsEmptyFilterListIdentity() {
        val rows = listOf(SearchBook("a", name = "Title [x]"), SearchBook("b", author = "Author"), SearchBook("c", kind = "Fantasy"), SearchBook("d", intro = "author fantasy [x]", wordCount = "Fantasy"))
        val snapshots = rows.map(BookSearchResult::from)
        assertSame(snapshots, filterBookSearchSnapshots(snapshots, "  "))
        val filter = " [x] \n AUTHOR\n fantasy\n[x]"
        assertEquals(filterBookSearchResults(rows, filter).map { it.bookUrl }, filterBookSearchSnapshots(snapshots, filter).map { it.bookUrl }); assertEquals(listOf("d"), filterBookSearchSnapshots(snapshots, filter).map { it.bookUrl })
    }
}
