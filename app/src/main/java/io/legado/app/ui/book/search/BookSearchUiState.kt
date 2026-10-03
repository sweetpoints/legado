package io.legado.app.ui.book.search

import io.legado.app.help.book.ReadRecordIndex
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchHistory
import io.legado.app.model.webBook.BookSearchMembership
import io.legado.app.model.webBook.BookSearchPreferences
import io.legado.app.model.webBook.BookSearchResult
import io.legado.app.model.webBook.BookSearchSuggestion
import io.legado.app.model.webBook.filterBookSearchSnapshots

internal data class BookSearchUiState(
    val loading: Boolean = true,
    val initializationFailed: Boolean = false,
    val draft: BookSearchDraft = BookSearchDraft(),
    val preferences: BookSearchPreferences = BookSearchPreferences(),
    val history: List<BookSearchHistory> = emptyList(),
    val suggestions: List<BookSearchSuggestion> = emptyList(),
    val membership: BookSearchMembership = BookSearchMembership(emptySet(), ReadRecordIndex.EMPTY),
    val groups: List<String> = emptyList(),
    val searching: Boolean = false,
    val durableRevision: Long = -1,
    val persistError: String? = null,
    val draftConflict: Boolean = false,
    val metadataError: String? = null,
    val commandError: String? = null,
    val settingsBusy: Boolean = false,
    val settingsError: String? = null,
) {
    val visibleResults: List<BookSearchResult>
        get() = filterBookSearchSnapshots(draft.results, preferences.resultFilter)

    val ready: Boolean
        get() = !loading && !initializationFailed && !draftConflict
}
