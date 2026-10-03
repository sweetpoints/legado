package io.legado.app.ui.book.explore

import io.legado.app.data.entities.SearchBook

internal data class ExplorePageRequest(
    val page: Int,
    internal val requestId: Long,
    internal val advancesNextPage: Boolean,
)

internal data class ExploreCategory(val title: String, val url: String)

internal data class ExploreListState(
    val books: List<SearchBook>,
    val firstPage: Int,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val prependCount: Int? = null,
)

internal class ExplorePaginationState {

    private var requestSequence = 0L
    private var activeRequestId: Long? = null

    val isLoading: Boolean
        @Synchronized get() = activeRequestId != null

    var nextPage = 1
        private set

    @Synchronized
    fun skipTo(page: Int): Boolean {
        if (page <= 0) return false
        activeRequestId = null
        nextPage = page
        return true
    }

    @Synchronized
    fun startNextPage(): ExplorePageRequest {
        return startRequest(nextPage, advancesNextPage = true)
    }

    @Synchronized
    fun startPage(page: Int): ExplorePageRequest? {
        if (page <= 0) return null
        return startRequest(page, advancesNextPage = false)
    }

    @Synchronized
    fun isActive(request: ExplorePageRequest): Boolean {
        return activeRequestId == request.requestId
    }

    @Synchronized
    fun complete(request: ExplorePageRequest): Boolean {
        if (!isActive(request)) return false
        if (request.advancesNextPage) nextPage = request.page + 1
        activeRequestId = null
        return true
    }

    @Synchronized
    fun fail(request: ExplorePageRequest): Boolean {
        if (!isActive(request)) return false
        activeRequestId = null
        return true
    }

    private fun startRequest(page: Int, advancesNextPage: Boolean): ExplorePageRequest {
        val requestId = ++requestSequence
        activeRequestId = requestId
        return ExplorePageRequest(page, requestId, advancesNextPage)
    }
}

internal fun <T> splitExploreCategoryRows(categories: List<T>): List<List<T>> {
    if (categories.isEmpty()) return emptyList()
    val rowCount = ((categories.size - 1) / 10 + 1).coerceAtMost(3)
    val perRow = categories.size / rowCount
    val extra = categories.size % rowCount
    var start = 0
    return List(rowCount) { row ->
        val end = start + perRow + if (row < extra) 1 else 0
        categories.subList(start, end).also { start = end }
    }
}
