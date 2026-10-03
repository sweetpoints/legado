package io.legado.app.data.repository

import com.script.rhino.runScriptWithContext
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.AudioPlay
import io.legado.app.model.AutoTask
import io.legado.app.model.ReadBook
import io.legado.app.model.VideoPlay
import kotlinx.coroutines.*

/** Native entry parameters are separate from Android Intent and from the login form draft. */
data class SourceLoginRequest(
    val bookType: Int = 0,
    val type: String? = null,
    val key: String? = null,
    val bookUrl: String? = null,
)

/** Entity references are confined to the existing script/form compatibility boundary. */
data class SourceLoginSnapshot(
    val source: BaseSource?,
    val book: Book?,
    val chapter: BookChapter?,
    val bookType: Int,
    val headers: Map<String, String>,
    val loginInfo: Map<String, String>,
)

interface SourceLoginRepository {
    suspend fun load(request: SourceLoginRequest): SourceLoginSnapshot
}

internal fun sourceLoginUsesReader(bookType: Int) =
    bookType == BookType.text || bookType == BookType.audio || bookType == BookType.video

internal fun sourceLoginRequiresKey(request: SourceLoginRequest) =
    !sourceLoginUsesReader(request.bookType)

/**
 * Keep reader ownership, source-specific header evaluation and stored-only form input semantics.
 */
class AppSourceLoginRepository(private val database: AppDatabase = appDb) : SourceLoginRepository {
    override suspend fun load(request: SourceLoginRequest): SourceLoginSnapshot =
        withContext(Dispatchers.IO) {
            var book: Book? = null
            var chapter: BookChapter? = null
            val source: BaseSource? =
                when (request.bookType) {
                    BookType.text -> {
                        book = ReadBook.book
                        chapter = book?.let {
                            database.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
                        }
                        ReadBook.bookSource
                    }
                    BookType.audio -> {
                        book = AudioPlay.book
                        chapter = AudioPlay.durChapter
                        AudioPlay.bookSource
                    }
                    BookType.video -> {
                        book = VideoPlay.book
                        chapter = VideoPlay.chapter
                        VideoPlay.source
                    }
                    else -> {
                        val key = request.key ?: throw NoStackTraceException("没有参数")
                        val selected: BaseSource? =
                            when (request.type) {
                                "bookSource" -> database.bookSourceDao.getBookSource(key)
                                "rssSource" -> database.rssSourceDao.getByKey(key)
                                "httpTts" -> database.httpTTSDao.get(key.toLong())
                                "autoTask" -> AutoTask.get(key)?.let(AutoTask::buildSource)
                                else -> null
                            }
                        book =
                            request.bookUrl?.let {
                                database.bookDao.getBook(it)
                                    ?: database.searchBookDao.getSearchBook(it)?.toBook()
                            }
                        selected
                    }
                }
            currentCoroutineContext().ensureActive()
            val headers = runScriptWithContext { source?.getHeaderMap(true) ?: emptyMap() }
            val values = source?.getStoredLoginInfoMap() ?: mutableMapOf()
            currentCoroutineContext().ensureActive()
            SourceLoginSnapshot(
                source,
                book,
                chapter,
                request.bookType,
                headers.toMap(),
                values.toMap(),
            )
        }
}
