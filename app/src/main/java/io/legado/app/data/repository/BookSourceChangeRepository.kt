package io.legado.app.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class BookSourceChangeSession(val request: ChapterSourceSearchRequest,
    val rows: List<ChapterSourceSearchRow> = emptyList(), val mismatchId: String? = null,
    val pendingReceipt: String? = null, val finished: Boolean = false, val revision: Long = 0)
internal data class BookSourceChangeReceipt(val key: String, val bookJson: String, val sourceJson: String,
    val chapters: List<ChapterSourceChapter>, val deleteAfter: ChapterSourceSearchRow? = null,
    val consumed: Boolean = false, val acknowledged: Boolean = false)
internal interface BookSourceChangeStore {
    suspend fun prepare(row: ChapterSourceSearchRow, allowWebFile: Boolean): ChapterSourceToc
    suspend fun read(session: String): BookSourceChangeSession?
    suspend fun write(session: String, snapshot: BookSourceChangeSession)
    suspend fun receipt(session: String, key: String): BookSourceChangeReceipt?
    suspend fun writeReceipt(session: String, receipt: BookSourceChangeReceipt)
    suspend fun delete(row: ChapterSourceSearchRow)
    suspend fun disable(row: ChapterSourceSearchRow)
    suspend fun order(row: ChapterSourceSearchRow, top: Boolean)
    suspend fun score(row: ChapterSourceSearchRow, score: Int)
}
internal interface BookSourceChangeRepository {
    suspend fun prepare(session: String, row: ChapterSourceSearchRow, deleteAfter: ChapterSourceSearchRow? = null): BookSourceChangeReceipt
    suspend fun read(session: String): BookSourceChangeSession?
    suspend fun write(session: String, snapshot: BookSourceChangeSession)
    suspend fun receipt(session: String, key: String): BookSourceChangeReceipt
    suspend fun consume(session: String, key: String)
    suspend fun complete(session: String, key: String)
    suspend fun delete(row: ChapterSourceSearchRow)
    suspend fun disable(row: ChapterSourceSearchRow)
    suspend fun order(row: ChapterSourceSearchRow, top: Boolean)
    suspend fun score(row: ChapterSourceSearchRow, score: Int)
}
internal class DefaultBookSourceChangeRepository(private val store: BookSourceChangeStore,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BookSourceChangeRepository {
    override suspend fun prepare(session: String, row: ChapterSourceSearchRow, deleteAfter: ChapterSourceSearchRow?) = withContext(io) {
        val target = store.prepare(row, allowWebFile = deleteAfter == null)
        currentCoroutineContext().ensureActive()
        val receipt = BookSourceChangeReceipt(UUID.randomUUID().toString(), target.bookJson, target.sourceJson, target.chapters, deleteAfter)
        store.writeReceipt(session, receipt)
        currentCoroutineContext().ensureActive()
        receipt
    }
    override suspend fun read(session: String) = withContext(io) { store.read(session) }
    override suspend fun write(session: String, snapshot: BookSourceChangeSession) = withContext(io + NonCancellable) { store.write(session, snapshot) }
    override suspend fun receipt(session: String, key: String) = withContext(io) { requireNotNull(store.receipt(session, key)) { "换源结果不存在" } }
    override suspend fun consume(session: String, key: String) = withContext(io + NonCancellable) {
        gate(session, key).withLock { store.receipt(session, key)?.let { store.writeReceipt(session, it.copy(consumed = true)) } }
        Unit
    }
    // Called exclusively by the existing host's successful change callback, never during preparation.
    override suspend fun complete(session: String, key: String) = withContext(io + NonCancellable) {
        gate(session, key).withLock {
            store.receipt(session, key)?.takeUnless { it.acknowledged }?.let {
                it.deleteAfter?.let { row -> store.delete(row) }
                store.writeReceipt(session, it.copy(acknowledged = true))
            }
        }
        Unit
    }
    override suspend fun delete(row: ChapterSourceSearchRow) = withContext(io) { store.delete(row) }
    override suspend fun disable(row: ChapterSourceSearchRow) = withContext(io) { store.disable(row) }
    override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) = withContext(io) { store.order(row, top) }
    override suspend fun score(row: ChapterSourceSearchRow, score: Int) = withContext(io) { store.score(row, score) }
    private fun gate(session: String, key: String) = gates.getOrPut("$session/$key") { Mutex() }
    private companion object { val gates = ConcurrentHashMap<String, Mutex>() }
}
