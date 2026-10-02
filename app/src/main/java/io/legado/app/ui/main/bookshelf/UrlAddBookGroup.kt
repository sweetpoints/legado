package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/** Compatibility entry points; the transfer repository owns the pure group migration rules. */
internal fun mergeBookGroupForUrlAdd(currentGroupMask: Long, selectedShelfGroupId: Long): Long =
    io.legado.app.data.repository.mergeBookGroupForUrlAdd(currentGroupMask, selectedShelfGroupId)
internal fun migrateBookForUrlAdd(existingBook: Book, fetchedBook: Book, toc: List<BookChapter>, selectedShelfGroupId: Long): Book =
    io.legado.app.data.repository.migrateBookForUrlAdd(existingBook, fetchedBook, toc, selectedShelfGroupId)
