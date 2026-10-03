package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface BookDetailChildServicesRepository {
    suspend fun discardTemporary(book: BookDetailBook): Boolean

    suspend fun folder(uri: String)

    suspend fun deferHighlight(book: BookDetailBook): Boolean
}

class RoomBookDetailChildServicesRepository(
    private val database: AppDatabase = appDb,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookDetailChildServicesRepository {
    override suspend fun discardTemporary(book: BookDetailBook) =
        withContext(io + NonCancellable) {
            val expected = book.materializeBook()
            database.runInTransaction<Boolean> {
                val current = database.bookDao.getBook(book.bookUrl) ?: return@runInTransaction true
                // A changed progress/group/cover/metadata owner is retained rather than deleted on
                // a late Toc cancellation.
                if (GSON.toJson(current.copy()) != GSON.toJson(expected.copy()))
                    return@runInTransaction false
                database.bookDao.delete(current)
                true
            }
        }

    override suspend fun folder(uri: String) =
        withContext(io) { AppConfig.defaultBookTreeUri = uri }

    override suspend fun deferHighlight(book: BookDetailBook) =
        withContext(io) {
            !book.isAudio &&
                !book.isVideo &&
                (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
        }
}
