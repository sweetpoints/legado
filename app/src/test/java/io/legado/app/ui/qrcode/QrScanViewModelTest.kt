package io.legado.app.ui.qrcode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class QrScanViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Repo : QrScanRepository {
        val values = mutableMapOf<String, QrScanSession>()
        var decodeBlock: suspend (String) -> String? = { it }
        var writeBlock: suspend (QrScanSession) -> Unit = {}
        var opening: suspend () -> Unit = {}
        val decoded = mutableListOf<String>()
        override suspend fun open(id: String?): QrScanSession { opening(); return if(id == null) QrScanSession(UUID.randomUUID().toString()).also { values[it.id] = it } else checkNotNull(values[id]) }
        override suspend fun write(value: QrScanSession) { writeBlock(value); val old = checkNotNull(values[value.id]); if(value.revision > old.revision) values[value.id] = value }
        override suspend fun decode(uri: String): String? { decoded += uri; return decodeBlock(uri) }
        override suspend fun release(id: String) { values.remove(id) }
    }
    private fun own(vm: QrScanViewModel) = ViewModelStore().apply { put("qr", vm) }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun fullResultIsPrivateAndProcessRestoreDeliversNullOrLargeTextOnlyOnce() = runTest(dispatcher) {
        for (text in listOf(null, "x".repeat(1200000))) {
            val repo = Repo(); val saved = SavedStateHandle(); val vm = QrScanViewModel(repo,saved); val owner = own(vm)
            try { vm.state.first { !it.loading }; vm.capture(text); vm.state.first { it.pendingResult != null }
                val restored = QrScanViewModel(repo,copy(saved)); val other = own(restored)
                try { restored.state.first { it.pendingResult != null }; val revision = restored.state.value.pendingResult!!
                    assertEquals(QrScanDelivery(text),restored.consume(revision) { true }); assertNull(restored.consume(revision) { true })
                    assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
                    val after = QrScanViewModel(repo,copy(saved)); val third = own(after)
                    try { after.state.first { !it.loading }; assertTrue(after.state.value.completed); assertNull(after.state.value.pendingResult) } finally { third.clear() }
                } finally { other.clear() }
            } finally { owner.clear() }
        }
    }
    @Test fun galleryReceiptSurvivesProcessDeathAndPickerCancellationKeepsCamera() = runTest(dispatcher) {
        val repo = Repo(); val gate = CompletableDeferred<Unit>(); repo.decodeBlock = { gate.await(); "decoded" }
        val saved = SavedStateHandle(); val vm = QrScanViewModel(repo,saved); val owner = own(vm)
        try { vm.state.first { !it.loading }; vm.gallery(null); runCurrent(); assertTrue(repo.decoded.isEmpty())
            vm.gallery("content://image"); runCurrent(); assertEquals("content://image",repo.values.values.single().gallery)
            owner.clear(); val restored = QrScanViewModel(repo,copy(saved)); val other = own(restored)
            try { runCurrent(); gate.complete(Unit); restored.state.first { it.pendingResult != null }; assertEquals("decoded",restored.consume(restored.state.value.pendingResult!!) { true }!!.text) }
            finally { other.clear() }
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun galleryDeliveredBeforeInitializationIsDecodedAfterSessionIsDurable() = runTest(dispatcher) {
        val repo = Repo(); val gate = CompletableDeferred<Unit>(); repo.opening = { gate.await() }
        val vm = QrScanViewModel(repo,SavedStateHandle()); val owner = own(vm)
        try { vm.gallery("early-uri"); runCurrent(); assertTrue(repo.decoded.isEmpty()); gate.complete(Unit)
            vm.state.first { it.pendingResult != null }; assertEquals(listOf("early-uri"),repo.decoded) }
        finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun nativeCaptureCancelsDecodeAndLateNonCooperativeResultCannotOverwrite() = runTest(dispatcher) {
        val repo = Repo(); val gate = CompletableDeferred<Unit>(); repo.decodeBlock = { withContext(NonCancellable) { gate.await() }; "late" }
        val vm = QrScanViewModel(repo,SavedStateHandle()); val owner = own(vm)
        try { vm.state.first { !it.loading }; vm.gallery("uri"); runCurrent(); vm.capture("native"); vm.state.first { it.pendingResult != null }
            gate.complete(Unit); runCurrent(); assertEquals("native",repo.values.values.single().result) }
        finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun resultIsNotPublishedBeforeWriteAndWriteFailureCanRetryWithoutRescanning() = runTest(dispatcher) {
        val repo = Repo(); val gate = CompletableDeferred<Unit>(); repo.writeBlock = { if (it.resultReady) { gate.await(); error("disk full") } }
        val vm = QrScanViewModel(repo,SavedStateHandle()); val owner = own(vm)
        try { vm.state.first { !it.loading }; vm.capture("capture"); runCurrent(); assertNull(vm.state.value.pendingResult)
            gate.complete(Unit); vm.state.first { it.error != null }; repo.writeBlock = {}; vm.retry(); vm.state.first { it.pendingResult != null }
            assertEquals("capture",repo.values.values.single().result); assertTrue(repo.decoded.isEmpty()) }
        finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun canceledOrPausedClaimRollsBackReceiptAndResumeConsumesOnce() = runTest(dispatcher) {
        val repo = Repo(); val vm = QrScanViewModel(repo,SavedStateHandle()); val owner = own(vm); val gate = CompletableDeferred<Unit>()
        try { vm.state.first { !it.loading }; vm.capture("result"); vm.state.first { it.pendingResult != null }
            repo.writeBlock = { if(it.completed) gate.await() }; val revision = vm.state.value.pendingResult!!; var resumed = true
            val claim = launch { assertNull(vm.consume(revision) { resumed }) }; runCurrent(); resumed = false; claim.cancel(); gate.complete(Unit); claim.join()
            assertFalse(repo.values.values.single().completed); assertNotNull(vm.state.value.pendingResult)
            resumed = true; assertEquals("result",vm.consume(vm.state.value.pendingResult!!) { resumed }!!.text); assertNull(vm.consume(revision) { true }) }
        finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun hugeStoredRevisionAfterRebootIsAlwaysAdvancedBeforeResultWrite() = runTest(dispatcher) {
        val repo = Repo(); val id = UUID.randomUUID().toString(); repo.values[id] = QrScanSession(id,Long.MAX_VALUE-100)
        val vm = QrScanViewModel(repo,SavedStateHandle(mapOf("session" to id))); val owner = own(vm)
        try { vm.state.first { !it.loading }; vm.capture("new"); vm.state.first { it.pendingResult != null }; assertEquals(Long.MAX_VALUE-99,repo.values[id]!!.revision) }
        finally { owner.clear() }
    }
    @Test fun realCloseReleasesOnlyOwnerAndLateDecoderCannotRecreateIt() = runTest(dispatcher) {
        val repo = Repo(); val gate = CompletableDeferred<Unit>(); repo.decodeBlock = { withContext(NonCancellable) { gate.await() }; "late" }
        val vm = QrScanViewModel(repo,SavedStateHandle()); val other = QrScanViewModel(repo,SavedStateHandle()); val owner = own(vm); val owner2 = own(other)
        try { vm.state.first { !it.loading }; other.state.first { !it.loading }; vm.gallery("uri"); runCurrent(); vm.close(); assertEquals(1,repo.values.size)
            gate.complete(Unit); runCurrent(); assertEquals(1,repo.values.size); assertNull(vm.state.value.pendingResult) }
        finally { gate.complete(Unit); owner.clear(); owner2.clear() }
    }
}
