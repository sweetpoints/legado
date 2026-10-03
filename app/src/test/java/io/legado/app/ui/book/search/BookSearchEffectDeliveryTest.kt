package io.legado.app.ui.book.search

import io.legado.app.model.webBook.BookSearchEffect
import io.legado.app.model.webBook.BookSearchReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSearchEffectDeliveryTest {
    @Test
    fun pausedPreparedDestinationIsReleasedWithoutConsumingAndResumedDeliveryIsOnce() = runTest {
        val fixture = Fixture()
        fixture.prepareGate = CompletableDeferred()
        val pending = launch { fixture.delivery.deliver(fixture.receipt) }
        runCurrent()
        fixture.resumed = false
        fixture.prepareGate!!.complete(Unit)
        pending.join()
        assertEquals(listOf("destination"), fixture.abandoned)
        assertEquals(fixture.receipt, fixture.pending)
        assertTrue(fixture.delivered.isEmpty())

        fixture.resumed = true
        fixture.delivery.deliver(fixture.receipt)
        fixture.delivery.deliver(fixture.receipt)
        assertEquals(listOf(fixture.receipt), fixture.delivered)
        assertNull(fixture.pending)
        assertEquals(listOf("consumed", "handled"), fixture.order)
    }

    @Test
    fun cancellationAfterPreparationReleasesOnlyUnreturnedDestination() = runTest {
        val fixture = Fixture()
        val owned = PreparedBookSearchEffect(fixture.receipt, "owned-destination")
        val gate = CompletableDeferred<Unit>()
        val delivery =
            BookSearchEffectDelivery(
                available = { fixture.resumed },
                prepare = { owned },
                consume = {
                    fixture.resumed = false
                    null
                },
                handle = { error("must not deliver") },
                abandon = {
                    fixture.abandoned += it.bookInfoTicket!!
                    gate.await()
                },
                failure = { fixture.failures += it },
            )
        val pending = launch { delivery.deliver(fixture.receipt) }
        runCurrent()
        pending.cancel()
        gate.complete(Unit)
        pending.join()
        assertEquals(listOf("owned-destination"), fixture.abandoned)
        assertEquals(fixture.receipt, fixture.pending)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun platformLaunchFailureConsumesOnceAndCleansPreparedDestination() = runTest {
        val fixture = Fixture()
        fixture.failPlatform = true
        fixture.delivery.deliver(fixture.receipt)
        fixture.delivery.deliver(fixture.receipt)
        assertNull(fixture.pending)
        assertTrue(fixture.delivered.isEmpty())
        assertEquals(1, fixture.failures.size)
        assertEquals(2, fixture.abandoned.size)
    }

    private class Fixture {
        val receipt = BookSearchReceipt("receipt", BookSearchEffect.BookInfo, bookId = "url")
        var pending: BookSearchReceipt? = receipt
        var resumed = true
        var failPlatform = false
        var prepareGate: CompletableDeferred<Unit>? = null
        val delivered = mutableListOf<BookSearchReceipt>()
        val abandoned = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val order = mutableListOf<String>()
        val delivery =
            BookSearchEffectDelivery(
                available = { resumed },
                prepare = {
                    prepareGate?.await()
                    PreparedBookSearchEffect(it, "destination")
                },
                consume = { id ->
                    pending
                        ?.takeIf { it.id == id }
                        ?.also {
                            pending = null
                            order += "consumed"
                        }
                },
                handle = {
                    check(pending == null)
                    check(!failPlatform) { "synthetic platform failure" }
                    order += "handled"
                    delivered += it.receipt
                },
                abandon = { abandoned += it.bookInfoTicket!! },
                failure = { failures += it },
            )
    }
}
