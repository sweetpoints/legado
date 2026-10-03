package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.contains
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.model.bookshelf.*
import io.legado.app.utils.cnCompare
import io.legado.app.utils.mergeFilteredOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.max

internal interface BookshelfManagementStore {
    fun books(groupId: Long): Flow<List<Book>>
    fun groups(): Flow<List<BookGroup>>
    fun sort(groupId: Long): Int
    fun openTitle(): Boolean
    fun setOpenTitle(value: Boolean)
    fun read(id: String): Book?
    fun allByOrder(): List<Book>
    fun updateMetadata(book: Book)
    fun updateOrder(id: String, order: Int)
    fun transaction(block: () -> Unit)
}
internal interface BookshelfManagementRepository {
    fun observe(groupId: Long, query: String): Flow<ManagedShelfSnapshot>
    suspend fun group(ids: List<String>, group: Long, mode: ShelfGroupMutation)
    suspend fun canUpdate(ids: List<String>, enabled: Boolean)
    suspend fun order(assignments: List<ShelfOrderAssignment>, resetAll: Boolean)
    suspend fun openTitle(value: Boolean)
}
/** All edits start with IDs and re-read current records in the transaction. */
internal class DefaultBookshelfManagementRepository(private val store: BookshelfManagementStore,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BookshelfManagementRepository {
    override fun observe(groupId: Long, query: String): Flow<ManagedShelfSnapshot> = combine(store.books(groupId), store.groups()) { books, groups ->
        val sort = store.sort(groupId)
        val ordered = when (sort) {
            1 -> books.sortedByDescending { it.latestChapterTime }
            2 -> books.sortedWith { a, b -> a.name.cnCompare(b.name) }
            3 -> books.sortedBy { it.order }
            4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
            else -> books.sortedByDescending { it.durChapterTime }
        }
        ManagedShelfSnapshot(ordered.filter { it.contains(query) }.map { book ->
            ManagedShelfBook(book.bookUrl, book.name, book.author, book.originName, book.isLocal, book.group,
                groups.filter { it.groupId > 0 && it.groupId and book.group > 0 }.joinToString(",") { it.groupName }, book.order, book.canUpdate)
        }, groups.map { ManagedShelfGroup(it.groupId, it.groupName, it.order) }, groupId, groups.firstOrNull { it.groupId == groupId }?.groupName, sort, store.openTitle())
    }.flowOn(io)
    override suspend fun group(ids: List<String>, group: Long, mode: ShelfGroupMutation) = edit(ids) { book ->
        book.copy(group = when (mode) { ShelfGroupMutation.Replace -> group; ShelfGroupMutation.Add -> book.group or group; ShelfGroupMutation.Remove -> book.group and group.inv() })
    }
    override suspend fun canUpdate(ids: List<String>, enabled: Boolean) = edit(ids) { book ->
        book.copy(canUpdate = enabled).apply { if (!enabled) removeType(BookType.updateError) }
    }
    private suspend fun edit(ids: List<String>, transform: (Book) -> Book) = withContext(io) {
        currentCoroutineContext().ensureActive()
        store.transaction { ids.distinct().forEach { id -> store.read(id)?.let { store.updateMetadata(transform(it)) } } }
    }
    override suspend fun order(assignments: List<ShelfOrderAssignment>, resetAll: Boolean) = withContext(io) {
        currentCoroutineContext().ensureActive()
        store.transaction {
            if (resetAll) {
                val all = store.allByOrder()
                val byId = all.associateBy { it.bookUrl }
                val requested = assignments.distinctBy { it.id }.mapNotNull { byId[it.id] }
                val merged = mergeFilteredOrder(all, requested) { it.bookUrl }
                merged.forEachIndexed { index, book -> store.updateOrder(book.bookUrl, index + 1) }
            } else assignments.distinctBy { it.id }.forEach { item ->
                if (store.read(item.id) != null) store.updateOrder(item.id, item.order)
            }
        }
    }
    override suspend fun openTitle(value: Boolean) = withContext(io) { store.setOpenTitle(value) }
}
internal class AppBookshelfManagementStore(private val database: AppDatabase = appDb) : BookshelfManagementStore {
    override fun books(groupId: Long) = database.bookDao.flowByGroup(groupId)
    override fun groups() = database.bookGroupDao.flowAll()
    override fun sort(groupId: Long) = AppConfig.getBookSortByGroupId(groupId)
    override fun openTitle() = AppConfig.openBookInfoByClickTitle
    override fun setOpenTitle(value: Boolean) { AppConfig.openBookInfoByClickTitle = value }
    override fun read(id: String) = database.bookDao.getBook(id)
    override fun allByOrder() = database.bookDao.allShelfByOrder
    override fun updateMetadata(book: Book) { database.bookDao.updatePreservingCustomCoverUrl(book) }
    override fun updateOrder(id: String, order: Int) = database.bookDao.updateOrder(id, order)
    override fun transaction(block: () -> Unit) = database.runInTransaction(block)
}
