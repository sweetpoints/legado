package io.legado.app.data.repository

import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.Executors

class BackupLanRepositoryTest {
    private class Store : BackupLanStore {
        val calls = mutableListOf<String>(); val threads = mutableListOf<Thread>(); var sendClosed = 0; var receivedClosed = 0
        var failFirstClose = false; private var preparedCount = 0
        var failSpace = false; var failRestore = false; var gate: CompletableDeferred<Unit>? = null; var entered: CompletableDeferred<Unit>? = null
        private fun call(name: String) { calls += name; threads += Thread.currentThread() }
        override suspend fun prepare(): BackupLanPrepared {
            call("prepare"); entered?.complete(Unit); gate?.let { withContext(NonCancellable) { it.await() } }
            val id = if (preparedCount++ == 0) "offer" else "offer-$preparedCount"
            return object : BackupLanPrepared { override val offer = BackupLanOffer(id, "/private/qr.png", 987)
                override fun close() { call("close-send"); sendClosed++; if (failFirstClose && id == "offer") error("first close failed") } }
        }
        override suspend fun decode(qrText: String): BackupLanReceiveInfo { call("decode"); return BackupLanReceiveInfo(2048, "device") }
        override suspend fun receive(qrText: String): BackupLanReceived {
            call("receive"); return object : BackupLanReceived { override val uncompressedBytes = 4096L
                override fun close() { call("close-received"); receivedClosed++ } }
        }
        override suspend fun backupBeforeRestore() { call("backup-before") }
        override suspend fun requireSpace(bytes: Long) { call("space:$bytes"); if (failSpace) error("no space") }
        override suspend fun restore(received: BackupLanReceived) { call("restore"); if (failRestore) error("restore failed") }
    }
    @Test fun ownedSendOfferClosesExactlyOnceAndClosedRepositoryRejectsLateOrNewServers() = runBlocking {
        val store = Store(); val repo = DefaultBackupLanRepository(store, Dispatchers.Unconfined)
        assertEquals(BackupLanOffer("offer", "/private/qr.png", 987), repo.prepare()); repo.close("offer"); repo.close("offer"); repo.closeAll()
        assertEquals(1, store.sendClosed); assertTrue(runCatching { repo.prepare() }.isFailure); assertEquals(2, store.sendClosed)
    }
    @Test fun closeAllAttemptsEveryOwnedSessionEvenWhenFirstCloseThrows() = runBlocking {
        val store = Store().apply { failFirstClose = true }; val repo = DefaultBackupLanRepository(store, Dispatchers.Unconfined)
        repo.prepare(); repo.prepare()
        assertEquals("first close failed", runCatching { repo.closeAll() }.exceptionOrNull()?.message)
        assertEquals(2, store.sendClosed); repo.closeAll(); assertEquals(2, store.sendClosed)
    }
    @Test fun successfulReceiveBacksUpThenChecksSpaceBeforeRestoreAndAlwaysCleansTemporaryFile() = runBlocking {
        val store = Store(); val repo = DefaultBackupLanRepository(store, Dispatchers.Unconfined)
        val phases = repo.receive("qr").toList()
        assertEquals(BackupLanReceivePhase.entries, phases)
        assertEquals(listOf("receive", "backup-before", "space:4096", "restore", "close-received"), store.calls)
        assertEquals(1, store.receivedClosed)
    }
    @Test fun insufficientSpaceNeverRestoresAndRestoreFailureStillCleansReceivedDirectory() = runBlocking {
        val store = Store().apply { failSpace = true }; val repo = DefaultBackupLanRepository(store, Dispatchers.Unconfined)
        assertTrue(runCatching { repo.receive("qr").toList() }.isFailure); assertFalse(store.calls.contains("restore")); assertEquals(1, store.receivedClosed)
        store.calls.clear(); store.failSpace = false; store.failRestore = true
        assertTrue(runCatching { repo.receive("qr").toList() }.isFailure); assertEquals("close-received", store.calls.last()); assertEquals(2, store.receivedClosed)
    }
    @Test fun sendPreparationDecodeCloseAndReceiveStagesUseIoIdentity() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val thread = withContext(io) { Thread.currentThread() }; val store = Store(); val repo = DefaultBackupLanRepository(store, io)
            repo.prepare(); assertEquals(BackupLanReceiveInfo(2048, "device"), repo.decode("qr")); repo.close("offer"); repo.receive("qr").toList(); repo.closeAll()
            assertTrue(store.threads.isNotEmpty()); store.threads.forEach { assertSame(thread, it) }
        }
    }
    @Test fun cancellingNonCooperativePrepareAfterOwnerCloseCannotResurrectOfferOrLeakServer() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); val store = Store().apply { this.gate = gate; this.entered = entered }
            val repo = DefaultBackupLanRepository(store, io); var published = false
            val job = launch { repo.prepare(); published = true }; entered.await(); job.cancel(); repo.closeAll(); gate.complete(Unit); job.join()
            assertFalse(published); assertEquals(1, store.sendClosed); repo.closeAll(); assertEquals(1, store.sendClosed)
        }
    }
}
