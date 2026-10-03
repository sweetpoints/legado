package io.legado.app.ui.book.info.detail

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailPreferencesViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    @Test fun rapidTogglePublishesImmediatelyAndSeriallyRestoresOriginalValue()=runTest(dispatcher) {
        val repo=Repository();val model=BookDetailPreferencesViewModel(SavedStateHandle(),repo)
        val gate=CompletableDeferred<Unit>();repo.writeGate=gate
        try{runCurrent();model.toggle(BookDetailPreference.DeleteAlert);assertFalse(model.state.value.values.deleteAlert);runCurrent()
            model.toggle(BookDetailPreference.DeleteAlert);assertTrue(model.state.value.values.deleteAlert);assertEquals(1,repo.writes.size)
            gate.complete(Unit);runCurrent();assertEquals(listOf(BookDetailPreference.DeleteAlert to false,BookDetailPreference.DeleteAlert to true),repo.writes)
            assertTrue(repo.actual.deleteAlert);assertTrue(model.state.value.dirty.isEmpty());assertFalse(model.state.value.saving)
        }finally{gate.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun staleResumedReadCannotOverwriteNewerRequestedOrAlreadyCommittedBoolean()=runTest(dispatcher) {
        val repo=Repository();val model=BookDetailPreferencesViewModel(SavedStateHandle(),repo);val gate=CompletableDeferred<Unit>()
        try{runCurrent();repo.readGate=gate;model.refresh();runCurrent();model.toggle(BookDetailPreference.DeleteAlert);runCurrent()
            assertFalse(repo.actual.deleteAlert);assertFalse(model.state.value.values.deleteAlert)
            gate.complete(Unit);runCurrent();assertFalse(model.state.value.values.deleteAlert)
        }finally{gate.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun earlierFailedFieldRemainsDirtyAndBlocksServiceAfterLaterDifferentFieldSucceeds()=runTest(dispatcher) {
        val repo=Repository();val model=BookDetailPreferencesViewModel(SavedStateHandle(),repo)
        try{runCurrent();repo.failed=BookDetailPreference.DeleteAlert;model.toggle(BookDetailPreference.DeleteAlert);runCurrent()
            model.toggle(BookDetailPreference.UploadImported);runCurrent()
            assertTrue(model.state.value.values.uploadImported);assertTrue(repo.actual.uploadImported)
            assertFalse(model.state.value.values.deleteAlert);assertTrue(repo.actual.deleteAlert)
            assertEquals(setOf(BookDetailPreference.DeleteAlert),model.state.value.dirty);assertNotNull(model.state.value.error)
            assertTrue(runCatching{model.requireCommitted()}.isFailure)
            assertEquals(2,repo.writes.size)
            repo.failed=null;model.retry();runCurrent();assertFalse(model.requireCommitted().deleteAlert)
            assertTrue(model.state.value.dirty.isEmpty());assertNull(model.state.value.error)
        }finally{model.stop();runCurrent()}
    }
    @Test fun cancelledOwnerRestoresOnlySmallPendingBooleanAndAppliesIdempotentDelta()=runTest(dispatcher) {
        val saved=SavedStateHandle();val repo=Repository();val gate=CompletableDeferred<Unit>();repo.writeGate=gate
        val model=BookDetailPreferencesViewModel(saved,repo)
        try{runCurrent();model.set(BookDetailPreference.DeleteOriginal,true);runCurrent();model.stop();runCurrent()
            assertEquals(setOf("book.detail.preference.DeleteOriginal"),saved.keys());assertEquals(true,saved.get<Boolean>(saved.keys().single()))
            gate.complete(Unit);repo.writeGate=null
            val restored=BookDetailPreferencesViewModel(SavedStateHandle(saved.keys().associateWith{saved.get<Boolean>(it)}),repo)
            try{runCurrent();assertTrue(restored.requireCommitted().deleteOriginal);assertTrue(restored.state.value.dirty.isEmpty())}
            finally{restored.stop();runCurrent()}
        }finally{gate.complete(Unit);model.stop();runCurrent()}
    }
    @Test fun freshFieldPatchRetainsConcurrentHostChangesToUnrelatedPreferences()=runTest(dispatcher) {
        val repo=Repository();val model=BookDetailPreferencesViewModel(SavedStateHandle(),repo)
        try{runCurrent();repo.actual=repo.actual.copy(uploadImported=true);model.set(BookDetailPreference.DeleteOriginal,true);runCurrent()
            assertTrue(model.requireCommitted().deleteOriginal);assertTrue(model.state.value.values.uploadImported);assertTrue(repo.actual.uploadImported)
        }finally{model.stop();runCurrent()}
    }
    @Test fun initialReadFailureCannotPassServiceGateAndRetryLoadsActualPreferences()=runTest(dispatcher) {
        val repo=Repository();repo.readFailure=true;val model=BookDetailPreferencesViewModel(SavedStateHandle(),repo)
        try{runCurrent();assertFalse(model.state.value.loaded);assertTrue(runCatching{model.requireCommitted()}.isFailure)
            repo.readFailure=false;repo.actual=repo.actual.copy(deleteAlert=false);model.retry();runCurrent()
            assertFalse(model.requireCommitted().deleteAlert);assertTrue(model.state.value.loaded)
        }finally{model.stop();runCurrent()}
    }
    private class Repository:BookDetailServicesRepository {
        var actual=BookDetailPreferences(true,false,false,true);val writes=mutableListOf<Pair<BookDetailPreference,Boolean>>()
        var writeGate:CompletableDeferred<Unit>?=null;var readGate:CompletableDeferred<Unit>?=null;var failed:BookDetailPreference?=null;var readFailure=false
        override suspend fun preferences():BookDetailPreferences {
            val observed=actual;readGate?.let{withContext(NonCancellable){it.await()}};if(readFailure)error("read failed");return observed
        }
        override suspend fun preference(field:BookDetailPreference,value:Boolean):BookDetailPreferences {
            writes+=field to value;writeGate?.await();if(field==failed)error("write failed")
            actual=when(field){BookDetailPreference.DeleteAlert->actual.copy(deleteAlert=value)
                BookDetailPreference.DeleteOriginal->actual.copy(deleteOriginal=value)
                BookDetailPreference.UploadImported->actual.copy(uploadImported=value)};return actual
        }
        override suspend fun refreshInput(book:BookDetailBook,source:BookDetailSource?)=error("unexpected refresh")
        override suspend fun remoteExists(book:BookDetailBook)=error("unexpected exists")
        override suspend fun upload(book:BookDetailBook,overwrite:Boolean)=error("unexpected upload")
        override suspend fun delete(book:BookDetailBook,deleteOriginal:Boolean,deleteRemote:Boolean)=error("unexpected delete")
        override suspend fun clearCache(book:BookDetailBook)=Unit
        override suspend fun download(book:BookDetailBook,source:BookDetailSource?,file:BookDetailWebFile)=error("unexpected download")
        override suspend fun archiveEntries(uri:String)=emptyList<String>()
        override suspend fun importArchive(book:BookDetailBook,uri:String,entry:String)=error("unexpected archive")
        override suspend fun variable(book:BookDetailBook,source:BookDetailSource?,sourceVariable:Boolean,comment:String)=error("unexpected variable")
        override suspend fun sourceVariable(source:BookDetailSource?,key:String,value:String?)=Unit
        override suspend fun updateTask(book:BookDetailBook,name:String)=error("unexpected update")
    }
}
