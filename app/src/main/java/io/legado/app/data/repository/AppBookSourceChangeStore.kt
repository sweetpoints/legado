package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isWebFile
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SuppressSourceNavigation
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AppBookSourceChangeStore(
    context: Context,
    private val database: AppDatabase = appDb,
    private val search: AppChapterSourceSearchStore = AppChapterSourceSearchStore(database),
) : BookSourceChangeStore {
    private val directory = File(context.applicationContext.filesDir, "book-source-sessions")

    override suspend fun prepare(
        row: ChapterSourceSearchRow,
        allowWebFile: Boolean,
    ): ChapterSourceToc =
        withContext(SuppressSourceNavigation) {
            val book = search.targetBook(row)
            val source = database.bookSourceDao.getBookSource(book.origin) ?: error("书源不存在")
            val chapters =
                if (allowWebFile && book.isWebFile) {
                    if (book.downloadUrls.isNullOrEmpty()) WebBook.getBookInfoAwait(source, book)
                    emptyList()
                } else
                    search.cachedToc(book)
                        ?: run {
                            if (book.tocUrl.isEmpty()) WebBook.getBookInfoAwait(source, book)
                            WebBook.getChapterListAwait(source, book).getOrThrow().also {
                                search.rememberToc(book, it)
                            }
                        }
            ChapterSourceToc(
                hash(row.id),
                GSON.toJson(book),
                GSON.toJson(source),
                chapters.map(::chapter),
                0,
            )
        }

    override suspend fun read(session: String) =
        locked(session) { readJson(file(session, "state"), BookSourceChangeSession::class.java) }

    override suspend fun write(session: String, snapshot: BookSourceChangeSession) =
        locked(session) {
            val target = file(session, "state")
            val previous = readJson(target, BookSourceChangeSession::class.java)
            if (previous == null || snapshot.revision >= previous.revision)
                writeJson(target, snapshot)
        }

    override suspend fun receipt(session: String, key: String) =
        locked(session) { readJson(file(session, key), BookSourceChangeReceipt::class.java) }

    override suspend fun writeReceipt(session: String, receipt: BookSourceChangeReceipt) =
        locked(session) {
            val target = file(session, receipt.key)
            val previous = readJson(target, BookSourceChangeReceipt::class.java)
            writeJson(
                target,
                receipt.copy(
                    consumed = receipt.consumed || previous?.consumed == true,
                    acknowledged = receipt.acknowledged || previous?.acknowledged == true,
                ),
            )
        }

    // The pre-existing ChangeBookSourceViewModel.del contract, moved to the IO store.
    override suspend fun delete(row: ChapterSourceSearchRow) {
        SourceHelp.deleteBookSource(row.origin)
        database.searchBookDao.delete(GSON.fromJson(row.json, SearchBook::class.java))
    }

    override suspend fun disable(row: ChapterSourceSearchRow) {
        database.bookSourceDao.getBookSource(row.origin)?.let {
            database.bookSourceDao.update(it.copy(enabled = false))
        }
    }

    override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) {
        database.bookSourceDao.getBookSource(row.origin)?.let {
            val order =
                if (top) database.bookSourceDao.minOrder - 1
                else database.bookSourceDao.maxOrder + 1
            database.bookSourceDao.update(it.copy(customOrder = order))
            database.searchBookDao.update(
                GSON.fromJson(row.json, SearchBook::class.java).copy(originOrder = order)
            )
        }
    }

    override suspend fun score(row: ChapterSourceSearchRow, score: Int) {
        SourceConfig.setBookScore(row.origin, row.name, row.author, score)
    }

    private suspend fun <T> locked(session: String, block: () -> T): T {
        require(session.matches(Regex("[A-Za-z0-9-]+")))
        return locks
            .getOrPut(File(directory, session).absolutePath) { Mutex() }
            .withLock { block() }
    }

    private fun file(session: String, key: String): File {
        require(key.matches(Regex("[A-Za-z0-9-]+")))
        return File(File(directory, session).apply { mkdirs() }, "$key.json")
    }

    private fun <T> readJson(file: File, type: Class<T>): T? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return GSON.fromJson(AtomicFile(file).readFully().toString(Charsets.UTF_8), type)
            ?: error("换源草稿损坏")
    }

    private fun writeJson(file: File, value: Any) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(GSON.toJson(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun chapter(chapter: BookChapter) =
        ChapterSourceChapter(
            "${chapter.index}-${hash(chapter.url)}",
            chapter.index,
            chapter.title,
            chapter.isVolume,
            chapter.tag,
            GSON.toJson(chapter),
        )

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
