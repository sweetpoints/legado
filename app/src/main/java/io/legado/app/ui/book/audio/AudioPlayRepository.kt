package io.legado.app.ui.book.audio

import android.app.Application
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.help.book.addType
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All persistence, source evaluation and cover decoding run off the main thread. */
internal class AudioPlayRepository(private val context: Application) {
    companion object {
        private val engineWrites = Mutex()
        private val requests = AudioRequestGate()
    }

    fun beginRequest(): Long = requests.claim()

    fun retireRequest(owner: Long) = requests.retire(owner)

    fun ownsRequest(owner: Long): Boolean = requests.current(owner)

    private fun identity(book: Book?): String? = book?.let {
        "${it.bookUrl.length}:${it.bookUrl}${it.origin}"
    }

    suspend fun initialize(requestedBookUrl: String?, owner: Long): Boolean? =
        withContext(IO) {
            engineWrites.withLock {
                if (!requests.current(owner)) return@withLock null
                val cachedBook = AudioPlay.book
                val cachedInBookshelf = AudioPlay.inBookshelf
                var databaseBook =
                    requestedBookUrl?.takeIf { it.isNotBlank() }?.let(appDb.bookDao::getBook)
                val resolvedBook =
                    resolveAudioPlayBook(requestedBookUrl, cachedBook, Book::bookUrl) {
                        databaseBook
                    } ?: return@withLock null
                var targetBook =
                    databaseBook?.takeIf { resolvedBook === cachedBook } ?: resolvedBook
                if (
                    !requestedBookUrl.isNullOrBlank() &&
                        databaseBook == null &&
                        targetBook === cachedBook
                ) {
                    val temporaryBook = targetBook.copy().apply { addType(BookType.notShelf) }
                    if (appDb.bookDao.insertIgnore(temporaryBook) == -1L) {
                        databaseBook =
                            appDb.bookDao.getBook(requestedBookUrl) ?: return@withLock null
                        targetBook = checkNotNull(databaseBook)
                    } else targetBook = temporaryBook
                }
                if (
                    !requests.publish(owner) {
                        AudioPlay.inBookshelf =
                            if (requestedBookUrl.isNullOrBlank()) cachedInBookshelf
                            else !(databaseBook ?: targetBook).isNotShelf
                    }
                )
                    return@withLock null
                initBook(targetBook, owner)
            }
        }

    private suspend fun initBook(book: Book, owner: Long): Boolean {
        val initial =
            requests.publish(owner) {
                if (AudioPlay.book?.bookUrl == book.bookUrl)
                    AudioPlay.upData(book, preserveProgress = true)
                else AudioPlay.resetData(book)
            }
        if (!initial) return false
        var engineIdentity = identity(AudioPlay.book)
        val workingBook = book.copy(readConfig = book.readConfig?.copy())
        if (AudioPlay.chapterSize == 0 && workingBook.tocUrl.isEmpty()) {
            val source = AudioPlay.bookSource ?: return false
            if (!loadBookInfo(workingBook, source)) return false
            if (
                !requests.publish(owner, engineIdentity, { identity(AudioPlay.book) }) {
                    // The parser works on a detached snapshot, so an old response cannot mutate the
                    // engine.
                    AudioPlay.book = workingBook.copy(readConfig = workingBook.readConfig?.copy())
                }
            )
                return false
            engineIdentity = identity(AudioPlay.book)
        }
        if (!requests.current(owner)) return false
        if (AudioPlay.chapterSize == 0) {
            val source = AudioPlay.bookSource ?: return false
            if (!loadChapterList(workingBook, source, owner, engineIdentity)) return false
        }
        return requests.current(owner) && AudioPlay.chapterSize > 0
    }

    private suspend fun loadBookInfo(book: Book, source: BookSource): Boolean {
        try {
            WebBook.getBookInfoAwait(source, book)
            return true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.put("详情页出错: ${error.localizedMessage}", error, true)
            return false
        }
    }

