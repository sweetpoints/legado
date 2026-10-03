package io.legado.app.model.webBook

import io.legado.app.help.book.ReadRecordIndex

/** Values detached from Room entities before entering search UI state. */
data class BookSearchHistory(
    val word: String,
    val usage: Int,
    val lastUseTime: Long,
)

data class BookSearchSuggestion(
    val bookId: String,
    val name: String,
    val author: String,
)

data class BookSearchMembership(
    val shelfKeys: Set<String>,
    val readRecords: ReadRecordIndex,
) {
    fun onShelf(bookId: String, name: String, author: String): Boolean {
        val identity = if (author.isNotBlank()) "$name-$author" else name
        return bookId in shelfKeys || identity in shelfKeys
    }

    fun hasRead(name: String, author: String): Boolean {
        return readRecords.contains(name, author)
    }
}
