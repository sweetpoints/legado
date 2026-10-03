package io.legado.app.ui.book.search

import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.filterBookSearchResults

/**
 * Existing callers retain their pure helper entry points after the View implementation is removed.
 */
internal typealias SearchCommandGate = io.legado.app.model.webBook.SearchCommandGate

internal fun filterSearchResults(books: List<SearchBook>, rawWords: String): List<SearchBook> =
    filterBookSearchResults(books, rawWords)
