package io.legado.app.ui.main.rss

import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class MainRssDeliveryTest {
    private val pending = MainRssPrepared(MainRssAction.Open.name, "nonce", navigation =
        MainRssNavigation(MainRssDestination.ReaderHtml, "origin", "Feed", "H".repeat(2000000)))
    private class Launches : RssReaderLaunchRepository {
        var request: RssReaderRequest? = null; val released = mutableListOf<String>()
        var afterStage: suspend () -> Unit = {}; var fail = false
        override suspend fun stage(request: RssReaderRequest): String {
            if (fail) error("IO failed")
            this.request = request; withContext(NonCancellable) { afterStage() }; return "own-ticket"
        }
        override suspend fun read(ticket: String) = request
        override suspend fun release(ticket: String) { released += ticket }
    }
    @Test fun fullReaderInputIsStagedBeforeAckAndNativeReceivesOnlySmallTicket() = runTest {
        val launches = Launches(); var ack = false; var called = false
        deliverMainRssRequest(launches, { pending }, { true }, {
            assertEquals(pending.navigation!!.value, launches.request!!.startHtml); ack = true; true
        }, { fail("Missing") }, { value, ticket ->
            assertTrue(ack); assertEquals(pending.nonce, value.nonce); assertEquals("own-ticket", ticket); called = true
        })
        assertTrue(called); assertTrue(launches.released.isEmpty())
    }
    @Test fun pauseAfterStagingKeepsPendingAndReleasesOnlyOwnTicket() = runTest {
        var ready = true; var ack = false; val launches = Launches().apply { afterStage = { ready = false } }
        deliverMainRssRequest(launches, { pending }, { ready }, { ack = true; true }, {}, { _, _ -> fail("Paused") })
        assertFalse(ack); assertEquals(listOf("own-ticket"), launches.released)
    }
    @Test fun canceledNonCooperativeStageCannotAckOrLaunchAndCleansOwnOutput() = runTest {
        val gate = CompletableDeferred<Unit>(); val launches = Launches().apply { afterStage = { gate.await() } }; var ack = false
        val job = launch { deliverMainRssRequest(launches, { pending }, { true }, { ack = true; true }, {}, { _, _ -> fail("Canceled") }) }
        runCurrent(); job.cancel(); gate.complete(Unit); job.join()
        assertFalse(ack); assertEquals(listOf("own-ticket"), launches.released)
    }
    @Test fun staleNonceCannotTransferOwnershipAndNativeFailureDoesNotLeakOutput() = runTest {
        val launches = Launches()
        deliverMainRssRequest(launches, { pending }, { true }, { false }, {}, { _, _ -> fail("Stale") })
        assertEquals(listOf("own-ticket"), launches.released); launches.released.clear()
        try { deliverMainRssRequest(launches, { pending }, { true }, { true }, {}, { _, _ -> error("Host unavailable") }); fail("Must throw") }
        catch (_: IllegalStateException) {}
        assertEquals(listOf("own-ticket"), launches.released)
    }
    @Test fun failedStageDoesNotConsumeAndRetryDoesNotNeedAnotherSourceEvaluation() = runTest {
        val launches = Launches().apply { fail = true }; var ack = 0; var delivered = 0
        try { deliverMainRssRequest(launches, { pending }, { true }, { ack++; true }, {}, { _, _ -> delivered++ }); fail("Must throw") }
        catch (_: IllegalStateException) {}
        assertEquals(0, ack); launches.fail = false
        deliverMainRssRequest(launches, { pending }, { true }, { ack++; true }, {}, { _, _ -> delivered++ })
        assertEquals(1, ack); assertEquals(1, delivered)
    }
    @Test fun nonReaderActionsAndMissingEntitiesDoNotCreateLaunchFiles() = runTest {
        val launches = Launches(); var native = 0; var missing = 0
        deliverMainRssRequest(launches, { pending.copy(action = MainRssAction.History.name, navigation = null) }, { true }, { true }, {}, { _, ticket -> assertNull(ticket); native++ })
        deliverMainRssRequest(launches, { null }, { true }, { true }, { missing++ }, { _, _ -> fail("Missing") })
        assertNull(launches.request); assertEquals(1, native); assertEquals(1, missing)
    }
}
