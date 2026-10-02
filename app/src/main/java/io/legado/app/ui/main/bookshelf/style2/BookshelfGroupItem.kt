package io.legado.app.ui.main.bookshelf.style2

import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookshelfBook

data class BookshelfGroupItem(
    val group: BookGroup,
    val previewBooks: List<BookshelfBook>,
) {
    val coverSignature: List<List<String?>> = previewBooks.map { book ->
        listOf(
            book.bookUrl,
            book.displayCover,
            book.name,
            book.author,
            book.coverSourceOrigin,
        )
    }
}

internal fun buildBookshelfGroupItems(groups: List<BookGroup>, books: List<BookshelfBook>): List<BookshelfGroupItem> =
    groups.map { group -> BookshelfGroupItem(group.copy(), io.legado.app.data.repository.folderPreviewBooks(group, books,
        group.bookSort.takeIf { it >= 0 } ?: io.legado.app.help.config.AppConfig.bookshelfSort)) }
internal fun sortBookshelfBooks(books: List<BookshelfBook>, sort: Int): List<BookshelfBook> =
    io.legado.app.data.repository.sortFolderPreviewBooks(books, sort)
