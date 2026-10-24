package io.legado.app.ui.replace

import androidx.lifecycle.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceManagerNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ReplaceManagementViewModel>()
    private val owners = mutableListOf<ReplaceManagerNativeCoordinator>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { owners.forEach { it.close() }; models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Repo : ReplaceManagementRepository {
        override fun rows(filter: ReplaceManagementFilter) = MutableStateFlow(listOf(ReplaceManagementRow(1,"Rule","Rule",null,false,42)))
        override fun groups() = MutableStateFlow(emptyList<String>())
        override suspend fun enabled(ids: List<Long>, value: Boolean) {}
        override suspend fun group(ids: List<Long>, value: String, add: Boolean) {}
        override suspend fun edge(ids: List<Long>, top: Boolean) {}
        override suspend fun move(id: Long, target: Long, after: Boolean) {}
        override suspend fun delete(ids: List<Long>) {}
        override suspend fun export(ids: List<Long>) = ReplaceManagementExport("owned.json")
        override suspend fun releaseExport(path: String) {}
        override suspend fun importHistory() = emptyList<String>()
        override suspend fun rememberImport(value: String) {}
        override suspend fun forgetImport(value: String) {}
        override suspend fun manual() = false
        override suspend fun manual(value: Boolean) {}
        override suspend fun refreshPipeline() {}
    }
    private class Sessions : ReplaceManagementSessionRepository {
        var value: ReplaceManagementCheckpoint? = null
        override suspend fun read(session: String) = value
        override suspend fun write(session: String, value: ReplaceManagementCheckpoint) { this.value=value }
        override suspend fun release(session: String) {}
    }
    private class Importer : ReplaceRulePreparedImportRepository {
        val prepared = mutableListOf<String>()
        val released = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun prepare(source: String): String {
            val handle="handle-${prepared.size}";prepared+=handle
            val current=gate
            // A native parser may finish staging after its Activity has paused or closed.
            if (current != null) suspendCoroutine<Unit> { continuation ->
                current.invokeOnCompletion { continuation.resume(Unit) }
            }
            return handle
        }
        override suspend fun release(session: String) { released+=session }
    }
    private fun model(): ReplaceManagementViewModel = ReplaceManagementViewModel(Repo(),Sessions(),SavedStateHandle(),object : ReplaceManagementSharingRepository {
        override suspend fun feedback(url: String) = ReplaceManagementShareFeedback(url)
        override suspend fun passphrase(url: String) = "encoded"
    }).also {
        models+=it;it.bind(ReplaceManagementLabels("Enabled","Disabled","No group"))
    }
    private fun coordinator(m: ReplaceManagementViewModel, h: Host, scope: CoroutineScope, importer: Importer,
        ready: () -> Boolean = { true }, native: (ReplaceManagementNative,String?) -> Unit): ReplaceManagerNativeCoordinator =
        ReplaceManagerNativeCoordinator(m,h.lifecycle,scope,ready,native,{},importer,dispatcher).also { owners+=it }

    @Test fun pausedPreparationReleasesItsHandleAndResumeDeliversSameNonceExactlyOnce() = runTest(dispatcher) {
        val m=model();runCurrent();val h=Host().apply { registry.currentState=Lifecycle.State.RESUMED }
        val importer=Importer().apply { gate=CompletableDeferred() };val received=mutableListOf<Pair<String,String?>>()
        val c=coordinator(m,h,backgroundScope,importer) { request,handle -> received+=request.effect.nonce to handle }
        m.effect(ReplaceManagementAction.ImportUrl,input="https://fixture.invalid");runCurrent();val nonce=m.state.value.pending!!.nonce
        assertEquals(listOf("handle-0"),importer.prepared)
        h.registry.currentState=Lifecycle.State.STARTED;runCurrent();importer.gate!!.complete(Unit);runCurrent()
        assertTrue(received.isEmpty());assertEquals(listOf("handle-0"),importer.released);assertEquals(nonce,m.state.value.pending!!.nonce)
        importer.gate=null;h.registry.currentState=Lifecycle.State.RESUMED;c.onHostReady();runCurrent()
        assertEquals(listOf(nonce to "handle-1"),received);assertNull(m.state.value.pending)
        c.onHostReady();h.registry.currentState=Lifecycle.State.STARTED;h.registry.currentState=Lifecycle.State.RESUMED;runCurrent()
        assertEquals(1,received.size);assertEquals(listOf("handle-0"),importer.released)
    }
    @Test fun closingPreparationReleasesLateHandleWithoutConsumingOrLaunchingTheEffect() = runTest(dispatcher) {
        val m=model();runCurrent();val h=Host().apply { registry.currentState=Lifecycle.State.RESUMED }
        val importer=Importer().apply { gate=CompletableDeferred() };var calls=0
        val c=coordinator(m,h,backgroundScope,importer) { _,_ -> calls++ }
        m.effect(ReplaceManagementAction.ImportUrl,input="https://fixture.invalid");runCurrent();val nonce=m.state.value.pending!!.nonce
        c.close();importer.gate!!.complete(Unit);runCurrent()
        assertEquals(0,calls);assertEquals(listOf("handle-0"),importer.released);assertEquals(nonce,m.state.value.pending!!.nonce)
    }
    @Test fun changedNonceRetiresOldPreparedHandleAndNativeFailureReleasesNewHandleWithoutRelaunch() = runTest(dispatcher) {
        val m=model();runCurrent();val h=Host().apply { registry.currentState=Lifecycle.State.RESUMED }
        val importer=Importer().apply { gate=CompletableDeferred() };val received=mutableListOf<String>()
        val c=coordinator(m,h,backgroundScope,importer) { request,_ -> received+=request.effect.nonce;error("native launch failed") }
        m.effect(ReplaceManagementAction.ImportUrl,input="old");runCurrent();val old=m.state.value.pending!!.nonce
        assertTrue(m.delivered(old));m.effect(ReplaceManagementAction.ImportUrl,input="new");runCurrent();val next=m.state.value.pending!!.nonce
        importer.gate!!.complete(Unit);importer.gate=null;runCurrent()
        assertEquals(listOf(next),received);assertEquals(listOf("handle-0","handle-1"),importer.released);assertNotNull(m.state.value.error)
        c.onHostReady();runCurrent();assertEquals(1,received.size)
    }
    @Test fun hostReadySignalDeliversDeferredEffectOnceWithoutReplayingOnLaterResume() = runTest(dispatcher) {
        val m=model();runCurrent();val h=Host().apply { registry.currentState=Lifecycle.State.RESUMED }
        val importer=Importer();var ready=false;val received=mutableListOf<String>()
        val c=coordinator(m,h,backgroundScope,importer,{ ready }) { request,_ -> received+=request.effect.nonce }
        m.effect(ReplaceManagementAction.Add);runCurrent();val nonce=m.state.value.pending!!.nonce
        assertTrue(received.isEmpty());ready=true;c.onHostReady();runCurrent()
        assertEquals(listOf(nonce),received);assertNull(m.state.value.pending)
        c.onHostReady();h.registry.currentState=Lifecycle.State.STARTED;h.registry.currentState=Lifecycle.State.RESUMED;runCurrent()
        assertEquals(listOf(nonce),received);assertTrue(importer.prepared.isEmpty())
    }
    @Test fun protocolPreservesUnboundedPrivateQueryRawRangesAndRejectsForeignOrOverflowedCommands() = runTest(dispatcher) {
        val m=model();runCurrent();val text="Q".repeat(2000000)
        replaceManagerCommand(mapOf("op" to "query","text" to text,"start" to 8,"end" to 3),m,{})
        val snapshot=replaceManagerSnapshot(m.state.value,"binding",1,emptyMap());assertEquals(text,snapshot["query"]);assertEquals(8,snapshot["queryStart"]);assertEquals(3,snapshot["queryEnd"])
        assertThrows(IllegalArgumentException::class.java) { replaceManagerCommand(mapOf("op" to "query","text" to "x","start" to 4294967296L,"end" to 0),m,{}) }
        assertThrows(IllegalArgumentException::class.java) { replaceManagerCommand(mapOf("op" to "enabled","ids" to listOf(999L),"value" to true),m,{}) }
        assertFalse(snapshot.containsKey("pendingNonce"));assertFalse(snapshot.containsKey("exportPath"))
    }
}
