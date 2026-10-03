package io.legado.app.ui.rss.article

import org.junit.Test
import org.junit.Assert.*

class RssPaginationRestoreTest {
    @Test fun restoredNextPageUsesExactUrlAndAdvancesFromSavedPage() {
        val state = RssPaginationState(); state.restore(4, " page-url ")
        val request = state.startNextPage() as RssNextPageAction.Request
        assertEquals(5, request.page); assertEquals(" page-url ", request.url)
        assertTrue(state.completeNextPage(request, "next")); assertEquals(5, state.page)
    }
    @Test fun activeRequestCannotBeRestoredAndItsCompletionStillWorks() {
        val state = RssPaginationState(); val request = state.startRefresh() as RssRefreshAction.Request
        assertTrue(runCatching { state.restore(3, "next") }.isFailure)
        assertTrue(state.completeRefresh(request, "next"))
    }
    @Test fun oldCompletedTokenBecomesInvalidAfterRestoreAndCannotCommitOverNewRequest() {
        val state = RssPaginationState(); val old = state.startRefresh() as RssRefreshAction.Request
        assertTrue(state.completeRefresh(old, "old-next")); state.restore(8, "restored-next")
        assertFalse(state.isLatestResult(old.requestId)); assertFalse(state.completeRefresh(old, "wrong"))
        val next = state.startNextPage() as RssNextPageAction.Request
        assertFalse(state.failRefresh(old)); assertEquals(9, next.page); assertEquals("restored-next", next.url)
    }
    @Test fun failedNextPageRestoresRetryAtSamePageAndUrlAndEmptyUrlHasNoMore() {
        val state = RssPaginationState(); state.restore(2, "next", RssRetryTarget.NextPage)
        assertEquals(RssRetryTarget.NextPage, state.retryTarget)
        val request = state.startNextPage() as RssNextPageAction.Request
        assertEquals(3, request.page); assertTrue(state.failNextPage(request)); assertEquals(2, state.page)
        state.restore(2, " ", RssRetryTarget.Refresh); assertFalse(state.hasNextPage)
        assertTrue(state.startNextPage() is RssNextPageAction.NoMore); assertEquals(RssRetryTarget.Refresh, state.retryTarget)
    }
}
