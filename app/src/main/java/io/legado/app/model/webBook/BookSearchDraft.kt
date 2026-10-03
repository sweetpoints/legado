package io.legado.app.model.webBook

import androidx.annotation.Keep

/**
 * Complete editable/search payload stays in private storage; SavedState owns only the session UUID.
 */
@Keep
data class BookSearchDraft(
    val revision: Long = 0,
    val query: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val scope: String = "",
    val submittedKey: String = "",
    val results: List<BookSearchResult> = emptyList(),
    val manualStop: Boolean = false,
    val inputHelp: Boolean = true,
    val hasMore: Boolean = true,
    val searched: Int = 0,
    val total: Int = 0,
    val interrupted: Boolean = false,
    val filterDraft: String? = null,
    val filterSelection: Int = 0,
    val clearHistoryConfirmation: Boolean = false,
    val emptyScopeConfirmation: Boolean = false,
    val initialEntryAccepted: Boolean = false,
    val effects: List<BookSearchReceipt> = emptyList(),
)

@Keep
enum class BookSearchEffect {
    BookInfo,
    Scope,
    Sources,
    Log,
    Toast,
}

@Keep
data class BookSearchReceipt(
    val id: String,
    val effect: BookSearchEffect,
    val resultId: String? = null,
    val bookId: String? = null,
    val name: String? = null,
    val author: String? = null,
    val text: String? = null,
)
