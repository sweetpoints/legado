package io.legado.app.data.association

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serial session commands reread the durable owner rather than overwriting a restored snapshot. */
class AssociationSessionController(
    val ticket: String,
    private val repository: AssociationSessionRepository,
) {
    private val commands = Mutex()

    suspend fun read(): AssociationSession = commands.withLock { repository.read(ticket) }

    suspend fun update(
        expectedGeneration: Long,
        transform: (AssociationSession) -> AssociationSession,
    ): AssociationSession? = commands.withLock {
        val current = repository.read(ticket)
        if (current.generation != expectedGeneration) return@withLock null
        val updated = transform(current).copy(revision = current.revision + 1)
        check(updated.generation == expectedGeneration) { "Command changed its import owner" }
        withContext(NonCancellable) {
            check(repository.write(ticket, updated)) { "Import session changed concurrently" }
        }
        currentCoroutineContext().ensureActive()
        updated
    }

    /**
     * Native work claims before dispatch. If cancellation wins the IO return hop, return the
     * receipt to pending while still holding the command gate: no Activity has accepted it at that
     * point.
     */
    suspend fun claim(token: String, expectedGeneration: Long): AssociationNativeReceipt? =
        commands.withLock {
            currentCoroutineContext().ensureActive()
            val current = repository.read(ticket)
            val claimed = current.claim(token, expectedGeneration) ?: return@withLock null
            val receipt = claimed.claimedEffects.first { it.token == token }
            var durableClaim = false
            try {
                withContext(NonCancellable) {
                    check(repository.write(ticket, claimed)) {
                        "Import session changed concurrently"
                    }
                    durableClaim = true
                }
                currentCoroutineContext().ensureActive()
                receipt
            } catch (failure: Throwable) {
                if (durableClaim) restoreUndeliveredClaim(receipt)
                throw failure
            }
        }

    private suspend fun restoreUndeliveredClaim(receipt: AssociationNativeReceipt) {
        withContext(NonCancellable) {
            val current = repository.read(ticket)
            if (current.generation != receipt.generation || receipt !in current.claimedEffects) {
                return@withContext
            }
            val restored =
                current.copy(
                    revision = current.revision + 1,
                    claimedEffects = current.claimedEffects - receipt,
                    effects = listOf(receipt) + current.effects,
                )
            check(repository.write(ticket, restored)) {
                "Import claim recovery changed concurrently"
            }
        }
    }

    suspend fun acknowledge(token: String, expectedGeneration: Long): Boolean = commands.withLock {
        val current = repository.read(ticket)
        val acknowledged = current.acknowledge(token, expectedGeneration) ?: return@withLock false
        withContext(NonCancellable) {
            check(repository.write(ticket, acknowledged)) { "Import session changed concurrently" }
        }
        true
    }
}
