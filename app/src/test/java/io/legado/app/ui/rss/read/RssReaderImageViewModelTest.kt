package io.legado.app.ui.rss.read

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssReaderImageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssReaderImageViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Images : RssReaderImageRepository {
        var directory: String? = null
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        val names = mutableListOf<String>()
        override suspend fun directory() = directory
        override suspend fun directory(value: String?) { directory = value }
        override suspend fun save(image: String, directory: String) { error("Expected fixed filename") }
        override suspend fun save(image: String, directory: String, fileName: String) {
            names += fileName
            withContext(NonCancellable) { gate?.await() }
            if (failure) error("Copy failure")
        }
    }
    private class Sessions : RssReaderImageSessionRepository {
        var value: RssReaderImageSession? = null
        var readGate: CompletableDeferred<Unit>? = null
        var failure = false
        var failComplete = false
        override suspend fun read(session: String): RssReaderImageSession? { withContext(NonCancellable) { readGate?.await() }; return value }
        override suspend fun write(session: String, value: RssReaderImageSession) {
            if (failure || (failComplete && value.phase == RssReaderImagePhase.Complete)) error("Disk failure")
            this.value = value
        }
        override suspend fun release(session: String) {}
    }
    private fun model(images: Images = Images(), sessions: Sessions = Sessions(), saved: SavedStateHandle = SavedStateHandle()) =
        RssReaderImageViewModel(images, sessions, saved) { "fixed.jpg" }.also { models += it }
    private fun clone(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.stop() }; gates.forEach { it.complete(Unit) }; runCurrent() }
    }
    @Test fun hugeImageRestoresThroughSmallTicketAndPickerIsDeliveredExactlyOnce() = test {
        val saved = SavedStateHandle(); val sessions = Sessions(); val first = model(sessions = sessions, saved = saved)
        first.bind("owner"); runCurrent(); first.save("x".repeat(2000000), "owner"); runCurrent()
        val nonce = first.state.value.pending!!.nonce
        assertTrue(first.pickerDelivered(nonce)); assertFalse(first.pickerDelivered(nonce)); first.stop()
        val restored = model(sessions = sessions, saved = clone(saved)); restored.bind("owner"); runCurrent()
        assertTrue(restored.state.value.loaded); assertNull(restored.state.value.pending)
        assertEquals(2000000, sessions.value!!.image!!.length)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
    }
    @Test fun resultBeforeRestorationCompletesIsRetainedAndCopiedOnce() = test {
        val saved = SavedStateHandle(); val sessions = Sessions(); val first = model(sessions = sessions, saved = saved)
        first.bind("owner"); runCurrent(); first.save("image", "owner"); runCurrent()
        val nonce = first.state.value.pending!!.nonce; first.pickerDelivered(nonce); first.stop()
        sessions.readGate = gate(); val images = Images(); val restored = model(images, sessions, clone(saved))
        restored.bind("owner"); runCurrent(); restored.picked(nonce, "target"); sessions.readGate!!.complete(Unit); runCurrent()
        assertEquals(listOf("fixed.jpg"), images.names); assertEquals(RssReaderImageEffectKind.Saved, restored.state.value.pending!!.kind)
        restored.picked(nonce, "another"); runCurrent(); assertEquals(1, images.names.size)
    }
    @Test fun cancelledAndForeignPickerResultsNeverCopy() = test {
        val images = Images(); val model = model(images); model.bind("owner"); runCurrent(); model.save("image", "owner"); runCurrent()
        val nonce = model.state.value.pending!!.nonce; model.pickerDelivered(nonce)
        model.picked("foreign", "wrong"); model.picked(nonce, null); model.picked(nonce, "late"); runCurrent()
        assertTrue(images.names.isEmpty()); assertNull(model.state.value.pending)
    }
    @Test fun oldOwnerPickerIsDiscardedWithoutWritingNewReader() = test {
        val saved = SavedStateHandle(); val sessions = Sessions(); val first = model(sessions = sessions, saved = saved)
        first.bind("old"); runCurrent(); first.save("image", "old"); runCurrent()
        val nonce = first.state.value.pending!!.nonce; first.pickerDelivered(nonce); first.stop()
        val images = Images(); val current = model(images, sessions, clone(saved)); current.bind("new"); runCurrent(); current.picked(nonce, "wrong"); runCurrent()
        assertTrue(images.names.isEmpty()); assertNull(current.state.value.pending)
    }
    @Test fun failedCopyClearsInvalidDirectoryAndRetryUsesSameTargetFilename() = test {
        val images = Images().apply { directory = "target"; failure = true }; val sessions = Sessions(); val model = model(images, sessions)
        model.bind("owner"); runCurrent(); model.save("image", "owner"); runCurrent()
        assertNotNull(model.state.value.error); assertNull(images.directory); assertEquals("fixed.jpg", sessions.value!!.fileName)
        images.failure = false; model.retry(); runCurrent()
        assertEquals(listOf("fixed.jpg", "fixed.jpg"), images.names)
        val nonce = model.state.value.pending!!.nonce; assertTrue(model.delivered(nonce)); assertFalse(model.delivered(nonce))
    }
    @Test fun completionCheckpointFailureRetriesCheckpointWithoutCopyingAgain() = test {
        val images = Images().apply { directory = "target" }; val sessions = Sessions().apply { failComplete = true }; val model = model(images, sessions)
        model.bind("owner"); runCurrent(); model.save("image", "owner"); runCurrent()
        assertNotNull(model.state.value.error); sessions.failComplete = false; model.retry(); runCurrent()
        assertEquals(1, images.names.size); assertEquals(RssReaderImagePhase.Complete, sessions.value!!.phase)
    }
    @Test fun processRestorationOfSavingOperationRetainsFixedFilename() = test {
        val sessions = Sessions().apply { value = RssReaderImageSession("owner", "image", 10, "target", "original.jpg", RssReaderImagePhase.Save) }
        val images = Images(); val model = model(images, sessions, SavedStateHandle(mapOf("rssReaderImage.ticket" to java.util.UUID.randomUUID().toString())))
        model.bind("owner"); runCurrent(); assertEquals(listOf("original.jpg"), images.names)
        assertEquals(RssReaderImageEffectKind.Saved, model.state.value.pending!!.kind)
    }
    @Test fun stoppedNonCooperativeCopyCannotPublishSuccess() = test {
        val images = Images().apply { directory = "target"; gate = gate() }; val sessions = Sessions(); val model = model(images, sessions)
        model.bind("owner"); runCurrent(); model.save("image", "owner"); runCurrent(); model.stop(); images.gate!!.complete(Unit); runCurrent()
        assertNull(model.state.value.pending); assertEquals(RssReaderImagePhase.Save, sessions.value!!.phase)
    }
    @Test fun initialCheckpointFailureKeepsImageForExplicitRetry() = test {
        val sessions = Sessions(); val model = model(sessions = sessions); model.bind("owner"); runCurrent(); sessions.failure = true
        model.save("exact image", "owner"); runCurrent(); assertNotNull(model.state.value.error)
        sessions.failure = false; model.retry(); runCurrent(); assertEquals("exact image", sessions.value!!.image)
        assertEquals(RssReaderImageEffectKind.Picker, model.state.value.pending!!.kind)
    }
    @Test fun choosingDirectoryWithoutImageUpdatesPreferenceWithoutSavedImageEffect() = test {
        val images = Images(); val model = model(images); model.bind("owner"); runCurrent(); model.chooseDirectory("owner"); runCurrent()
        val nonce = model.state.value.pending!!.nonce; model.pickerDelivered(nonce); model.picked(nonce, "directory"); runCurrent()
        assertEquals("directory", images.directory); assertTrue(images.names.isEmpty()); assertNull(model.state.value.pending)
    }
}
