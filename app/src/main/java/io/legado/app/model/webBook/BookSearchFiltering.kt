package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook

internal fun filterBookSearchResults(
    books: List<SearchBook>,
    rawWords: String,
): List<SearchBook> {
    if (rawWords.isBlank()) return books
    val words = rawWords.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
    return books.filterNot { book ->
        words.any { word ->
            book.name.contains(word, ignoreCase = true) ||
                    book.author.contains(word, ignoreCase = true) ||
                    book.kind?.contains(word, ignoreCase = true) == true
        }
    }
}