    private suspend fun loadChapterList(
        book: Book,
        source: BookSource,
        owner: Long,
        engineIdentity: String?,
    ): Boolean {
        try {
            val oldBook = book.copy(readConfig = book.readConfig?.copy())
            val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
            if (chapters.isEmpty()) return false
            // Preserve an accepted database result even if a replacement request now owns the
            // engine.
            withContext(IO + NonCancellable) {
                if (oldBook.bookUrl == book.bookUrl) book.update()
                else appDb.bookDao.replace(oldBook, book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
            }
            return requests.publish(owner, engineIdentity, { identity(AudioPlay.book) }) {
                AudioPlay.book = book
                AudioPlay.chapterSize = chapters.size
                AudioPlay.simulatedChapterSize = book.simulatedTotalChapterNum()
                AudioPlay.upDurChapter()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
    }

    suspend fun source(owner: Long) =
        withContext(IO) {
            val book = AudioPlay.book
            val expected = identity(book)
            val source = book?.getBookSource()
            requests.publish(owner, expected, { identity(AudioPlay.book) }) {
                AudioPlay.setBookSource(source)
            }
            source
        }

    suspend fun changeSource(
        oldBook: Book?,
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        owner: Long,
    ) =
        withContext(IO + NonCancellable) {
            engineWrites.withLock {
                val wasNotShelf =
                    oldBook?.let { appDb.bookDao.getBook(it.bookUrl)?.isNotShelf ?: true }
                        ?: !AudioPlay.inBookshelf
                oldBook?.migrateTo(book, toc)
                book.removeType(BookType.updateError)
                if (wasNotShelf) book.addType(BookType.notShelf)
                replaceBookAfterSourceChange(oldBook, book, toc)
                // Finish the accepted DB migration, but do not retarget a newer playback request.
                requests.publish(owner, identity(oldBook), { identity(AudioPlay.book) }) {
                    AudioPlay.replaceBook(book)
                    AudioPlay.inBookshelf = !wasNotShelf
                    AudioPlay.setBookSource(source)
                    AudioPlay.upData(book, preserveProgress = false)
                    AudioPlayService.updateNotification(context)
                }
                postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
            }
        }

    suspend fun changeToText(oldBook: Book?, book: Book, toc: List<BookChapter>) =
        withContext(IO + NonCancellable) {
            engineWrites.withLock {
                oldBook?.migrateTo(book, toc)
                book.removeType(BookType.updateError)
                replaceBookAfterSourceChange(oldBook, book, toc)
            }
        }

    suspend fun removeFromBookshelf(book: Book) =
        withContext(IO + NonCancellable) {
            book.saveReadRecordSnapshot()
            appDb.bookDao.delete(book)
        }

    suspend fun clearCachedChapter(action: AudioCacheAction.Clear, treeUri: String?): Boolean =
        withContext(IO + NonCancellable) {
            val removed = runCatching {
                io.legado.app.help.audio.AudioCacheManager.removeCachedChapter(
                    treeUri,
                    action.bookUrl,
                    action.chapter,
                )
            }
                .getOrDefault(false)
            if (removed)
                postEvent(
                    EventBus.AUDIO_CACHE_CHANGED,
                    io.legado.app.model.AudioCacheStateChanged(
                        action.bookUrl,
                        io.legado.app.model.AudioCacheKey.from(action.chapter),
                        false,
                        treeUri,
                    ),
                )
            removed
        }

    suspend fun addToShelf(book: Book, source: BookSource?) =
        withContext(IO + NonCancellable) {
            book.removeType(BookType.notShelf)
            book.save()
            io.legado.app.model.SourceCallBack.callBackBook(
                io.legado.app.model.SourceCallBack.ADD_BOOK_SHELF,
                source,
                book,
            )
            if (AudioPlay.book?.bookUrl == book.bookUrl) AudioPlay.inBookshelf = true
        }

    suspend fun cacheFolderAvailable(uri: String?) =
        withContext(IO) {
            runCatching { io.legado.app.help.audio.AudioCacheManager.isCacheDirAvailable(uri) }
                .getOrDefault(false)
        }

    suspend fun cover(path: String?, origin: String?, blur: Boolean = false): Bitmap? =
        withContext(IO) {
            val request =
                if (blur) BookCover.loadBlur(context, path, sourceOrigin = origin)
                else BookCover.load(context, path, sourceOrigin = origin)
            val target = request.submit(600, 800)
            try {
                // Copy before clearing Glide's target: Compose owns the pixels independently of its
                // pool.
                target.get().toBitmap().copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                withContext(NonCancellable + kotlinx.coroutines.Dispatchers.Main) {
                    Glide.with(context).clear(target)
                }
            }
        }
}
