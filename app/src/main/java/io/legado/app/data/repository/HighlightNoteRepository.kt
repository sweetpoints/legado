package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.model.ReadBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface HighlightNoteRepository {
    suspend fun save(highlight: BookHighlight)

    suspend fun delete(highlight: BookHighlight)
}

class ReaderHighlightNoteRepository : HighlightNoteRepository {
    override suspend fun save(highlight: BookHighlight): Unit =
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { appDb.bookHighlightDao.update(highlight) }
            withContext(Dispatchers.Main.immediate) { ReadBook.applyUpdatedHighlight(highlight) }
        }

    override suspend fun delete(highlight: BookHighlight): Unit =
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { appDb.bookHighlightDao.delete(highlight) }
            withContext(Dispatchers.Main.immediate) { ReadBook.applyRemovedHighlight(highlight) }
        }
}
