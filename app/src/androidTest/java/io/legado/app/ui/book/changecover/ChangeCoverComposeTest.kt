package io.legado.app.ui.book.changecover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ChangeCoverComposeTest {
    @get:Rule val compose = createComposeRule()
    private val stores = mutableListOf<ViewModelStore>()
    @After fun cleanup() { compose.runOnIdle { stores.forEach { it.clear() } } }
    private fun newModel(repo: Fake = Fake(), saved: SavedStateHandle = SavedStateHandle()): ChangeCoverComposeViewModel {
        val model = ChangeCoverComposeViewModel(repo, saved, "Name", "Author")
        stores += ViewModelStore().apply { put("cover", model) }; return model
    }
    @Test fun gridUsesThreeColumnsStableIdentitiesSourceMetadataAndDefaultFallbackWithoutWifiRestriction() {
        val requests = mutableMapOf<String?, CoverRequest>(); var selected: String? = null
        val target = ChangeCoverTarget("Name", "Author")
        val items = listOf(ChangeCoverItem("default", "", "Default", "use_default_cover")) + (1..5).map {
            ChangeCoverItem("book:$it", "origin:$it", "Source $it", "cover:$it", it)
        }
        compose.setContent { LegadoComposeTheme { ChangeCoverScreen(ChangeCoverState(ChangeCoverSnapshot(target, items), loading = false), {}, {}, { selected = it }, {},
            cover = { request, modifier -> requests[request.path] = request; Box(modifier.height(100.dp)) }) } }
        val first = compose.onNodeWithTag("change-cover-item-default").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("change-cover-item-book:1").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("change-cover-item-book:2").fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag("change-cover-item-book:3").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 0.1f); assertEquals(second.top, third.top, 0.1f); assertTrue(fourth.top > first.top)
        assertTrue(first.width >= 48f); assertTrue(first.height >= 48f)
        compose.runOnIdle { assertEquals(CoverRequest(null, "Name", "Author", false, ""), requests[null])
            assertEquals("origin:1", requests["cover:1"]!!.sourceOrigin); assertFalse(requests["cover:1"]!!.loadOnlyWifi) }
        compose.onNodeWithTag("change-cover-item-book:1").performClick(); assertEquals("book:1", selected)
    }
    @Test fun realStartStopButtonUpdatesProgressAndCancelsSearch() {
        lateinit var model: ChangeCoverComposeViewModel; val repo = Fake()
        compose.runOnIdle { model = newModel(repo) }
        compose.setContent { LegadoComposeTheme { ChangeCoverRoute(model, { true }, {}, {}, cover = { _, modifier -> Box(modifier.height(100.dp)) }) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("change-cover-progress").assertDoesNotExist()
        compose.onNodeWithTag("change-cover-start-stop").performClick(); compose.waitUntil { repo.active == 1 }
        compose.onNodeWithTag("change-cover-progress").assertExists()
        compose.onNodeWithTag("change-cover-start-stop").performClick(); compose.waitUntil { repo.active == 0 }
        compose.onNodeWithTag("change-cover-progress").assertDoesNotExist(); assertEquals(listOf(false), repo.calls)
    }
    @Test fun ruleReadyButtonResumesSourcesAndKeepsExistingGridInsteadOfRefreshing() {
        val repo = Fake().apply { status = ChangeCoverStatus.RuleReady }; lateinit var model: ChangeCoverComposeViewModel
        compose.runOnIdle { model = newModel(repo) }
        compose.setContent { LegadoComposeTheme { ChangeCoverRoute(model, { true }, {}, {}, cover = { _, modifier -> Box(modifier.height(100.dp)) }) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("change-cover-start-stop").performClick(); compose.waitUntil { repo.calls.isNotEmpty() }
        assertEquals(listOf(true), repo.calls); compose.onNodeWithTag("change-cover-item-default").assertExists()
    }
    @Test fun initialReadFailureShowsRetryWithoutSearchUntilOriginalRowsLoad() {
        val repo = Fake().apply { loadFails = true }; lateinit var model: ChangeCoverComposeViewModel
        compose.runOnIdle { model = newModel(repo) }
        compose.setContent { LegadoComposeTheme { ChangeCoverRoute(model, { true }, {}, {}, cover = { _, modifier -> Box(modifier.height(100.dp)) }) } }
        compose.waitUntil { model.state.value.error != null }; compose.onNodeWithTag("change-cover-start-stop").assertIsNotEnabled()
        compose.runOnIdle { repo.loadFails = false }
        compose.onNodeWithTag("change-cover-retry").performClick(); compose.waitUntil { model.state.value.snapshot != null }
        compose.onNodeWithTag("change-cover-item-default").assertExists(); assertTrue(repo.calls.isEmpty())
    }
    @Test fun selectionWaitsForDurableWriteAndResumeThenConsumesBeforeHostAndNeverReplays() {
        val owner = Owner(); val repo = Fake().apply { saveGate = CompletableDeferred() }; lateinit var model: ChangeCoverComposeViewModel
        var calls = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; model = newModel(repo) }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
            ChangeCoverRoute(model, { true }, { assertEquals("use_default_cover", it); assertNull(model.state.value.selected); calls++
                owner.registry.currentState = Lifecycle.State.CREATED }, {}, cover = { _, modifier -> Box(modifier.height(100.dp)) })
        } } }
        compose.waitUntil { !model.state.value.loading }; compose.onNodeWithTag("change-cover-item-default").performClick()
        compose.waitForIdle(); assertEquals(0, calls)
        compose.runOnIdle { repo.saveGate!!.complete(Unit) }; compose.waitUntil { model.state.value.selected != null }; assertEquals(0, calls)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { calls == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle(); assertEquals(1, calls)
    }
    @Test fun pendingStableIdRestoresAsynchronouslyAndConsumedReceiptSurvivesRecreationWithoutReplay() {
        val owner = Owner(); val repo = Fake(); val saved = SavedStateHandle(); var next by mutableStateOf<ChangeCoverComposeViewModel?>(null)
        var calls = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; next = newModel(repo, saved) }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
            key(next) { ChangeCoverRoute(next!!, { true }, { calls++; assertNull(next!!.state.value.selected) }, {}, cover = { _, modifier -> Box(modifier.height(100.dp)) }) }
        } } }
        compose.waitUntil { next!!.state.value.snapshot != null }; compose.runOnIdle { next!!.select("default") }
        compose.waitUntil { saved.get<String>("selectedId") != null }
        lateinit var restoredSaved: SavedStateHandle
        compose.runOnIdle { restoredSaved = copy(saved); repo.resolveGate = CompletableDeferred(); next = newModel(repo, restoredSaved); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle(); assertEquals(0, calls)
        compose.runOnIdle { repo.resolveGate!!.complete(Unit) }; compose.waitUntil { calls == 1 }
        compose.runOnIdle { next = newModel(repo, copy(restoredSaved)) }; compose.waitForIdle(); assertEquals(1, calls)
    }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private class Fake : ChangeCoverRepository {
        var status = ChangeCoverStatus.Idle
        var loadFails = false; var active = 0; var saved: ChangeCoverSnapshot? = null
        var saveGate: CompletableDeferred<Unit>? = null; var resolveGate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<Boolean>()
        override suspend fun initial(session: String, target: ChangeCoverTarget): ChangeCoverInitial {
            if (loadFails) error("failed")
            return ChangeCoverInitial(ChangeCoverSnapshot(target, listOf(ChangeCoverItem("default", "", "Default", "use_default_cover")), listOf("source"), status), false)
        }
        override fun search(snapshot: ChangeCoverSnapshot, resume: Boolean): Flow<ChangeCoverSnapshot> = flow {
            calls += resume; active++; try { emit(snapshot.copy(status = ChangeCoverStatus.Running)); awaitCancellation() } finally { active-- }
        }
        override suspend fun save(session: String, snapshot: ChangeCoverSnapshot) { saveGate?.await(); saved = snapshot }
        override suspend fun selected(session: String, id: String): String { resolveGate?.await(); return saved!!.covers.first { it.id == id }.coverUrl }
    }
}
