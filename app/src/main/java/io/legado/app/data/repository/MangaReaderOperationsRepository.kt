package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.globalExecutor
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.writeFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Immutable identities are captured before IO, so work never consults the current reader. */
data class MangaImageSaveRequest(
    val bookUrl: String,
    val bookSnapshot: String,
    val sourceSnapshot: String?,
    val imageUrl: String,
    val directoryUri: String,
)

data class MangaChapterRefreshRequest(
    val bookUrl: String,
    val chapterIndex: Int,
    val pageIndex: Int,
)

interface MangaReaderOperationsRepository {
    suspend fun saveImage(request: MangaImageSaveRequest)

    suspend fun refreshChapter(request: MangaChapterRefreshRequest): Boolean

    suspend fun removeFromBookshelf(bookUrl: String)
}

class DefaultMangaReaderOperationsRepository : MangaReaderOperationsRepository {
    override suspend fun saveImage(request: MangaImageSaveRequest): Unit =
        withContext(Dispatchers.IO) {
            // Detached full snapshots preserve transient books and original source request headers.
            val book = GSON.fromJsonObject<Book>(request.bookSnapshot).getOrThrow()
            check(book.bookUrl == request.bookUrl)
            val source =
                request.sourceSnapshot?.let {
                    GSON.fromJsonObject<BookSource>(it).getOrThrow()
                }
            BookHelp.saveImage(source, book, request.imageUrl)
            val image = BookHelp.getImage(book, request.imageUrl)
            if (!image.isFile) throw NoStackTraceException("图片下载失败")
            try {
                FileDoc.fromDir(Uri.parse(request.directoryUri))
                    .createFileIfNotExist(image.name)
                    .writeFile(image)
            } catch (error: Exception) {
                // A delayed failed export cannot erase a newer directory selection.
                withContext(Dispatchers.Main + NonCancellable) {
                    val cache = ACache.get()
                    if (cache.getAsString(AppConst.imagePathKey) == request.directoryUri) {
                        cache.remove(AppConst.imagePathKey)
                    }
                }
                throw error
            }
        }

    override suspend fun refreshChapter(request: MangaChapterRefreshRequest): Boolean =
        withContext(Dispatchers.IO) {
            val book = appDb.bookDao.getBook(request.bookUrl) ?: return@withContext false
            val chapter =
                appDb.bookChapterDao.getChapter(request.bookUrl, request.chapterIndex)
                    ?: return@withContext false
            BookHelp.delContent(book, chapter)
            true
        }

    override suspend fun removeFromBookshelf(bookUrl: String) {
        val queuedSavesFinished = CompletableDeferred<Unit>()
        globalExecutor.execute { queuedSavesFinished.complete(Unit) }
        queuedSavesFinished.await()
        // Once deletion starts, finish the accepted operation with the latest saved progress.
        withContext(Dispatchers.IO + NonCancellable) {
            appDb.bookDao.getBook(bookUrl)?.delete()
        }
    }
}
