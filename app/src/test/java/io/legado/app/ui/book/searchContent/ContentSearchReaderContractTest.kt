package io.legado.app.ui.book.searchContent

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentSearchReaderContractTest {
    @Test
    fun immutableProjectionRoundTripsEveryPublicReaderResultFieldWithStableDistinctIncomingIds() {
        val result =
            SearchResult(
                resultCount = 90,
                resultCountWithinChapter = 3,
                resultText = "needle body",
                chapterTitle = "Title",
                query = "needle",
                pageSize = 12,
                chapterIndex = 7,
                pageIndex = 4,
                queryIndexInResult = 2,
                queryIndexInChapter = 1234,
                isRegex = true,
            )
        assertEquals(result, result.contentMatch(0).readerResult())
        assertEquals("incoming-0", result.contentMatch(0).id)
        assertEquals("incoming-1", result.contentMatch(1).id)
    }
}
