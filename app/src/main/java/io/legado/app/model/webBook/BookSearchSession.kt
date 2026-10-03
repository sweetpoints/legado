package io.legado.app.model.webBook

/** Engine progress is distinct from private UI draft state. */
data class BookSearchSession(
    val generation: Long = 0,
    val key: String = "",
    val scope: String = "",
    val searching: Boolean = false,
    val searched: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = true,
    val results: List<BookSearchResult> = emptyList(),
    val finishedEmpty: Boolean = false,
    val error: String? = null,
)
