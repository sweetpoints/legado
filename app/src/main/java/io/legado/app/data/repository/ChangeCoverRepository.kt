package io.legado.app.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal data class ChangeCoverTarget(val name: String, val author: String)
internal data class ChangeCoverItem(val id: String, val origin: String, val originName: String,
    val coverUrl: String, val order: Int = 0)
internal enum class ChangeCoverStatus { Idle, Running, RuleReady }
internal data class ChangeCoverSnapshot(val target: ChangeCoverTarget, val covers: List<ChangeCoverItem>,
    val pending: List<String> = emptyList(), val status: ChangeCoverStatus = ChangeCoverStatus.Idle,
    val revision: Long = 0)
internal data class ChangeCoverInitial(val snapshot: ChangeCoverSnapshot, val autoSearch: Boolean)
internal interface ChangeCoverStore {
    suspend fun cached(target: ChangeCoverTarget): List<ChangeCoverItem>
    suspend fun sources(): List<String>
    suspend fun rule(target: ChangeCoverTarget): String?
    suspend fun search(target: ChangeCoverTarget, source: String): ChangeCoverItem?
    suspend fun read(session: String): ChangeCoverSnapshot?
    suspend fun write(session: String, snapshot: ChangeCoverSnapshot)
    fun threadCount(): Int = 9
    fun log(message: String, error: Throwable)
}
internal interface ChangeCoverRepository {
    suspend fun initial(session: String, target: ChangeCoverTarget): ChangeCoverInitial
    fun search(snapshot: ChangeCoverSnapshot, resume: Boolean): Flow<ChangeCoverSnapshot>
    suspend fun save(session: String, snapshot: ChangeCoverSnapshot)
    suspend fun selected(session: String, id: String): String
}
internal class DefaultChangeCoverRepository(private val store: ChangeCoverStore,
    private val concurrency: Int? = null, private val timeoutMillis: Long = 60000,
    private val io: CoroutineDispatcher = Dispatchers.IO) : ChangeCoverRepository {
    override suspend fun initial(session: String, target: ChangeCoverTarget) = withContext(io) {
        val restored = store.read(session)?.takeIf { it.target == target }
        if (restored != null) ChangeCoverInitial(restored, restored.status == ChangeCoverStatus.Running)
        else {
            val cached = store.cached(target)
            ChangeCoverInitial(ChangeCoverSnapshot(target, listOf(defaultItem()) + cached), cached.size <= 1)
        }
    }
    override suspend fun selected(session: String, id: String): String = withContext(io) {
        store.read(session)?.covers?.find { it.id == id }?.coverUrl ?: error("Selected cover is unavailable")
    }
    override suspend fun save(session: String, snapshot: ChangeCoverSnapshot) = withContext(io) { store.write(session, snapshot) }
    override fun search(snapshot: ChangeCoverSnapshot, resume: Boolean): Flow<ChangeCoverSnapshot> = channelFlow {
        var current = if (resume) snapshot.copy(status = ChangeCoverStatus.Running)
            else ChangeCoverSnapshot(snapshot.target, listOf(defaultItem()), store.sources(), ChangeCoverStatus.Running, snapshot.revision)
        send(current)
        if (!resume) {
            val rule = try { store.rule(snapshot.target) }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { store.log("封面规则搜索出错", error); null }
            if (!rule.isNullOrEmpty()) {
                current = current.copy(covers = current.covers + ChangeCoverItem("rule", "", "封面规则", rule, -1), status = ChangeCoverStatus.RuleReady)
                send(current); return@channelFlow
            }
        }
        val lock = Mutex(); val semaphore = Semaphore((concurrency ?: store.threadCount()).coerceIn(1, 9))
        coroutineScope {
            current.pending.toList().forEach { source -> launch {
                semaphore.withPermit {
                    val result = try { withTimeout(timeoutMillis) { store.search(snapshot.target, source) } }
                    catch (_: TimeoutCancellationException) { null }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (error: Exception) { store.log("封面换源搜索出错", error); null }
                    lock.withLock {
                        val found = current.covers.filterNot { it.id == "default" }.toMutableList()
                        if (result != null && found.none { it.id == result.id }) found += result
                        current = current.copy(covers = listOf(defaultItem()) + found.sortedBy { it.order }, pending = current.pending - source)
                        send(current)
                    }
                }
            } }
        }
        send(current.copy(status = ChangeCoverStatus.Idle))
    }.flowOn(io)
    private fun defaultItem() = ChangeCoverItem("default", "", "默认封面", "use_default_cover")
}
