package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.SearchBook
import io.legado.app.model.book.ChangeSourceResultOptions as Policy

/** Compatibility facade for the existing full-book source picker. */
internal object ChangeSourceResultOptions {
    const val FILTER_OFF = Policy.FILTER_OFF
    const val FILTER_ABSOLUTE = Policy.FILTER_ABSOLUTE
    const val FILTER_RELATIVE = Policy.FILTER_RELATIVE

    fun apply(
        books: List<SearchBook>,
        filterMode: Int,
        minimum: Int,
        maximum: Int,
        referenceWordCount: Int?,
        comparator: Comparator<SearchBook>,
        pinnedBookUrl: String? = null,
    ) =
        Policy.apply(
            books,
            filterMode,
            minimum,
            maximum,
            referenceWordCount,
            comparator,
            pinnedBookUrl,
        )

    fun responseTimeComparator(fallback: Comparator<SearchBook>) =
        Policy.responseTimeComparator(fallback)

    fun measuredFirstComparator(fallback: Comparator<SearchBook>) =
        Policy.measuredFirstComparator(fallback)
}
