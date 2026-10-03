package io.legado.app.ui.book.explore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreResultsDeliveryTest {
    @Test
    fun pauseDuringPreparationLeavesTicketUnclaimedAndResumedRetryDeliversOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        var resumed = true
        var claims = 0
        var deliveries = 0
        var handled = 0
        val first = launch {
            deliverExploreBookDetail(
                prepare = {
                    gate.await()
                    "owned"
                },
                claim = {
                    claims++
                    true
                },
                defer = { error("Unclaimed ticket must not be rolled back") },
                handled = { handled++ },
                ready = { resumed },
                native = { deliveries++ },
            )
        }
        runCurrent()
        resumed = false
        gate.complete(Unit)
        first.join()
        assertEquals(0, claims)
        assertEquals(0, deliveries)
        resumed = true
        deliverExploreBookDetail(
            { "owned" },
            {
                claims++
                true
            },
            {},
            { handled++ },
            { resumed },
        ) {
            deliveries++
        }
        assertEquals(1, claims)
        assertEquals(1, deliveries)
        assertEquals(1, handled)
    }

    @Test
    fun canceledNonCooperativeClaimRollsBackBeforeAnotherOwnerRetries() = runTest {
        val gate = CompletableDeferred<Unit>()
        var claimed = false
        var rollbacks = 0
        var deliveries = 0
        val pending = launch {
            deliverExploreBookDetail(
                prepare = { "owned" },
                claim = {
                    withContext(NonCancellable) { gate.await() }
                    claimed = true
                    true
                },
                defer = {
                    claimed = false
                    rollbacks++
                },
                handled = {},
                ready = { true },
                native = { deliveries++ },
            )
        }
        runCurrent()
        pending.cancel()
        gate.complete(Unit)
        pending.join()
        assertFalse(claimed)
        assertEquals(1, rollbacks)
        assertEquals(0, deliveries)
        deliverExploreBookDetail(
            { "owned" },
            {
                claimed = true
                true
            },
            {},
            {},
            { true },
        ) {
            deliveries++
        }
        assertTrue(claimed)
        assertEquals(1, deliveries)
    }

    @Test
    fun claimWriteAcceptedButThrowingBeforeReturnStillRollsBack() = runTest {
        var durableClaim = false
        var delivered = false
        try {
            deliverExploreBookDetail(
                prepare = { "owned" },
                claim = {
                    durableClaim = true
                    throw kotlinx.coroutines.CancellationException("Cancelled IO return")
                },
                defer = { durableClaim = false },
                handled = {},
                ready = { true },
                native = { delivered = true },
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertFalse(durableClaim)
            assertFalse(delivered)
        }
    }

    @Test
    fun nativeFailureDefersClaimAndSuccessfulHandoffFinishesReceiptDespiteCancellation() = runTest {
        val calls = mutableListOf<String>()
        try {
            deliverExploreBookDetail(
                { "owned" },
                { true },
                { calls += "deferred" },
                { calls += "handled" },
                { true },
            ) {
                error("no Activity")
            }
        } catch (_: IllegalStateException) {
            assertEquals(listOf("deferred"), calls)
        }
        val gate = CompletableDeferred<Unit>()
        val delivered = launch {
            deliverExploreBookDetail(
                { "owned" },
                { true },
                { calls += "deferred" },
                {
                    gate.await()
                    calls += "handled"
                },
                { true },
            ) {
                calls += "native"
            }
        }
        runCurrent()
        delivered.cancel()
        gate.complete(Unit)
        delivered.join()
        assertEquals(listOf("deferred", "native", "handled"), calls)
    }
}
