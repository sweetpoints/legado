package io.legado.app.ui.book.manage

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.model.bookshelf.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class BookshelfManagementRouteTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private class Repository : BookshelfManagementRepository {
        override fun observe(groupId: Long, query: String) = flowOf(ManagedShelfSnapshot(listOf(ManagedShelfBook("a", "Alpha", "", "", false, 0, "", 0, true)), emptyList(), groupId, null, 3, true))
        override suspend fun group(ids: List<String>, group: Long, mode: ShelfGroupMutation) {}
        override suspend fun canUpdate(ids: List<String>, enabled: Boolean) {}
        override suspend fun order(assignments: List<ShelfOrderAssignment>, resetAll: Boolean) {}
        override suspend fun openTitle(value: Boolean) {}
    }
    private class Drafts : BookshelfManagementDraftRepository {
        var value = BookshelfManagementDraft()
        override suspend fun open(session: String) = value
        override suspend fun write(session: String, draft: BookshelfManagementDraft) { if (draft.revision >= value.revision) value = draft }
        override suspend fun release(session: String) {}
    }
    private class Maintenance : BookshelfMaintenanceRepository {
        var gate: CompletableDeferred<Unit>? = null; var entered = 0; var fail = false
        override suspend fun delete(ids: List<String>, original: Boolean) = 0
        override suspend fun clearCache(ids: List<String>) = 0
        override suspend fun exportSources(): File = error("unused")
        override suspend fun books(ids: List<String>): List<Book> { entered++; gate?.await(); if (fail) error("prepare failed"); return ids.map { Book(bookUrl = it, name = "Fresh metadata") } }
        override suspend fun updateCandidates(ids: List<String>) = books(ids)
        override suspend fun createTasks(ids: List<String>, cron: String) = 0
    }
    @Test fun pausedAndCanceledPreparationWaitsForResumedAndConsumesBeforeHostOnceAcrossReattachment() {
        lateinit var owner: Owner; lateinit var model: BookshelfManagementViewModel
        val store = ViewModelStore(); val maintenance = Maintenance(); val received = mutableListOf<PreparedShelfEffect>(); var attached by mutableStateOf(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = BookshelfManagementViewModel(Repository(), Drafts(), SavedStateHandle(), maintenance = maintenance); store.put("shelf", model)
        }
        try {
            compose.setContent { if (attached) CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
                BookshelfManagementRoute(model, { true }, { receipt -> assertFalse(model.consumeEffect(receipt.receipt.id)); received += receipt }, {}, {})
            } } }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.value.loading }
            compose.runOnIdle { model.openBook("a") }
            compose.waitUntil(timeoutMillis = 10000) { model.state.value.draft?.effects?.isNotEmpty() == true && !model.state.value.busy }
            assertTrue(received.isEmpty()); assertEquals(0, maintenance.entered)
            compose.runOnIdle { maintenance.gate = CompletableDeferred(); owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { maintenance.entered == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; attached = false; maintenance.gate!!.complete(Unit) }
            compose.waitForIdle(); assertTrue(received.isEmpty())
            compose.runOnIdle { maintenance.gate = null; attached = true; owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { received.size == 1 }; assertEquals("Fresh metadata", received.single().books.single().name)
            compose.runOnIdle { attached = false }; compose.waitForIdle(); compose.runOnIdle { attached = true }; compose.waitForIdle(); assertEquals(1, received.size)
        } finally { compose.runOnIdle { attached = false; owner.registry.currentState = Lifecycle.State.DESTROYED; model.stop(); store.clear() } }
    }
    @Test fun failedFreshSnapshotPreparationRequiresRetryAndRetainsReceiptWithoutHostReplay() {
        lateinit var owner: Owner; lateinit var model: BookshelfManagementViewModel
        val store = ViewModelStore(); val maintenance = Maintenance().apply { fail = true }; val received = mutableListOf<PreparedShelfEffect>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            model = BookshelfManagementViewModel(Repository(), Drafts(), SavedStateHandle(), maintenance = maintenance); store.put("shelf", model)
        }
        try {
            compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { BookshelfManagementRoute(model, { true }, { received += it }, {}, {}) } } }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.value.loading }; compose.runOnIdle { model.openBook("a") }
            compose.waitUntil(timeoutMillis = 10000) { model.state.value.error == "prepare failed" }; assertTrue(received.isEmpty()); assertEquals(1, model.state.value.draft!!.effects.size)
            compose.runOnIdle { maintenance.fail = false; model.retry() }; compose.waitUntil(timeoutMillis = 10000) { received.size == 1 }; compose.waitForIdle(); assertEquals(1, received.size)
        } finally { compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED; model.stop(); store.clear() } }
    }
}
