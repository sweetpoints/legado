package io.legado.app.model.jsSource

import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceAcceptedWriteTest {
    @Test
    fun cancelDuringAcceptedWriteStillDeliversReceiptInIoContext() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var accepted = 0
        var delivered = 0
        val source = BookSource(bookSourceUrl = "source")
        val operation =
            async(Dispatchers.IO) {
                JsSourceUpsert.acceptedWrite(
                    onAccepted = { result ->
                        assertEquals(source.bookSourceUrl, result.bookSourceUrl)
                        delivered++
                    }
                ) {
                    accepted++
                    entered.complete(Unit)
                    release.await()
                    source
                }
            }
        entered.await()
        operation.cancel()
        release.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(1, accepted)
        assertEquals(1, delivered)
    }

    @Test
    fun canceledCallerCannotBeginAcceptedWriteOrReceipt() = runBlocking {
        var writes = 0
        var receipts = 0
        val canceledJob = Job().apply { cancel() }
        val operation =
            CoroutineScope(canceledJob).launch(start = CoroutineStart.UNDISPATCHED) {
                JsSourceUpsert.acceptedWrite(onAccepted = { receipts++ }) {
                    writes++
                    BookSource()
                }
            }
        operation.join()
        assertEquals(0, writes)
        assertEquals(0, receipts)
    }

    @Test
    fun absentCallbackKeepsLegacyWriteCancelable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var completed = false
        val operation =
            async(Dispatchers.IO) {
                JsSourceUpsert.acceptedWrite(onAccepted = null) {
                    entered.complete(Unit)
                    release.await()
                    completed = true
                    BookSource()
                }
            }
        entered.await()
        operation.cancel()
        release.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(false, completed)
    }
}
