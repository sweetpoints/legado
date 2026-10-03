package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BookSearchResultTest {
    @Test
    fun completeProjectionRetainsMergedOriginsAndMetadataWithoutMutableEntityAliasing() {
        val original =
            SearchBook(
                    bookUrl = "url",
                    origin = "source",
                    originName = "Name",
                    type = 7,
                    name = "Title",
                    author = "Author",
                    kind = "kind",
                    coverUrl = "cover",
                    intro = "intro",
                    wordCount = "100",
                    latestChapterTitle = "latest",
                    tocUrl = "toc",
                    time = 42,
                    variable = "variables",
                    originOrder = 3,
                    chapterWordCountText = "words",
                    chapterWordCount = 10,
                    respondTime = 20,
                )
                .apply {
                    addOrigin("other")
                    infoHtml = "info"
                    tocHtml = "toc-html"
                }
        val result = BookSearchResult.from(original)
        original.name = "changed"
        original.origins.clear()
        original.infoHtml = "changed"
        assertEquals("Title", result.name)
        assertEquals(listOf("source", "other"), result.origins)

        val copy = result.toSearchBook()
        assertEquals("info", copy.infoHtml)
        assertEquals("toc-html", copy.tocHtml)
        assertEquals("variables", copy.variable)
        assertEquals(10, copy.chapterWordCount)
        assertEquals(20, copy.respondTime)
        assertEquals("words", copy.chapterWordCountText)
        assertEquals(42L, copy.time)
        assertEquals(7, copy.type)
        assertEquals("toc", copy.tocUrl)
        assertEquals("intro", copy.intro)
        copy.origins.clear()
        copy.name = "mutable"
        assertEquals("Title", result.name)
        assertEquals(2, result.origins.size)
    }

    @Test
    fun stableIdentityMatchesLegacyNameAuthorAndAvoidsConcatenationCollision() {
        val first = BookSearchResult.from(SearchBook("a", name = "ab", author = "c"))
        val same = BookSearchResult.from(SearchBook("b", name = "ab", author = "c"))
        val other = BookSearchResult.from(SearchBook("c", name = "a", author = "bc"))
        assertEquals(first.id, same.id)
        assertNotEquals(first.id, other.id)
        // Full content equality observes metadata despite legacy entity URL-only equals.
        assertNotEquals(first, same)
    }

    @Test
    fun immutableFilteringMatchesLegacyPlainTextRulesAndRetainsEmptyFilterListIdentity() {
        val books =
            listOf(
                SearchBook("a", name = "Title [x]"),
                SearchBook("b", author = "Author"),
                SearchBook("c", kind = "Fantasy"),
                SearchBook("d", intro = "author fantasy [x]", wordCount = "Fantasy"),
            )
        val snapshots = books.map(BookSearchResult::from)
        assertSame(snapshots, filterBookSearchSnapshots(snapshots, "  "))
        val filter = " [x] \n AUTHOR\n fantasy\n[x]"
        assertEquals(
            filterBookSearchResults(books, filter).map { it.bookUrl },
            filterBookSearchSnapshots(snapshots, filter).map { it.bookUrl },
        )
        assertEquals(listOf("d"), filterBookSearchSnapshots(snapshots, filter).map { it.bookUrl })
    }
}
