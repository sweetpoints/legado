package io.legado.app.model.remote

import io.legado.app.data.entities.Book
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBookUploadBridgeTest {
    @Test fun legacyContractPersistsExactlyOnceAfterTransferOriginMutationAndRetainsOverwriteFlag()=runTest {
        val book=Book(bookUrl="book",origin="local");val order=mutableListOf<String>()
        finishRemoteBookUpload(book,false,{native,overwrite->
            assertSame(book,native);assertFalse(overwrite);assertEquals("local",native.origin)
            native.origin="uploaded-origin";order+="transfer"
        }){native->assertSame(book,native);assertEquals("uploaded-origin",native.origin);order+="persist"}
        assertEquals(listOf("transfer","persist"),order)
    }
    @Test fun transferFailureDoesNotPersistAndCancellationBeforeTransferCompletionDoesNotStartPersistence()=runTest {
        var saves=0;assertTrue(runCatching{finishRemoteBookUpload(Book(),true,{_,_->error("HTTP conflict")}){saves++}}.isFailure)
        val gate=CompletableDeferred<Unit>()
        val job=launch{finishRemoteBookUpload(Book(),true,{_,_->gate.await()}){saves++}}
        runCurrent();job.cancel();gate.complete(Unit);job.join();assertEquals(0,saves)
    }
}
