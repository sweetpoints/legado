package io.legado.app.ui.book.import.remote

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.model.remote.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class RemoteLibraryRouteTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private class Repository : RemoteLibraryRepository {
        override suspend fun connect() = RemoteLibraryConnection("connection", "root", true, null)
        override suspend fun list(connection: RemoteLibraryConnection, path: String?) = listOf(RemoteLibraryEntry("existing", "existing.txt", "existing", 1, 1, "txt", true))
        override suspend fun import(connection: RemoteLibraryConnection, entry: RemoteLibraryEntry) {}
        override suspend fun close() {}
    }
    private class Reading : RemoteLibraryReadingRepository {
        var storage = true; var gate: CompletableDeferred<Unit>? = null; var readCalls = 0
        override suspend fun storageConfigured() = storage
        override suspend fun storageHelp() = "Synthetic help"
        override suspend fun storageUri(value: String) { storage = true }
        override suspend fun prepare(entry: RemoteLibraryEntry) = RemoteLibraryReadTarget.Open("book")
        override suspend fun chooseArchive(uri: String, name: String) = RemoteLibraryReadTarget.None
        override suspend fun importArchive(uri: String, name: String): String? = null
        override suspend fun readBook(id: String): Book { readCalls++; gate?.await(); return Book(bookUrl = id, name = "Fresh read metadata") }
    }
    private class Drafts : RemoteLibraryDraftRepository {
        var value = RemoteLibraryDraft()
        override suspend fun open(session: String) = value
        override suspend fun write(session: String, draft: RemoteLibraryDraft) { if (draft.revision >= value.revision) value = draft }
        override suspend fun release(session: String) {}
    }
    @Test fun pausedReadingWaitsThenCanceledIoPreparationAndCompositionRecreationDeliverFreshBookExactlyOnce() {
        lateinit var owner: Owner; lateinit var model: RemoteLibraryViewModel
        val store = ViewModelStore(); val reading = Reading(); val received = mutableListOf<PreparedRemoteLibraryEffect>(); var attached by mutableStateOf(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = RemoteLibraryViewModel(Repository(), reading, Drafts(), SavedStateHandle()); store.put("remote", model)
        }
        try {
            compose.setContent { if (attached) CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
                RemoteLibraryRoute(model, { true }, { event -> assertFalse(model.consumeEffect(event.receipt.id)); received += event }, {})
            } } }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.value.loading }; compose.runOnIdle { model.read("existing") }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.value.busy && model.state.value.draft?.effects?.isNotEmpty() == true }; assertTrue(received.isEmpty()); assertEquals(0, reading.readCalls)
            compose.runOnIdle { reading.gate = CompletableDeferred(); owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { reading.readCalls == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; attached = false; reading.gate!!.complete(Unit) }; compose.waitForIdle(); assertTrue(received.isEmpty())
            compose.runOnIdle { reading.gate = null; attached = true; owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { received.size == 1 }; assertEquals("Fresh read metadata", received.single().book!!.name)
            compose.runOnIdle { attached = false }; compose.waitForIdle(); compose.runOnIdle { attached = true }; compose.waitForIdle(); assertEquals(1, received.size)
        } finally { compose.runOnIdle { attached = false; owner.registry.currentState = Lifecycle.State.DESTROYED; model.stop(); store.clear() } }
    }
    @Test fun storagePickerOwnsOneTicketAndCancelCloseWaitsForResumedWithoutReopeningAfterRotation() {
        lateinit var owner: Owner; lateinit var model: RemoteLibraryViewModel
        val store = ViewModelStore(); val received = mutableListOf<RemoteLibraryReceipt>(); var attached by mutableStateOf(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = RemoteLibraryViewModel(Repository(), Reading().apply { storage = false }, Drafts(), SavedStateHandle()); store.put("remote", model)
        }
        try {
            compose.setContent { if (attached) CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
                RemoteLibraryRoute(model, { true }, { event -> assertFalse(model.consumeEffect(event.receipt.id)); received += event.receipt }, {})
            } } }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.value.loading }; compose.runOnIdle { model.confirm() }
            compose.waitUntil(timeoutMillis = 10000) { model.storageTicket() != null }; val id = model.storageTicket()!!; assertTrue(received.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil(timeoutMillis = 10000) { received.size == 1 }; assertEquals(id, received.single().id)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; attached = false }; compose.waitForIdle(); compose.runOnIdle { attached = true; model.storagePicked(id, null) }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.value.busy && model.state.value.draft?.effects?.any { it.effect == RemoteLibraryEffect.Close } == true }; assertEquals(1, received.size)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil(timeoutMillis = 10000) { received.size == 2 }; assertEquals(RemoteLibraryEffect.Close, received.last().effect)
            compose.runOnIdle { attached = false }; compose.waitForIdle(); compose.runOnIdle { attached = true }; compose.waitForIdle(); assertEquals(2, received.size)
        } finally { compose.runOnIdle { attached = false; owner.registry.currentState = Lifecycle.State.DESTROYED; model.stop(); store.clear() } }
    }
}
