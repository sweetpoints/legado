package io.legado.app.model.book

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContentSearchMatcherTest {
    @Test fun literalResultsAreNonOverlappingAndRetainReaderOffsetsAndChapterMetadata() = runTest {
        val results = findContentSearchMatches("aaaa", "aa", 7, "Chapter", false)
        assertEquals(listOf(0, 2), results.map { it.queryIndexInChapter }); assertEquals(listOf(0, 1), results.map { it.resultCountWithinChapter })
        assertEquals(listOf(0, 2), results.map { it.queryIndexInResult }); assertTrue(results.all { it.chapterIndex == 7 && it.chapterTitle == "Chapter" && !it.isRegex })
        assertEquals(2, results.map { it.id }.distinct().size)
    }
    @Test fun snippetsKeepTwentyCharactersAtEachSideAndClipBookBeginningAndEnd() = runTest {
        val content = "x".repeat(30) + "query" + "y".repeat(30)
        val match = findContentSearchMatches(content, "query", 0, "Title", false).single()
        assertEquals("x".repeat(20) + "query" + "y".repeat(20), match.resultText)
        assertEquals(30, match.queryIndexInChapter); assertEquals(20, match.queryIndexInResult)
        val edges = findContentSearchMatches("q" + "x".repeat(40) + "q", "q", 0, "Title", false)
        assertEquals(0, edges.first().queryIndexInResult); assertEquals(20, edges.last().queryIndexInResult)
        assertEquals(21, edges.first().resultText.length); assertEquals(21, edges.last().resultText.length)
    }
    @Test fun regexResultsUsePatternStringLengthForLegacySnippetWindowAndInvalidPatternReturnsEmpty() = runTest {
        val matches = findContentSearchMatches("x".repeat(30) + "aaaaaa" + "y".repeat(40), "a+", 3, "Title", true)
        val result = matches.single(); assertTrue(result.isRegex); assertEquals(30, result.queryIndexInChapter)
        assertEquals(42, result.resultText.length); assertEquals(20..25, contentSearchHighlight(result))
        assertTrue(findContentSearchMatches("text", "[", 0, "Title", true).isEmpty())
    }
    @Test fun zeroLengthRegexRetainsAllPositionsWhileBlankQueryNeverSearches() = runTest {
        assertEquals(listOf(0, 1), findContentSearchMatches("aa", "(?=a)", 0, "Title", true).map { it.queryIndexInChapter })
        assertTrue(findContentSearchMatches("text", "  ", 0, "Title", false).isEmpty())
    }
    @Test fun utf16OffsetsAndOriginalTextAreRetainedForReaderPositionAndHtmlLikeCharacters() = runTest {
        val match = findContentSearchMatches("😀猫<&>猫", "猫", 4, "Title", false).first()
        assertEquals(2, match.queryIndexInChapter); assertEquals("😀猫<&>猫", match.resultText); assertEquals(2..2, contentSearchHighlight(match))
    }
    @Test fun highlightPrefersTwentyCharacterOffsetAndIdentitiesRemainSmallWithVeryLargeQuery() = runTest {
        val match = ContentSearchMatch("id", resultText = "q" + "x".repeat(19) + "q", query = "q")
        assertEquals(20..20, contentSearchHighlight(match))
        val query = "q".repeat(1200000); val first = contentSearchIdentity(query, 1, 2, 3)
        assertTrue(first.length < 100); assertEquals(first, contentSearchIdentity(query, 1, 2, 3))
        assertNotEquals(first, contentSearchIdentity(query, 1, 2, 4))
    }
    @Test fun canceledMatcherThrowsCancellationInsteadOfReturningPartialRegexMatches() = runTest {
        var returned = false
        val job = launch { currentCoroutineContext().cancel(); findContentSearchMatches("aaaa", "a", 0, "Title", true); returned = true }
        job.join(); assertTrue(job.isCancelled); assertFalse(returned)
    }
}
