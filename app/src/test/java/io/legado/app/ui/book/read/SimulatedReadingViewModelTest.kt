package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedReadingViewModelTest {
    private val dispatcher=StandardTestDispatcher();private val models=mutableListOf<SimulatedReadingViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.stop() };Dispatchers.resetMain() }
    private val initial=SimulatedReadingSettings(false,"2026-10-03","5","3",12)
    private class Requests(val request:SimulatedReadingRequest):SimulatedReadingRequestRepository {
        var fail=false;var gate:CompletableDeferred<Unit>?=null;var released=0
        override suspend fun create(request:SimulatedReadingRequest)="ticket"
        override suspend fun read(ticket:String):SimulatedReadingRequest { withContext(NonCancellable) { gate?.await() };if(fail) error("read error");return request }
        override suspend fun release(ticket:String) { released++ }
    }
    private class Repo:SimulatedReadingRepository {
        val writes=mutableListOf<SimulatedReadingSettings>();var gate:CompletableDeferred<Unit>?=null;var fail=false
        override suspend fun load(bookUrl:String)=error("unused")
        override suspend fun save(bookUrl:String,value:SimulatedReadingSettings):Book {
            gate?.await();if(fail) error("write error");writes+=value
            return Book(bookUrl=bookUrl,readConfig=Book.ReadConfig(readSimulating=value.enabled,startDate=LocalDate.of(2026,10,3),startChapter=value.start.toIntOrNull() ?: 0,dailyChapters=value.daily.toIntOrNull()?.coerceAtLeast(1) ?: 12))
        }
    }
    private fun model(scope:CoroutineScope,requests:Requests,repo:Repo=Repo(),saved:SavedStateHandle=SavedStateHandle())=
        SimulatedReadingViewModel(repo,requests,"ticket",saved,scope).also { models+=it }
    @Test fun unsavedEditAndPickerRestoreWithoutLargeUrlInBundleOrDatabaseWrite()=runTest(dispatcher) {
        val requests=Requests(SimulatedReadingRequest("url".repeat(300000),initial));val repo=Repo();val saved=SavedStateHandle()
        val first=model(backgroundScope,requests,repo,saved);runCurrent();first.enabled(true);first.start("27");first.daily("");first.dateOpen(true)
        val restored=model(backgroundScope,requests,repo,SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }));runCurrent()
        assertEquals("27",restored.state.value.settings!!.start);assertEquals("",restored.state.value.settings!!.daily);assertTrue(restored.state.value.dateOpen)
        assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { n->n<=10 } != false });assertTrue(repo.writes.isEmpty())
    }
    @Test fun originalFiveDigitInputsAndCalendarDateRejectInvalidEdits()=runTest(dispatcher) {
        val vm=model(backgroundScope,Requests(SimulatedReadingRequest("url",initial)));runCurrent()
        vm.start("123456");vm.daily("-1");vm.date("bad");assertEquals(initial,vm.state.value.settings)
        vm.start("");vm.daily("99999");vm.date("2026-09-30");assertEquals("",vm.state.value.settings!!.start);assertEquals("99999",vm.state.value.settings!!.daily);assertEquals("2026-09-30",vm.state.value.settings!!.date)
    }
    @Test fun saveFailureKeepsDraftAndRetryPublishesNormalizedResultExactlyOnce()=runTest(dispatcher) {
        val repo=Repo().apply { fail=true };val vm=model(backgroundScope,Requests(SimulatedReadingRequest("url",initial)),repo);runCurrent()
        vm.start("");vm.daily("");vm.save();runCurrent();assertFalse(vm.state.value.finished);assertNotNull(vm.state.value.error)
        repo.fail=false;vm.retry();runCurrent();assertEquals("0",vm.state.value.settings!!.start);assertEquals("12",vm.state.value.settings!!.daily)
        assertNotNull(vm.claim());assertNull(vm.claim());assertEquals(1,repo.writes.size)
    }
    @Test fun confirmBlocksDuplicateSaveAndCancellationWhileCommitIsInFlight()=runTest(dispatcher) {
        val repo=Repo().apply { gate=CompletableDeferred() };val vm=model(backgroundScope,Requests(SimulatedReadingRequest("url",initial)),repo);runCurrent()
        vm.save();vm.save();vm.cancel();vm.enabled(true);runCurrent();assertTrue(vm.state.value.saving);assertFalse(vm.state.value.finished)
        repo.gate!!.complete(Unit);runCurrent();assertEquals(1,repo.writes.size);assertFalse(repo.writes.single().enabled)
    }
    @Test fun cancelledLateRequestReadCannotReopenDialogOrWrite()=runTest(dispatcher) {
        val requests=Requests(SimulatedReadingRequest("url",initial)).apply { gate=CompletableDeferred() };val repo=Repo();val vm=model(backgroundScope,requests,repo);runCurrent()
        vm.cancel();requests.gate!!.complete(Unit);runCurrent();assertTrue(vm.state.value.finished);assertFalse(vm.state.value.applied);assertNull(vm.state.value.settings);assertTrue(repo.writes.isEmpty())
        vm.release();runCurrent();assertEquals(1,requests.released)
    }
    @Test fun unreadRequestRequiresExplicitRetryBeforeEditingOrSaving()=runTest(dispatcher) {
        val requests=Requests(SimulatedReadingRequest("url",initial)).apply { fail=true };val repo=Repo();val vm=model(backgroundScope,requests,repo);runCurrent()
        vm.start("1");vm.save();runCurrent();assertNull(vm.state.value.settings);assertTrue(repo.writes.isEmpty())
        requests.fail=false;vm.retry();runCurrent();assertEquals(initial,vm.state.value.settings)
    }
}
