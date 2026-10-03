package io.legado.app.ui.file

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileManagementViewModelTest {
    private val dispatcher=StandardTestDispatcher();private val models=mutableListOf<FileManagementViewModel>();private val repos=mutableListOf<FilesRepo>();private val stores=mutableListOf<Store>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.stop() };repos.forEach { it.gates.values.forEach { gate->gate.complete(Unit) };it.openGate?.complete(Unit) };dispatcher.scheduler.runCurrent();Dispatchers.resetMain() }
    private fun model(files:FilesRepo,store:Store=Store(),saved:SavedStateHandle=SavedStateHandle())=FileManagementViewModel(files,store,saved).also { models+=it;repos+=files;stores+=store }
    private fun snapshot(saved:SavedStateHandle)=SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun initialRootAndCaseSensitiveFilterAlwaysKeepExplicitParent()=runTest(dispatcher) {
        val files=FilesRepo();val vm=model(files);runCurrent();assertTrue(vm.state.value.loaded);assertEquals("/root",vm.state.value.directory)
        vm.click("/root/folder");runCurrent();vm.query("alpha");assertEquals(listOf("..","alpha.txt"),vm.state.value.rows.map { it.name });vm.query("ALPHA");assertEquals(listOf(".."),vm.state.value.rows.map { it.name })
    }
    @Test fun directoryAndBreadcrumbNavigationResetQueryAndBackAtRootCloses()=runTest(dispatcher) {
        val vm=model(FilesRepo());runCurrent();vm.click("/root/folder");runCurrent();vm.query("alpha");vm.back();runCurrent();assertEquals("/root",vm.state.value.directory);assertEquals("",vm.state.value.query);assertEquals(listOf("root"),vm.state.value.crumbs.map { it.name });vm.back();runCurrent();assertTrue(vm.state.value.closed)
    }
    @Test fun latestDirectoryWinsEvenWhenOldScanIgnoresCancellation()=runTest(dispatcher) {
        val files=FilesRepo();val vm=model(files);runCurrent();val gate=CompletableDeferred<Unit>();files.gates["/root/folder"]=gate
        vm.navigate("/root/folder");runCurrent();vm.root();runCurrent();assertEquals("/root",vm.state.value.directory);gate.complete(Unit);runCurrent();assertEquals("/root",vm.state.value.directory);assertEquals(listOf("folder","root.txt"),vm.state.value.rows.map { it.name })
    }
    @Test fun scanFailureCanRetryCurrentRequestedDirectoryWithoutLosingExistingBreadcrumbs()=runTest(dispatcher) {
        val files=FilesRepo();val vm=model(files);runCurrent();files.failList="/root/folder";vm.navigate("/root/folder");runCurrent();assertNotNull(vm.state.value.error);assertFalse(vm.state.value.loading)
        files.failList=null;vm.retry();runCurrent();assertEquals("/root/folder",vm.state.value.directory);assertNull(vm.state.value.error);assertEquals(listOf("root","folder"),vm.state.value.crumbs.map { it.name })
    }
    @Test fun millionCharacterQueryRestoresFromDiskWithOnlyUuidAndSmallCursorInSavedState()=runTest(dispatcher) {
        val files=FilesRepo();val store=Store();val saved=SavedStateHandle();val vm=model(files,store,saved);runCurrent();vm.click("/root/folder");runCurrent();val large="query".repeat(200000);vm.query(large,2,4);runCurrent();vm.flush();vm.stop()
        val restored=model(files,store,snapshot(saved));runCurrent();assertEquals("/root/folder",restored.state.value.directory);assertEquals(large,restored.state.value.query);assertEquals(2,restored.state.value.queryStart);assertEquals(4,restored.state.value.queryEnd);assertEquals(listOf(".."),restored.state.value.rows.map { it.name })
        assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { size->size<100 } != false })
    }
    @Test fun deleteRefreshesOnlyManagedRequestedEntryAndNeverDeletesParentSentinel()=runTest(dispatcher) {
        val files=FilesRepo();val vm=model(files);runCurrent();vm.click("/root/folder");runCurrent();vm.delete("/root");runCurrent();assertTrue(files.deleted.isEmpty())
        vm.query("alpha");vm.delete("/root/folder/alpha.txt");runCurrent();assertEquals(listOf("/root/folder/alpha.txt"),files.deleted);assertEquals("",vm.state.value.query);assertEquals(listOf("..","Alpha.txt"),vm.state.value.rows.map { it.name })
    }
    @Test fun nonemptyFolderDeleteReturningFalseKeepsFolderAndRefreshesWithoutRecursiveRemoval()=runTest(dispatcher) {
        val files=FilesRepo();files.deleteResult=false;val vm=model(files);runCurrent();vm.delete("/root/folder");runCurrent();assertEquals(listOf("folder","root.txt"),vm.state.value.rows.map { it.name });assertEquals(listOf("/root/folder"),files.deleted);assertNull(vm.state.value.error)
    }
    @Test fun ordinaryFileOpenStoresFullNativeUriAndDisablesRepeatWhileRequestPending()=runTest(dispatcher) {
        val files=FilesRepo();files.uri="content://provider/"+"long".repeat(5000);val store=Store();val vm=model(files,store);runCurrent();vm.click("/root/root.txt");runCurrent();assertEquals(files.uri,vm.state.value.navigation!!.uri);assertFalse(vm.state.value.canAct)
        vm.click("/root/root.txt");runCurrent();assertEquals(1,files.opens);assertEquals(files.uri,store.records.values.single().navigation!!.uri)
    }
    @Test fun failedOpenLeavesVisibleFileForExplicitRetryAndNeverStagesNativeAction()=runTest(dispatcher) {
        val files=FilesRepo();files.failOpen=true;val vm=model(files);runCurrent();vm.click("/root/root.txt");runCurrent();assertNotNull(vm.state.value.error);assertNull(vm.state.value.navigation);assertTrue(vm.state.value.canAct)
        files.failOpen=false;vm.click("/root/root.txt");runCurrent();assertNotNull(vm.state.value.navigation);assertNull(vm.state.value.error)
    }
    @Test fun unreadDraftIsNeverOverwrittenAndRetryRestoresExactDirectoryAndQuery()=runTest(dispatcher) {
        val files=FilesRepo();val store=Store();val saved=SavedStateHandle(mapOf("file.management.ticket" to "ticket"));val original=FileManagementDraft("/root/folder","alpha",revision=2);store.records["ticket"]=original;store.failRead=true
        val vm=model(files,store,saved);runCurrent();assertFalse(vm.state.value.loaded);vm.query("lost");vm.navigate(null);assertEquals(original,store.records["ticket"])
        store.failRead=false;vm.retry();runCurrent();assertEquals("alpha",vm.state.value.query);assertEquals("/root/folder",vm.state.value.directory)
    }
    @Test fun failedDraftWriteRetriesLatestQueryWithoutResettingInputs()=runTest(dispatcher) {
        val files=FilesRepo();val store=Store();val vm=model(files,store);runCurrent();store.failWrite=true;vm.query("latest");runCurrent();assertNotNull(vm.state.value.error)
        store.failWrite=false;vm.retry();runCurrent();assertEquals("latest",store.records.values.single().query);assertEquals("latest",vm.state.value.query);assertNull(vm.state.value.error)
    }
    @Test fun stoppedLateNonCooperativeScanFailurePublishesNothingAndLateOpenCannotRecreateClosedDraft()=runTest(dispatcher) {
        val files=FilesRepo();val vm=model(files);runCurrent();val gate=CompletableDeferred<Unit>();files.gates["/root/folder"]=gate;files.failList="/root/folder";vm.navigate("/root/folder");runCurrent();vm.stop();val before=vm.state.value;gate.complete(Unit);runCurrent();assertEquals(before,vm.state.value)
        val slow=FilesRepo();slow.openGate=CompletableDeferred();val store=Store();val closing=model(slow,store);runCurrent();closing.click("/root/root.txt");runCurrent();closing.close();runCurrent();slow.openGate!!.complete(Unit);runCurrent();assertNull(closing.state.value.navigation);assertTrue(store.records.isEmpty());assertEquals(1,store.released.size)
    }
    @Test fun closedReceiptRestorationReleasesSessionWithoutScanningOrOpeningFile()=runTest(dispatcher) {
        val files=FilesRepo();val store=Store();store.records["ticket"]=FileManagementDraft(query="private");val vm=model(files,store,SavedStateHandle(mapOf("file.management.ticket" to "ticket","file.management.closed" to true)));runCurrent();assertTrue(vm.state.value.closed);assertFalse(vm.state.value.loading);assertTrue(store.records.isEmpty());assertEquals(0,files.scans)
    }
    @Test fun nativeFileOpenIsConsumedDurablyOnceAndCannotRepeatAfterDiskRestore()=runTest(dispatcher) {
        val files=FilesRepo();val store=Store();val saved=SavedStateHandle();val vm=model(files,store,saved);runCurrent();vm.click("/root/root.txt");runCurrent();val pending=vm.state.value.navigation!!
        assertEquals(pending,vm.consumeOpen(pending.token){true});assertNull(vm.consumeOpen(pending.token){true});vm.stop();val restored=model(files,store,snapshot(saved));runCurrent();assertNull(restored.state.value.navigation);assertEquals(1,files.opens)
    }
    @Test fun canceledNonCooperativeClaimRollsBackFullPendingUriWithoutDelivering()=runTest(dispatcher) {
        val files=FilesRepo();val store=Store();val vm=model(files,store);runCurrent();vm.click("/root/root.txt");runCurrent();val pending=vm.state.value.navigation!!
        val gate=CompletableDeferred<Unit>();store.claimGate=gate;val job=launch { vm.consumeOpen(pending.token){true};error("Canceled claim delivered") };runCurrent();job.cancel();gate.complete(Unit);runCurrent()
        assertTrue(job.isCancelled);assertEquals(pending,vm.state.value.navigation);assertEquals(pending,store.records.values.single().navigation);assertNull(vm.consumeOpen(pending.token){false});assertEquals(pending,vm.consumeOpen(pending.token){true})
    }
    private class FilesRepo:FileManagementRepository {
        val deleted=mutableListOf<String>();var deleteResult=true;var scans=0;var opens=0;var uri="content://provider/root.txt";var failList:String?=null;var failOpen=false
        val gates=mutableMapOf<String,CompletableDeferred<Unit>>();var openGate:CompletableDeferred<Unit>?=null
        val snapshots=mutableMapOf(
            "/root" to ManagedDirectory("/root","/root",listOf(ManagedFileCrumb("/root","root")),listOf(ManagedFile("/root/folder","folder",ManagedFileKind.Directory),ManagedFile("/root/root.txt","root.txt",ManagedFileKind.File))),
            "/root/folder" to ManagedDirectory("/root","/root/folder",listOf(ManagedFileCrumb("/root","root"),ManagedFileCrumb("/root/folder","folder")),listOf(ManagedFile("/root","..",ManagedFileKind.Parent),ManagedFile("/root/folder/Alpha.txt","Alpha.txt",ManagedFileKind.File),ManagedFile("/root/folder/alpha.txt","alpha.txt",ManagedFileKind.File))))
        override suspend fun list(directory:String?):ManagedDirectory { scans++;val path=directory ?: "/root";val snapshot=snapshots[path]!!;gates[path]?.let { withContext(NonCancellable) { it.await() } };if(failList==path) error("scan failed");return snapshot }
        override suspend fun delete(path:String):Boolean { deleted+=path;if(deleteResult) snapshots.replaceAll { _,value->value.copy(entries=value.entries.filter { it.path!=path }) };return deleteResult }
        override suspend fun open(path:String):String { opens++;openGate?.let { withContext(NonCancellable) { it.await() } };if(failOpen) error("open failed");return uri }
    }
    private class Store:FileManagementDraftRepository {
        val records=mutableMapOf<String,FileManagementDraft>();val released=mutableSetOf<String>();var failRead=false;var failWrite=false;var claimGate:CompletableDeferred<Unit>?=null
        override suspend fun read(ticket:String):FileManagementDraft? { if(failRead) error("read failed");return records[ticket] }
        override suspend fun write(ticket:String,draft:FileManagementDraft) {
            if(draft.navigation==null && records[ticket]?.navigation!=null) { val gate=claimGate;claimGate=null;gate?.let { withContext(NonCancellable) { it.await() } } }
            check(ticket !in released);if(failWrite) error("write failed");if((records[ticket]?.revision ?: -1)<=draft.revision) records[ticket]=draft }
        override suspend fun release(ticket:String) { released+=ticket;records.remove(ticket) }
    }
}
