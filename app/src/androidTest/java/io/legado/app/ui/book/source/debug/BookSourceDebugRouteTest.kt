package io.legado.app.ui.book.source.debug

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookSourceDebugRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<BookSourceDebugViewModel>(); private val repos = mutableListOf<Fake>()
    @After fun after() { compose.runOnIdle { models.forEach { it.stop(); it.viewModelScope.cancel() }; repos.forEach { it.leases.forEach { lease -> lease.done.complete(Unit) } } } }
    private fun model(repo: Fake): BookSourceDebugViewModel { lateinit var result: BookSourceDebugViewModel; compose.runOnIdle { result = BookSourceDebugViewModel(repo, SavedStateHandle(), "key"); models += result; repos += repo }; return result }
    @Test fun pausedScreenKeepsExecutionAndResumeShowsLogsAndRerunWaitsForCanceledExecutionToFinish() {
        val repo = Fake(); val model = model(repo); val owner = Owner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { BookSourceDebugRoute(model, {}, {}, { error(it) }, {}, {}) } } }
        compose.waitUntil { model.state.value.loaded }; compose.runOnIdle { model.run("query") }; compose.waitUntil { repo.leases.size == 1 }; val old = repo.leases.single()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; old.event(BookSourceDebugEvent(1, "while paused")) }
        assertEquals(0, old.closes); assertTrue(model.state.value.running)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.onNodeWithText("while paused").assertExists()
        compose.onNodeWithTag("book-debug-search").performClick(); compose.runOnIdle { assertEquals(1, old.closes); assertEquals(1, repo.leases.size); old.event(BookSourceDebugEvent(1, "late")); old.done.complete(Unit) }
        compose.waitUntil { repo.leases.size == 2 }; compose.runOnIdle { repo.leases.last().event(BookSourceDebugEvent(1, "fresh")) }
        compose.onNodeWithText("fresh").assertExists(); compose.onNodeWithText("late").assertDoesNotExist()
    }
    @Test fun backImmediatelyReleasesOwnedExecutionBeforeClosingHost() {
        val repo = Fake(); val model = model(repo); var backs = 0
        compose.setContent { LegadoComposeTheme { BookSourceDebugRoute(model, { assertEquals(1, repo.leases.single().closes); backs++ }, {}, { error(it) }, {}, {}) } }
        compose.waitUntil { model.state.value.loaded }; compose.runOnIdle { model.run("query") }; compose.waitUntil { repo.leases.size == 1 }
        compose.onNodeWithTag("book-debug-back").performClick(); compose.waitUntil { backs == 1 }; assertTrue(model.state.value.closed)
        compose.waitUntil { repo.released.size == 1 && repo.records.isEmpty() }; compose.runOnIdle { repo.leases.single().event(BookSourceDebugEvent(40,"late private html")); repo.leases.single().done.complete(Unit) }; compose.waitForIdle(); assertTrue(repo.records.isEmpty())
    }
    @Test fun missingHostDefersCloseWhilePausedAndOnlyClosesOnceAfterResume() {
        val repo = Fake(); repo.missing = true; val model = model(repo); val owner = Owner(); var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { BookSourceDebugRoute(model, { closes++ }, {}, { error(it) }, {}, {}) } } }
        compose.waitUntil { model.state.value.missing }; assertEquals(0, closes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { closes == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle(); assertEquals(1, closes)
    }
    @Test fun htmlBodyFromParserEventIsDeliveredByEachMenuWithoutStoppingActiveExecution() {
        val repo = Fake(); val model = model(repo); val bodies = mutableListOf<String?>()
        compose.setContent { LegadoComposeTheme { BookSourceDebugRoute(model, {}, { bodies += it }, { error(it) }, {}, {}) } }
        compose.waitUntil { model.state.value.loaded }; compose.runOnIdle { model.run("query") }; compose.waitUntil { repo.leases.size == 1 }
        val lease = repo.leases.single(); compose.runOnIdle { BookSourceDebugStage.entries.forEachIndexed { index,stage -> lease.event(BookSourceDebugEvent((index+1)*10,stage.name+" fixture")) } }
        BookSourceDebugStage.entries.forEach { stage -> compose.onNodeWithTag("book-debug-menu").performClick(); compose.onNodeWithTag("book-debug-html-${stage.name}").performClick() }
        assertEquals(BookSourceDebugStage.entries.map{it.name+" fixture"},bodies); assertEquals(0,lease.closes); assertTrue(model.state.value.running)
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle get() = registry }
    private class Fake : BookSourceDebugRepository {
        var missing = false; val released = mutableSetOf<String>(); val leases = mutableListOf<Lease>(); val records = mutableMapOf<String, BookSourceDebugRecord>()
        override suspend fun load(key: String) = if (missing) null else BookSourceDebugSnapshot(key, "Name", "json")
        override suspend fun sorts(source: BookSourceDebugSnapshot, refresh: Boolean) = emptyList<BookSourceDebugSort>()
        override suspend fun acquire(source: BookSourceDebugSnapshot, event: (BookSourceDebugEvent) -> Unit) = Lease(event).also { leases += it }
        override suspend fun read(session: String) = records[session]
        override suspend fun write(session: String, record: BookSourceDebugRecord) { check(session !in released); if ((records[session]?.revision ?: -1) <= record.revision) records[session] = record }
        override suspend fun release(session: String) { released += session; records.remove(session) }
    }
    private class Lease(val event: (BookSourceDebugEvent) -> Unit) : BookSourceDebugLease {
        val done = CompletableDeferred<Unit>(); var closes = 0
        override suspend fun run(query: String) = withContext(NonCancellable) { done.await() }
        override fun close() { if (closes == 0) closes++ }
        override suspend fun awaitStopped() = Unit
    }
}
