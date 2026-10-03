package io.legado.app.data.repository

import android.content.Context
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.utils.cnCompare
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

interface BookshelfFolderRepository {
    fun groups(): Flow<List<BookGroup>>

    fun books(groupId: Long): Flow<List<Book>>

    fun previews(): Flow<List<BookshelfBook>>

    fun settings(): Flow<BookshelfPageSettings>

    fun preferences(): Flow<BookshelfHomePreferences>

    fun header(preferences: BookshelfHomePreferences): Flow<BookshelfHomeHeader>

    suspend fun book(key: String): Book?

    suspend fun group(id: Long): BookGroup?
}

class RoomBookshelfFolderRepository(context: Context) : BookshelfFolderRepository {
    private val home = RoomBookshelfHomeRepository(context)
    private val page = RoomBookshelfPageRepository(context)

    override fun groups() = home.groups()

    override fun books(groupId: Long) = page.books(groupId)

    override fun previews(): Flow<List<BookshelfBook>> =
        appDb.bookDao.flowBookshelfBooks().map { it.toList() }.flowOn(Dispatchers.IO)

    override fun settings() = page.settings()

    override fun preferences() = home.preferences()

    override fun header(preferences: BookshelfHomePreferences) = home.header(preferences)

    override suspend fun book(key: String) = home.book(key)

    override suspend fun group(id: Long) = home.group(id)
}

internal fun folderPreviewBooks(
    group: BookGroup,
    books: List<BookshelfBook>,
    defaultSort: Int,
): List<BookshelfBook> =
    if (!group.cover.isNullOrBlank()) emptyList()
    else
        sortFolderPreviewBooks(
                books.filter { it.belongsToFolder(group.groupId) },
                group.bookSort.takeIf { it >= 0 } ?: defaultSort,
            )
            .take(4)

internal fun sortFolderPreviewBooks(books: List<BookshelfBook>, sort: Int): List<BookshelfBook> =
    when (sort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
        3 -> books.sortedBy { it.order }
        4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        5 -> books.sortedWith { first, second -> first.author.cnCompare(second.author) }
        else -> books.sortedByDescending { it.durChapterTime }
    }

private fun BookshelfBook.belongsToFolder(id: Long): Boolean {
    val audio = type and BookType.audio > 0
    val local = type and BookType.local > 0
    val video = type and BookType.video > 0
    return when (id) {
        BookGroup.IdAll -> true
        BookGroup.IdLocal -> local
        BookGroup.IdAudio -> audio
        BookGroup.IdNetNone -> !local && !audio && !video && !hasUserGroup
        BookGroup.IdLocalNone -> local && !hasUserGroup
        BookGroup.IdVideo -> video
        BookGroup.IdError -> type and BookType.updateError > 0
        else -> id > 0 && (group and id) > 0
    }
}
