package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.book.ContentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Capture before queueing so a delayed save cannot read another book or chapter from the engine.
 */
data class MangaProgressUpdate(
    val bookUrl: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val chapterTime: Long,
    val pageChanged: Boolean,
)

data class MangaSavedProgress(
    val chapterIndex: Int,
    val pageIndex: Int,
    val chapterTitle: String?,
    val chapterTime: Long,
)

fun interface MangaProgressRepository {
    suspend fun save(update: MangaProgressUpdate): MangaSavedProgress?
}

class RoomMangaProgressRepository(private val database: AppDatabase = appDb) :
    MangaProgressRepository {
    override suspend fun save(update: MangaProgressUpdate): MangaSavedProgress? =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                // The engine's book is a reading snapshot. Metadata may have changed since then.
                val freshBook =
                    database.bookDao.getBook(update.bookUrl) ?: return@withTransaction null
                val chapterChanged = freshBook.durChapterIndex != update.chapterIndex
                freshBook.lastCheckCount = 0
                freshBook.durChapterTime = update.chapterTime
                freshBook.durChapterIndex = update.chapterIndex
                freshBook.durChapterPos = update.pageIndex
                if (!update.pageChanged || chapterChanged) {
                    database.bookChapterDao.getChapter(update.bookUrl, update.chapterIndex)?.let {
                        chapter ->
                        freshBook.durChapterTitle =
                            chapter.getDisplayTitle(
                                ContentProcessor.get(freshBook.name, freshBook.origin)
                                    .getTitleReplaceRules(),
                                freshBook.getUseReplaceRule(),
                                replaceBook = freshBook.toReplaceBook(),
                            )
                    }
                }
                // Update only a transaction-fresh entity; a removed book is never reinserted.
                database.bookDao.update(freshBook)
                MangaSavedProgress(
                    chapterIndex = freshBook.durChapterIndex,
                    pageIndex = freshBook.durChapterPos,
                    chapterTitle = freshBook.durChapterTitle,
                    chapterTime = freshBook.durChapterTime,
                )
            }
        }
}
