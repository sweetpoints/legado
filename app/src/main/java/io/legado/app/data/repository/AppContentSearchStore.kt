package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.GSON
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AppContentSearchStore(context: Context, private val database: AppDatabase = appDb) :
    ContentSearchStore {
    private val directory = File(context.applicationContext.filesDir, "content-search-sessions")

    override suspend fun book(url: String) =
        database.bookDao.getBook(url)?.let {
            ContentSearchBook(it.bookUrl, it.name, it.durChapterIndex, it.isLocal, GSON.toJson(it))
        }

    override suspend fun cacheNames(book: ContentSearchBook) =
        BookHelp.getChapterFiles(GSON.fromJson(book.json, Book::class.java)).toSet()

    override suspend fun chapters(book: ContentSearchBook) =
        database.bookChapterDao.getChapterList(book.url).map {
            ContentSearchChapter(it.index, it.getFileName(), GSON.toJson(it))
        }

    override suspend fun process(
        book: ContentSearchBook,
        chapter: ContentSearchChapter,
        replace: Boolean,
    ): ContentSearchChapterText? {
        val original = GSON.fromJson(book.json, Book::class.java)
        val selected = GSON.fromJson(chapter.json, BookChapter::class.java)
        val raw = BookHelp.getContent(original, selected) ?: return null
        currentCoroutineContext().ensureActive()
        selected.title =
            when (AppConfig.chineseConverterType) {
                1 -> ChineseUtils.t2s(selected.title)
                2 -> ChineseUtils.s2t(selected.title)
                else -> selected.title
            }
        currentCoroutineContext().ensureActive()
        val content =
            ContentProcessor.get(original.name, original.origin)
                .getContent(original, selected, raw, useReplace = replace)
                .toString()
        currentCoroutineContext().ensureActive()
        return ContentSearchChapterText(selected.index, selected.title, content)
    }

    override suspend fun read(session: String) =
        locked(session) {
            ensureOpen(session)
            readFile(target(session))
        }

    override suspend fun create(session: String, snapshot: ContentSearchSession) =
        locked(session) {
            ensureOpen(session)
            readFile(target(session)) ?: snapshot.also { writeFile(target(session), it) }
        }

    override suspend fun write(session: String, snapshot: ContentSearchSession) =
        locked(session) {
            ensureOpen(session)
            val target = target(session)
            val previous = readFile(target) ?: throw ContentSearchSessionClosedException()
            if (snapshot.revision >= previous.revision) writeFile(target, snapshot)
        }

    override suspend fun release(session: String) =
        locked(session) {
            // Fence the owner before deleting large results, so old queued writers cannot resurrect
            // them.
            val marker = marker(session)
            if (!marker.exists() && !File(marker.path + ".bak").exists()) {
                marker.parentFile?.mkdirs()
                val atomic = AtomicFile(marker)
                val output = atomic.startWrite()
                try {
                    output.write("closed".toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }
            val file = target(session)
            AtomicFile(file).delete()
            check(
                listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none {
                    it.exists()
                }
            ) {
                "无法清理全文搜索结果"
            }
        }

    private fun ensureOpen(session: String) {
        val marker = marker(session)
        if (marker.exists() || File(marker.path + ".bak").exists())
            throw ContentSearchSessionClosedException()
    }

    private fun readFile(file: File): ContentSearchSession? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return GSON.fromJson(
            AtomicFile(file).readFully().toString(Charsets.UTF_8),
            ContentSearchSession::class.java,
        ) ?: error("全文搜索草稿损坏")
    }

    private fun writeFile(file: File, value: ContentSearchSession) {
        file.parentFile?.mkdirs()
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

    private fun target(session: String) = File(directory, "$session.json")

    private fun marker(session: String) = File(directory, "$session.closed")

    private suspend fun <T> locked(session: String, block: () -> T): T {
        require(session.matches(Regex("[A-Za-z0-9-]+")))
        val path = target(session).canonicalPath
        return stripes[(path.hashCode() and Int.MAX_VALUE) % stripes.size].withLock { block() }
    }

    private companion object {
        val stripes = Array(64) { Mutex() }
    }
}
