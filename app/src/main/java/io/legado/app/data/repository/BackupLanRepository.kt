package io.legado.app.data.repository

import io.legado.app.model.backup.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal interface BackupLanPrepared : Closeable {
    val offer: BackupLanOffer
}

internal interface BackupLanReceived : Closeable {
    val uncompressedBytes: Long
}

internal interface BackupLanStore {
    suspend fun prepare(): BackupLanPrepared

    suspend fun decode(qrText: String): BackupLanReceiveInfo

    suspend fun receive(qrText: String): BackupLanReceived

    suspend fun backupBeforeRestore()

    suspend fun requireSpace(bytes: Long)

    suspend fun restore(received: BackupLanReceived)
}

internal interface BackupLanRepository {
    suspend fun prepare(): BackupLanOffer

    suspend fun decode(qrText: String): BackupLanReceiveInfo

    fun receive(qrText: String): Flow<BackupLanReceivePhase>

    suspend fun close(id: String)

    suspend fun closeAll()
}

/** Owns the native server and its private QR image; cancellation cannot orphan either resource. */
internal class DefaultBackupLanRepository(
    private val store: BackupLanStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupLanRepository {
    private class Owned(private val delegate: BackupLanPrepared) : BackupLanPrepared {
        override val offer = delegate.offer
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) delegate.close()
        }
    }

    private val sessions = LinkedHashMap<String, Owned>()
    private var closed = false

    override suspend fun prepare(): BackupLanOffer {
        var pending: Owned? = null
        try {
            return withContext(io) {
                val prepared = Owned(store.prepare())
                pending = prepared
                currentCoroutineContext().ensureActive()
                synchronized(sessions) {
                    check(!closed) { "LAN repository closed" }
                    check(!sessions.containsKey(prepared.offer.id)) { "Duplicate LAN offer" }
                    sessions[prepared.offer.id] = prepared
                }
                prepared.offer
            }
        } catch (error: Throwable) {
            pending?.let { value ->
                withContext(io + NonCancellable) {
                    synchronized(sessions) {
                        if (sessions[value.offer.id] === value) sessions.remove(value.offer.id)
                    }
                    value.close()
                }
            }
            throw error
        }
    }

    override suspend fun decode(qrText: String) =
        withContext(io) { store.decode(qrText).also { currentCoroutineContext().ensureActive() } }

    override fun receive(qrText: String): Flow<BackupLanReceivePhase> = flow {
        var received: BackupLanReceived? = null
        try {
            emit(BackupLanReceivePhase.Receiving)
            received = store.receive(qrText)
            currentCoroutineContext().ensureActive()
            emit(BackupLanReceivePhase.BackingUp)
            store.backupBeforeRestore()
            currentCoroutineContext().ensureActive()
            store.requireSpace(received.uncompressedBytes)
            currentCoroutineContext().ensureActive()
            emit(BackupLanReceivePhase.Restoring)
            store.restore(received)
            currentCoroutineContext().ensureActive()
            emit(BackupLanReceivePhase.Complete)
        } finally {
            received?.let { withContext(NonCancellable) { it.close() } }
        }
    }
        .flowOn(io)
        .buffer(0)

    override suspend fun close(id: String): Unit =
        withContext(io + NonCancellable) {
            synchronized(sessions) { sessions.remove(id) }?.close()
        }

    override suspend fun closeAll(): Unit =
        withContext(io + NonCancellable) {
            val owned =
                synchronized(sessions) {
                    closed = true
                    sessions.values.toList().also { sessions.clear() }
                }
            var first: Throwable? = null
            owned.forEach { value ->
                try {
                    value.close()
                } catch (error: Throwable) {
                    if (first == null) first = error
                    else if (first !== error) first.addSuppressed(error)
                }
            }
            first?.let { throw it }
        }
}
