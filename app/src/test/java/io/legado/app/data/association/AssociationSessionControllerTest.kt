package io.legado.app.data.association

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssociationSessionControllerTest {
    private val input = AssociationInput(AssociationHostKind.Online, AssociationInputKind.View)
    private val receipt = AssociationNativeReceipt("native", 0, AssociationNativeKind.ImportDialog)

    @Test
    fun updatesMergeLatestSelectionAndClaimsDeliverOnlyOnceForMatchingOwner() = runTest {
        val repository = MemoryRepository(AssociationSession(input, effects = listOf(receipt)))
        val controller = AssociationSessionController("ticket", repository)
        controller.update(0) { it.copy(selectedIds = listOf("selected")) }
        controller.update(0) { it.copy(error = "failure") }
        assertEquals(listOf("selected"), controller.read().selectedIds)
        assertEquals("failure", controller.read().error)
        assertNull(controller.claim(receipt.token, 1))
        assertEquals(receipt, controller.claim(receipt.token, 0))
        assertNull(controller.claim(receipt.token, 0))
        assertFalse(controller.acknowledge(receipt.token, 1))
        assertTrue(controller.acknowledge(receipt.token, 0))
        assertFalse(controller.acknowledge(receipt.token, 0))
        assertTrue(controller.read().claimedEffects.isEmpty())
    }

    @Test
    fun staleOwnerCannotOverwriteRestoredSession() = runTest {
        val repository =
            MemoryRepository(AssociationSession(input, generation = 2, error = "latest"))
        val controller = AssociationSessionController("ticket", repository)
        assertNull(controller.update(1) { it.copy(error = "stale") })
        assertEquals("latest", controller.read().error)
        assertEquals(0L, controller.read().revision)
    }

    @Test
    fun cancellationAfterRealIoClaimRestoresReceiptWithoutDeliveringNativeUi() = runTest {
        val entered = CompletableDeferred<Unit>()
        val returnFromIo = CompletableDeferred<Unit>()
        val storage = AtomicReference(AssociationSession(input, effects = listOf(receipt)))
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val repository =
            object : AssociationSessionRepository {
                override suspend fun create(input: AssociationInput): String = error("Unused")

                override suspend fun read(ticket: String): AssociationSession =
                    withContext(dispatcher) { storage.get() }

                override suspend fun write(ticket: String, value: AssociationSession): Boolean =
                    withContext(dispatcher + NonCancellable) {
                        if (value.revision <= storage.get().revision) return@withContext false
                        storage.set(value)
                        if (value.claimedEffects.isNotEmpty()) {
                            entered.complete(Unit)
                            returnFromIo.await()
                        }
                        true
                    }

                override suspend fun writeBytes(
                    ticket: String,
                    name: String,
                    bytes: ByteArray,
                ): Unit = error("Unused")

                override suspend fun readBytes(ticket: String, name: String): ByteArray =
                    error("Unused")

                override suspend fun release(ticket: String): Unit = error("Unused")
            }
        val controller = AssociationSessionController("ticket", repository)
        var delivered = false
        val delivery = launch {
            controller.claim(receipt.token, 0)?.let { delivered = true }
        }
        try {
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
            delivery.cancel()
            returnFromIo.complete(Unit)
            delivery.join()
            assertFalse(delivered)
            assertEquals(listOf(receipt), storage.get().effects)
            assertTrue(storage.get().claimedEffects.isEmpty())
            assertEquals(2L, storage.get().revision)
            assertEquals(receipt, controller.claim(receipt.token, 0))
        } finally {
            returnFromIo.complete(Unit)
            delivery.cancelAndJoin()
            dispatcher.close()
        }
    }

    private class MemoryRepository(initial: AssociationSession) : AssociationSessionRepository {
        private var value = initial

        override suspend fun create(input: AssociationInput): String = error("Unused")

        override suspend fun read(ticket: String): AssociationSession = value

        override suspend fun write(ticket: String, value: AssociationSession): Boolean {
            if (value.revision <= this.value.revision) return false
            this.value = value
            return true
        }

        override suspend fun writeBytes(ticket: String, name: String, bytes: ByteArray): Unit =
            error("Unused")

        override suspend fun readBytes(ticket: String, name: String): ByteArray = error("Unused")

        override suspend fun release(ticket: String): Unit = error("Unused")
    }
}
