package io.legado.app.data.repository

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaReaderSessionControllerTest {
    private class Store : MangaReaderSessionRepository {
        var value: MangaReaderSession? = null
        var blockedWrite: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        var deleted = false

        override suspend fun read(session: String) = value

        override suspend fun write(session: String, value: MangaReaderSession) {
            entered?.complete(Unit)
            blockedWrite?.await()
            check(!deleted)
            this.value = value
        }

        override suspend fun release(session: String) {
            deleted = true
            value = null
        }
    }

    @Test
    fun concurrentResumedClaimsDispatchOnlyOnceAndRetainFullPayload() = runBlocking {
        val store = Store()
        val controller = MangaReaderSessionController(UUID.randomUUID().toString(), store)
        val hugeUrl = "https://fixture.invalid/" + "chapter/".repeat(50000)
        controller.restore(MangaReaderLaunch(bookUrl = hugeUrl))
        val request =
            MangaNativeRequest(
                ticket = UUID.randomUUID().toString(),
                kind = MangaNativeKind.ChapterBrowser,
                bookUrl = hugeUrl,
                imageUrl = hugeUrl,
            )
        controller.enqueue(request)
        var dispatched = 0
        assertFalse(
            controller.claimAndDispatch(request.ticket, resumed = { false }) { dispatched++ }
        )
        val first = async { controller.claimAndDispatch(request.ticket, { true }) { dispatched++ } }
        val second = async {
            controller.claimAndDispatch(request.ticket, { true }) { dispatched++ }
        }
        assertEquals(listOf(false, true), listOf(first.await(), second.await()).sorted())
        assertEquals(1, dispatched)
        assertEquals(hugeUrl, store.value?.nativeRequests?.single()?.imageUrl)
        controller.complete(request.ticket)
        assertEquals(MangaNativePhase.Complete, store.value?.nativeRequests?.single()?.phase)
    }

    @Test
    fun cancellationDuringAcceptedClaimStillDispatchesAndPreservesReceipt() = runBlocking {
        val store = Store()
        val controller = MangaReaderSessionController(UUID.randomUUID().toString(), store)
        controller.restore(MangaReaderLaunch())
        val request = MangaNativeRequest(UUID.randomUUID().toString(), MangaNativeKind.Catalog)
        controller.enqueue(request)
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        store.entered = entered
        store.blockedWrite = proceed
        var dispatched = false
        val claimant = launch {
            controller.claimAndDispatch(request.ticket, { true }) { dispatched = true }
        }
        entered.await()
        claimant.cancel()
        proceed.complete(Unit)
        claimant.join()
        assertTrue(dispatched)
        assertEquals(
            MangaNativePhase.Claimed,
            controller.state.value?.nativeRequests?.single()?.phase,
        )
        assertEquals(controller.state.value, store.value)
    }

    @Test
    fun pausedOwnerDuringClaimIOLeavesRequestPendingWithoutDispatch() = runBlocking {
        val store = Store()
        val controller = MangaReaderSessionController(UUID.randomUUID().toString(), store)
        controller.restore(MangaReaderLaunch())
        val request = MangaNativeRequest(UUID.randomUUID().toString(), MangaNativeKind.Catalog)
        controller.enqueue(request)
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        store.entered = entered
        store.blockedWrite = proceed
        var resumed = true
        var dispatched = false
        val claimant = async {
            controller.claimAndDispatch(request.ticket, { resumed }) { dispatched = true }
        }
        entered.await()
        resumed = false
        proceed.complete(Unit)
        assertFalse(claimant.await())
        assertFalse(dispatched)
        assertEquals(MangaNativePhase.Pending, store.value?.nativeRequests?.single()?.phase)
    }

    @Test
    fun releasedOwnerCannotWriteAnotherCheckpoint() = runBlocking {
        val store = Store()
        val controller = MangaReaderSessionController(UUID.randomUUID().toString(), store)
        controller.restore(MangaReaderLaunch())
        controller.release()
        controller.checkpoint(menuVisible = true, chapterIndex = 9, pageIndex = 8)
        assertTrue(store.deleted)
        assertNull(store.value)
        assertNull(controller.state.value)
    }
}
