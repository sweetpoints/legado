package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.writeToOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class AllBookmarksRow(
    val id: Long,
    val bookName: String,
    val bookAuthor: String,
    val chapter: String,
    val original: String,
    val content: String,
) {
    val group: String
        get() = "$bookName($bookAuthor)"
}

data class AllBookmarksDestination(val bookmark: Bookmark, val book: Book?)

interface AllBookmarksRepository {
    fun observe(): Flow<List<AllBookmarksRow>>

    suspend fun resolve(id: Long, edit: Boolean): AllBookmarksDestination?

    suspend fun export(directory: String, markdown: Boolean): String
}

class AppAllBookmarksRepository(
    private val database: AppDatabase = appDb,
    private val now: () -> Long = System::currentTimeMillis,
) : AllBookmarksRepository {
    override fun observe() =
        database.bookmarkDao
            .flowAll()
            .map { list ->
                list.map { row ->
                    AllBookmarksRow(
                        row.time,
                        row.bookName,
                        row.bookAuthor,
                        preview(row.chapterName),
                        preview(row.bookText),
                        preview(row.content),
                    )
                }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun resolve(id: Long, edit: Boolean) =
        withContext(Dispatchers.IO) {
            val bookmark =
                database.bookmarkDao.all.find { it.time == id }?.copy() ?: return@withContext null
            AllBookmarksDestination(
                bookmark,
                if (edit) null
                else database.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)?.copy(),
            )
        }

    override suspend fun export(directory: String, markdown: Boolean) =
        withContext(Dispatchers.IO) {
            val date = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault()).format(Date(now()))
            val filename = "bookmark-$date.${if (markdown) "md" else "json"}"
            val rows = database.bookmarkDao.all.map { it.copy() }
            FileDoc.fromUri(Uri.parse(directory), true)
                .createFileIfNotExist(filename)
                .openOutputStream()
                .getOrThrow()
                .use {
                    if (markdown) writeMarkdown(it, rows) else GSON.writeToOutputStream(it, rows)
                }
            filename
        }

    companion object {
        private fun preview(text: String): String {
            if (text.length <= 512) return text
            val end = if (text[511].isHighSurrogate()) 511 else 512
            return text.substring(0, end)
        }

        /** Preserve the existing export format, including its two-field group boundary. */
        fun writeMarkdown(output: OutputStream, rows: List<Bookmark>) {
            var name = ""
            var author = ""
            rows.forEach { row ->
                if (row.bookName != name && row.bookAuthor != author) {
                    name = row.bookName
                    author = row.bookAuthor
                    output.write("## ${row.bookName} ${row.bookAuthor}\n\n".toByteArray())
                }
                output.write("#### ${row.chapterName}\n\n".toByteArray())
                output.write("###### 原文\n ${row.bookText}\n\n".toByteArray())
                output.write("###### 摘要\n ${row.content}\n\n".toByteArray())
            }
        }
    }
}
