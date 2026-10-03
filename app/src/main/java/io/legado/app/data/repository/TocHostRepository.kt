package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.*
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.VideoPlay
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface TocHostRepository {
    suspend fun load(bookUrl: String): Book?

    suspend fun reverse(book: Book): Book

    suspend fun expanded(bookUrl: String, value: Boolean)

    suspend fun rebuild(book: Book): Book

    suspend fun export(book: Book, directory: String, markdown: Boolean)

    fun synchronizeReverse(book: Book)

    fun synchronizeExpanded(bookUrl: String, value: Boolean)

    fun readerMessage(book: Book, error: Throwable?)

    fun preferences(): Pair<Boolean, Boolean>

    fun useReplace(value: Boolean)

    fun countWords(value: Boolean)
}

class AppTocHostRepository(
    private val database: AppDatabase = appDb,
    private val readChapters: (Book) -> List<BookChapter> = LocalBook::getChapterList,
    private val notifyChapters: (Book) -> Unit = { ReadBook.onChapterListUpdated(it) },
) : TocHostRepository {
    override suspend fun load(bookUrl: String): Book? =
        withContext(Dispatchers.IO) { database.bookDao.getBook(bookUrl)?.let(::ownedTocBook) }

    override suspend fun reverse(book: Book): Book =
        withContext(Dispatchers.IO) {
            ownedTocBook(book).apply {
                if (isPdf || isEpub) {
                    setReverseToc(!getReverseToc())
                    database.bookDao.updateReverseToc(bookUrl, getReverseToc())
                } else {
                    setReverseTocDisplay(!getReverseTocDisplay())
                    database.bookDao.updateReverseTocDisplay(bookUrl, getReverseTocDisplay())
                }
            }
        }

    override suspend fun expanded(bookUrl: String, value: Boolean): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            database.bookDao.updateTocExpanded(bookUrl, value)
        }

    override suspend fun rebuild(book: Book): Book =
        withContext(Dispatchers.IO) {
            ownedTocBook(book).apply {
                database.bookDao.updatePreservingCustomCoverUrl(this)
                val chapters = readChapters(this)
                database.bookChapterDao.delByBook(bookUrl)
                database.bookChapterDao.insert(*chapters.toTypedArray())
                database.bookDao.updatePreservingCustomCoverUrl(this)
                notifyChapters(this)
            }
        }

    override suspend fun export(book: Book, directory: String, markdown: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val rows = database.bookmarkDao.getByBook(book.name, book.author)
            val doc = FileDoc.fromUri(Uri.parse(directory), true)
            val fileName = "bookmark-${book.name} ${book.author}.${if (markdown) "md" else "json"}"
            val target = doc.createFileIfNotExist(fileName)
            if (!markdown) target.writeText(GSON.toJson(rows))
            else
                target.openOutputStream().getOrThrow().use { output ->
                    output.write("## ${book.name} ${book.author}\n\n".toByteArray())
                    rows.forEach { row ->
                        output.write("#### ${row.chapterName}\n\n".toByteArray())
                        output.write("###### 原文\n ${row.bookText}\n\n".toByteArray())
                        output.write("###### 摘要\n ${row.content}\n\n".toByteArray())
                    }
                }
        }

    override fun synchronizeReverse(book: Book) {
        listOf(ReadBook.book, ReadManga.book, AudioPlay.book, VideoPlay.book)
            .filter { it?.bookUrl == book.bookUrl }
            .forEach {
                if (book.isPdf || book.isEpub) it?.setReverseToc(book.getReverseToc())
                else it?.setReverseTocDisplay(book.getReverseTocDisplay())
            }
    }

    override fun synchronizeExpanded(bookUrl: String, value: Boolean) {
        listOf(ReadBook.book, ReadManga.book, AudioPlay.book, VideoPlay.book)
            .filter { it?.bookUrl == bookUrl }
            .forEach { it?.setTocExpanded(value) }
    }

    override fun readerMessage(book: Book, error: Throwable?) {
        if (ReadBook.book == book)
            ReadBook.upMsg(error?.let { "LoadTocError:${it.localizedMessage}" })
    }

    override fun preferences() = AppConfig.tocUiUseReplace to AppConfig.tocCountWords

    override fun useReplace(value: Boolean) {
        AppConfig.tocUiUseReplace = value
    }

    override fun countWords(value: Boolean) {
        AppConfig.tocCountWords = value
    }
}

fun ownedTocBook(book: Book): Book = book.copy(readConfig = book.readConfig?.copy())
