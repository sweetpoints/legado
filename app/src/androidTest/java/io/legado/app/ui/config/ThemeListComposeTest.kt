package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeListComposeTest {
    @get:Rule val compose = createComposeRule()
    private val stores = mutableListOf<ViewModelStore>()
    @After fun cleanup() { compose.runOnIdle { stores.forEach { it.clear() } } }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = ThemeListViewModel(repo, saved).also {
        stores += ViewModelStore().apply { put("themes", it) }
    }
    @Test fun rowAppliesCapturedThemeAndShareDeleteButtonsDoNotApplyIt() {
        val repo = Fake(); lateinit var model: ThemeListViewModel; val shares = mutableListOf<String>()
        compose.runOnIdle { model = model(repo) }
        compose.setContent { LegadoComposeTheme { ThemeListRoute(model, { true }, { null }, { shares += it }, {}, {}) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("theme-list-apply-one").performClick()
        compose.waitUntil { repo.applied.size == 1 }
        compose.onNodeWithTag("theme-list-share-two").performClick()
        compose.waitUntil { shares.size == 1 }; assertEquals("two-json", shares.single())
        compose.onNodeWithTag("theme-list-delete-two").performClick()
        compose.onNodeWithTag("theme-list-delete-cancel").performClick()
        assertEquals(listOf("one-json"), repo.applied); assertTrue(repo.deleted.isEmpty())
    }
    @Test fun deleteConfirmationRestoresByIdentityDespiteReorderAndCancelKeepsBothRows() {
        val repo = Fake(); var next by mutableStateOf<ThemeListViewModel?>(null); val saved = SavedStateHandle()
        compose.runOnIdle { next = model(repo, saved) }
        compose.setContent { LegadoComposeTheme { key(next) { ThemeListRoute(next!!, { true }, { null }, {}, {}, {}) } } }
        compose.waitUntil { !next!!.state.value.loading }
        compose.onNodeWithTag("theme-list-delete-one").performClick()
        compose.runOnIdle { repo.rows = repo.rows.reversed(); next = model(repo, copy(saved)) }
        compose.waitUntil { !next!!.state.value.loading }
        compose.onNodeWithTag("theme-list-delete-confirm").assertExists()
        compose.onNodeWithTag("theme-list-delete-cancel").performClick()
        assertTrue(repo.deleted.isEmpty())
        compose.onNodeWithTag("theme-list-delete-one").performClick()
        compose.onNodeWithTag("theme-list-delete-confirm").performClick()
        compose.waitUntil { next!!.state.value.items.size == 1 }
        assertEquals(listOf("one"), repo.deleted)
        compose.onNodeWithTag("theme-list-apply-one").assertDoesNotExist()
        compose.onNodeWithTag("theme-list-apply-two").assertExists()
    }
    @Test fun clipboardNullValidAndInvalidKeepOriginalImportContract() {
        val repo = Fake(); lateinit var model: ThemeListViewModel; var clipboard: String? = null; var failures = 0; var reads = 0
        compose.runOnIdle { model = model(repo) }
        compose.setContent { LegadoComposeTheme { ThemeListRoute(model, { true }, { reads++; clipboard }, {}, { failures++ }, {}) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("theme-list-import").performClick(); compose.waitUntil { reads == 1 }
        assertTrue(repo.imported.isEmpty())
        compose.runOnIdle { clipboard = "valid" }
        compose.onNodeWithTag("theme-list-import").performClick(); compose.waitUntil { repo.imported.size == 1 && !model.state.value.loading && !model.state.value.busy }
        assertEquals(listOf("valid"), repo.imported)
        compose.runOnIdle { clipboard = "invalid"; repo.valid = false }
        compose.onNodeWithTag("theme-list-import").performClick(); compose.waitUntil { failures == 1 }
        assertEquals(listOf("valid", "invalid"), repo.imported); assertEquals(3, reads)
    }
    @Test fun realReadFailureDisplaysRetryThenLoadedRowsBecomeInteractive() {
        val repo = Fake().apply { loadFails = true }; lateinit var model: ThemeListViewModel
        compose.runOnIdle { model = model(repo) }
        compose.setContent { LegadoComposeTheme { ThemeListRoute(model, { true }, { null }, {}, {}, {}) } }
        compose.waitUntil { model.state.value.error != null }; compose.onNodeWithText("load failed").assertExists()
        compose.runOnIdle { repo.loadFails = false }
        compose.onNodeWithTag("theme-list-retry").performClick()
        compose.waitUntil { !model.state.value.loading && model.state.value.error == null }
        compose.onNodeWithTag("theme-list-apply-two").performClick(); compose.waitUntil { repo.applied.size == 1 }
        assertEquals("two-json", repo.applied.single())
    }
    @Test fun pausedSharePreparationRetriesReadButNeverRepeatsConsumedHostOperation() {
        val owner = Owner(); val repo = Fake().apply { readGate = CompletableDeferred(); noncooperative = true }; lateinit var model: ThemeListViewModel; var calls = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED; model = model(repo) }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
            ThemeListRoute(model, { true }, { null }, { assertNull(model.state.value.event); calls++; owner.registry.currentState = Lifecycle.State.CREATED }, {}, {})
        } } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("theme-list-share-one").performClick(); compose.waitUntil { repo.reads == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle(); assertEquals(0, calls)
        compose.runOnIdle { repo.readGate!!.complete(Unit); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { calls == 1 }; assertTrue(repo.reads >= 2)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle(); assertEquals(1, calls)
    }
    @Test fun pendingShareRestoresSmallReceiptAndConsumedProcessRestoreDoesNotReplayPayload() {
        val owner = Owner(); val repo = Fake().apply { rows = listOf(ThemeListItem("one", "One", "x".repeat(1200000), 0)) }
        var next by mutableStateOf<ThemeListViewModel?>(null); val saved = SavedStateHandle(); var calls = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; next = model(repo, saved) }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { key(next) {
            ThemeListRoute(next!!, { true }, { null }, { assertNull(next!!.state.value.event); assertEquals(1200000, it.length); calls++ }, {}, {})
        } } } }
        compose.waitUntil { !next!!.state.value.loading }
        compose.runOnIdle { next!!.share("one") }; compose.waitUntil { next!!.state.value.event != null }
        lateinit var restored: SavedStateHandle
        compose.runOnIdle { assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 }); restored = copy(saved); next = model(repo, restored); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { calls == 1 }
        compose.runOnIdle { next = model(repo, copy(restored)) }; compose.waitUntil { !next!!.state.value.loading }; assertEquals(1, calls)
    }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private class Fake : ThemeListRepository {
        var rows = listOf(ThemeListItem("one", "One", "one-json", 0), ThemeListItem("two", "Two", "two-json", 0))
        var noncooperative = false; var valid = true; var loadFails = false; var readGate: CompletableDeferred<Unit>? = null; var reads = 0
        val applied = mutableListOf<String>(); val deleted = mutableListOf<String>(); val imported = mutableListOf<String>(); val payloads = mutableMapOf<String, String>()
        override suspend fun list(): List<ThemeListItem> { if (loadFails) error("load failed"); return rows.toList() }
        override suspend fun delete(item: ThemeListItem): Boolean { deleted += item.key; rows = rows.filterNot { it.key == item.key }; return true }
        override suspend fun add(json: String): Boolean { imported += json; return valid }
        override suspend fun apply(item: ThemeListItem) { applied += item.json }
        override suspend fun stageShare(session: String, receipt: String, item: ThemeListItem) { payloads[receipt] = item.json }
        override suspend fun share(session: String, receipt: String): String { reads++; if (noncooperative) withContext(NonCancellable) { readGate?.await() } else readGate?.await(); return payloads.getValue(receipt) }
    }
}
