package io.legado.app.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw stored seconds are preserved; controls clamp their display until the user changes a value. */
internal data class AudioSkipCreditsDraft(val useGlobal: Boolean, val bookOpen: Int, val bookClose: Int,
    val globalOpen: Int, val globalClose: Int) {
    val opening get() = (if (useGlobal) globalOpen else bookOpen).coerceIn(0, 180)
    val closing get() = (if (useGlobal) globalClose else bookClose).coerceIn(0, 180)
    fun scope(global: Boolean) = if (!global && useGlobal) copy(useGlobal = false, bookOpen = globalOpen, bookClose = globalClose) else copy(useGlobal = global)
    fun opening(value: Int) = if (useGlobal) copy(globalOpen = value.coerceIn(0, 180)) else copy(bookOpen = value.coerceIn(0, 180))
    fun closing(value: Int) = if (useGlobal) copy(globalClose = value.coerceIn(0, 180)) else copy(bookClose = value.coerceIn(0, 180))
}
internal interface AudioSkipCreditsStore {
    val bookId: String
    suspend fun load(): AudioSkipCreditsDraft
    suspend fun globals(opening: Int, closing: Int)
    suspend fun saveBook(draft: AudioSkipCreditsDraft)
}
internal interface AudioSkipCreditsRepository {
    suspend fun load(): AudioSkipCreditsDraft
    suspend fun write(draft: AudioSkipCreditsDraft, revision: Long, globalsChanged: Boolean, saveBook: Boolean)
}
/** Shared across dialog owners so a late writer cannot undo a newer scope or slider change. */
internal class AudioSkipCreditsWriteGate {
    val mutex = Mutex()
    val bookRevisions = mutableMapOf<String, Long>()
    var globalRevision = Long.MIN_VALUE
}
internal class DefaultAudioSkipCreditsRepository(private val store: AudioSkipCreditsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val gate: AudioSkipCreditsWriteGate = sharedGate) : AudioSkipCreditsRepository {
    override suspend fun load() = withContext(io) { store.load() }
    override suspend fun write(draft: AudioSkipCreditsDraft, revision: Long, globalsChanged: Boolean, saveBook: Boolean) = withContext(io + NonCancellable) {
        gate.mutex.withLock {
            val bookCurrent = gate.bookRevisions[store.bookId] ?: Long.MIN_VALUE
            if (revision < bookCurrent) return@withLock
            if (globalsChanged && revision >= gate.globalRevision) {
                store.globals(draft.globalOpen, draft.globalClose)
                gate.globalRevision = revision
            }
            if (saveBook) store.saveBook(draft)
            gate.bookRevisions[store.bookId] = revision
        }
    }
    private companion object { val sharedGate = AudioSkipCreditsWriteGate() }
}
