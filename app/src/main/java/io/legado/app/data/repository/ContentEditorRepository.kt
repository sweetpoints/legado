package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val CONTENT_EDITOR_PLAIN_TEXT_PREF = "contentEditPlainText"

data class ContentEditorTarget(val bookUrl: String, val chapterIndex: Int, val chapterPos: Int) {
    fun matches(bookUrl: String?, chapterIndex: Int) =
        this.bookUrl == bookUrl && this.chapterIndex == chapterIndex
}

data class ContentEditorLoaded(val text: String, val displayTitle: String)

data class ContentEditorDraft(
    val target: ContentEditorTarget,
    val text: String,
    val hasChanges: Boolean,
    val revision: Long,
)

interface ContentEditorRepository {
    suspend fun load(target: ContentEditorTarget, reset: Boolean): ContentEditorLoaded

    suspend fun save(target: ContentEditorTarget, text: String)

    suspend fun title(target: ContentEditorTarget): String

    suspend fun saveTitle(target: ContentEditorTarget, title: String): String

    suspend fun plainText(): Boolean

    suspend fun setPlainText(value: Boolean)

    suspend fun readDraft(id: String): ContentEditorDraft?

    suspend fun writeDraft(id: String, draft: ContentEditorDraft)

    suspend fun deleteDraft(id: String)
}

class BookContentEditorRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val draftsDirectory: File = File(context.filesDir, "content-editor-drafts"),
) : ContentEditorRepository {
    private val context = context.applicationContext

    companion object {
        private val draftLocks = ConcurrentHashMap<String, Mutex>()
    }

    override suspend fun load(target: ContentEditorTarget, reset: Boolean) =
        withContext(Dispatchers.IO) {
            val book = database.bookDao.getBook(target.bookUrl) ?: error("书籍不存在")
            val chapter =
                database.bookChapterDao.getChapter(target.bookUrl, target.chapterIndex)
                    ?: error("章节不存在")
            if (reset) {
                BookHelp.delContent(book, chapter)
                if (!book.isLocal)
                    database.bookSourceDao.getBookSource(book.origin)?.let {
                        WebBook.getContentAwait(it, book, chapter)
                    }
            }
            val content = BookHelp.getContent(book, chapter)
            val processed =
                content
                    ?.let {
                        ContentProcessor.get(book.name, book.origin)
                            .getContent(book, chapter, it, includeTitle = false)
                            .toString()
                    }
                    .orEmpty()
            ContentEditorLoaded(processed, chapter.getDisplayTitle())
        }

    override suspend fun save(target: ContentEditorTarget, text: String) =
        withContext(Dispatchers.IO) {
            val book = database.bookDao.getBook(target.bookUrl) ?: error("书籍不存在")
            val chapter =
                database.bookChapterDao.getChapter(target.bookUrl, target.chapterIndex)
                    ?: error("章节不存在")
            // Keep BookHelp's save fence, exact content replacement and reversal-marker
            // invalidation.
            BookHelp.saveText(book, chapter, text)
        }

    override suspend fun title(target: ContentEditorTarget) =
        withContext(Dispatchers.IO) {
            database.bookChapterDao.getChapter(target.bookUrl, target.chapterIndex)?.title
                ?: error("章节不存在")
        }

    override suspend fun saveTitle(target: ContentEditorTarget, title: String) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current =
                    database.bookChapterDao.getChapter(target.bookUrl, target.chapterIndex)
                        ?: error("章节不存在")
                val updated = current.copy(title = title)
                database.bookChapterDao.update(updated)
                updated.getDisplayTitle()
            }
        }

    override suspend fun plainText() =
        withContext(Dispatchers.IO) {
            context.getPrefBoolean(CONTENT_EDITOR_PLAIN_TEXT_PREF, false)
        }

    override suspend fun setPlainText(value: Boolean) =
        withContext(Dispatchers.IO) {
            context.putPrefBoolean(CONTENT_EDITOR_PLAIN_TEXT_PREF, value)
        }

    private fun draftFile(id: String): AtomicFile {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        check(draftsDirectory.isDirectory || draftsDirectory.mkdirs()) { "无法保存草稿" }
        return AtomicFile(File(draftsDirectory, "$id.json"))
    }

    override suspend fun readDraft(id: String): ContentEditorDraft? =
        withContext(Dispatchers.IO) {
            val file = draftFile(id)
            val input =
                try {
                    file.openRead()
                } catch (_: FileNotFoundException) {
                    return@withContext null
                }
            val draft =
                input.bufferedReader().use { GSON.fromJson(it, ContentEditorDraft::class.java) }
            checkNotNull(draft)
            checkNotNull(draft.text)
            checkNotNull(draft.target)
            checkNotNull(draft.target.bookUrl)
            draft
        }

    // Once a checkpoint starts, finish or roll it back before the next writer/cancel can proceed.
    override suspend fun writeDraft(id: String, draft: ContentEditorDraft) =
        withContext(NonCancellable + Dispatchers.IO) {
            draftLocks
                .getOrPut(id) { Mutex() }
                .withLock {
                    val previous = readDraft(id)
                    if (previous != null && previous.revision > draft.revision) return@withLock
                    val file = draftFile(id)
                    val output = file.startWrite()
                    try {
                        output.write(GSON.toJson(draft).toByteArray(Charsets.UTF_8))
                        file.finishWrite(output)
                    } catch (error: Exception) {
                        file.failWrite(output)
                        throw error
                    }
                }
        }

    override suspend fun deleteDraft(id: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            draftLocks.getOrPut(id) { Mutex() }.withLock { draftFile(id).delete() }
        }
}
