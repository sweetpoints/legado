package io.legado.app.ui.book.import.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportSessionTest {
    private class Memory : LocalImportSessions {
        var value: LocalImportCheckpoint? = null
        var waiting: CompletableDeferred<Unit>? = null
        var finish: CompletableDeferred<Unit>? = null

        override suspend fun read(ticket: String) = value

        override suspend fun write(ticket: String, value: LocalImportCheckpoint): Boolean {
            waiting?.complete(Unit)
            finish?.await()
            if ((this.value?.revision ?: -1) >= value.revision) return false
            this.value = value
            return true
        }

        override suspend fun close(ticket: String) {
            value = null
        }
    }

    @Test
    fun acceptedImportCancellationRestoresItsReceiptWithoutRunningTheParserAgain() = runBlocking {
        val disk = Memory()
        val waiting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        disk.waiting = waiting
        disk.finish = finish
        val controller = LocalImportSession("key", disk, true)
        val accepted =
            launch(start = CoroutineStart.UNDISPATCHED) {
                controller.update {
                    it.copy(
                        importNonce = "accepted-request",
                        importAccepted = true,
                        importedIds = setOf("successful-file"),
                    )
                }
            }
        waiting.await()
        accepted.cancel()
        finish.complete(Unit)
        accepted.join()
        val restored = LocalImportSession("key", disk, false).load()
        assertTrue(restored.importAccepted)
        assertEquals(setOf("successful-file"), restored.importedIds)
        assertEquals("accepted-request", restored.importNonce)
    }

    @Test
    fun replacedSnapshotRejectsLateReceiptAndForcesControllerToReadNewOwner() = runBlocking {
        val disk = Memory()
        val old = LocalImportSession("key", disk, true)
        old.update { it.copy(root = "old-folder") }
        val waiting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        disk.waiting = waiting
        disk.finish = finish
        var rejected = false
        val stale =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    old.update { it.copy(importNonce = "stale") }
                } catch (error: IllegalStateException) {
                    rejected = true
                }
            }
        waiting.await()
        disk.waiting = null
        disk.finish = null
        val replacement = LocalImportSession("key", disk, false)
        val snapshot = replacement.update { it.copy(root = "new-folder") }
        finish.complete(Unit)
        stale.join()
        assertTrue(rejected)
        assertEquals(snapshot, old.load())
    }
}
