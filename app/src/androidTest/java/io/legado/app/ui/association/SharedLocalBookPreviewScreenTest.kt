package io.legado.app.ui.association

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class SharedLocalBookPreviewScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: SharedLocalBookPreviewViewModel
    private class Fake : SharedLocalBookPreviewRepository {
        var fail = false; var gate: CompletableDeferred<Unit>? = null
        override suspend fun project(seeds: List<SharedLocalBookPreviewSeed>): List<SharedLocalBookPreviewRow> {
            gate?.await(); if (fail) error("failed")
            return seeds.map { SharedLocalBookPreviewRow(FileSharedLocalBookPreviewRepository.id(it.uri), it.title ?: "File", "book.epub", it.directory, it.onBookshelf, "epub", "2 KB", "Today") }
        }
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private val input = listOf(SharedLocalBookPreviewSeed("file:///first.epub", "First / Author"), SharedLocalBookPreviewSeed("file:///second.epub", "Second"))
    private fun show(repo: Fake = Fake(), saved: SavedStateHandle = SavedStateHandle(), seeds: List<SharedLocalBookPreviewSeed> = input,
        owner: Owner? = null, mapping: () -> Map<String, String> = { seeds.associate { FileSharedLocalBookPreviewRepository.id(it.uri) to it.uri } },
        selection: (List<String>) -> Unit = {}, effect: (SharedLocalBookPreviewAction, List<String>) -> Unit = { _, _ -> }, close: () -> Unit = {}) {
        compose.runOnIdle { model = SharedLocalBookPreviewViewModel(repo, saved); model.batch(seeds, seeds.map { it.uri }) }
        compose.setContent { LegadoComposeTheme {
            if (owner == null) SharedLocalBookPreviewRoute(model, { true }, mapping, selection, effect, close, {})
            else CompositionLocalProvider(LocalLifecycleOwner provides owner) { SharedLocalBookPreviewRoute(model, { true }, mapping, selection, effect, close, {}) }
        } }
    }
    private fun loaded() { compose.waitUntil { model.state.value.loaded } }
    @After fun cleanup() { if (::model.isInitialized) compose.runOnIdle { model.stop() } }
    @Test fun initialSelectionRowToggleAndSelectAllUpdateCountAndNativeUriSelection() {
        var selected = emptyList<String>(); show(selection = { selected = it }); loaded()
        compose.waitUntil { selected.size == 2 }
        val id = model.state.value.rows.first().id
        compose.onNodeWithTag("shared-local-row-$id").assertIsOn(); compose.onNodeWithTag("shared-local-format-$id", true).assertTextEquals("epub")
        compose.onNodeWithTag("shared-local-row-$id").performClick().assertIsOff(); compose.waitUntil { selected == listOf(input.last().uri) }
        compose.onNodeWithTag("shared-local-select-all").performClick(); compose.waitUntil { selected.size == 2 }
        compose.onNodeWithTag("shared-local-select-all").performClick(); compose.onNodeWithTag("shared-local-confirm").assertIsNotEnabled(); compose.waitUntil { selected.isEmpty() }
    }
    @Test fun importAndDirectoryMenuEmitExactActionsAndDuplicateClicksAreGuarded() {
        val events = mutableListOf<Pair<SharedLocalBookPreviewAction, List<String>>>()
        show(effect = { action, uris -> events += action to uris; model.source(false, true) }); loaded()
        compose.onNodeWithTag("shared-local-confirm").performClick(); compose.waitUntil { events.size == 1 }
        assertEquals(SharedLocalBookPreviewAction.Import, events.single().first); assertEquals(input.map { it.uri }, events.single().second)
        compose.onNodeWithTag("shared-local-confirm").assertIsNotEnabled(); compose.onNodeWithTag("shared-local-select-all").assertIsNotEnabled(); compose.onNodeWithTag("shared-local-cancel").assertIsNotEnabled()
        compose.runOnIdle { model.source(false, false) }
        compose.onNodeWithTag("shared-local-menu").performClick(); compose.onNodeWithTag("shared-local-directory").performClick(); compose.waitUntil { events.size == 2 }
        assertEquals(SharedLocalBookPreviewAction.Directory, events.last().first)
    }
    @Test fun pausedPendingImportUsesLatestUriMapBeforeResumeAndConsumesExactlyOnce() {
        val owner = Owner(); compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        var current = input.associate { FileSharedLocalBookPreviewRepository.id(it.uri) to it.uri }
        val events = mutableListOf<List<String>>()
        show(owner = owner, mapping = { current }, effect = { _, uris -> events += uris }); loaded()
        compose.onNodeWithTag("shared-local-confirm").performClick(); assertTrue(events.isEmpty())
        compose.runOnIdle { current = current.filterValues { it == input.last().uri }; owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { events.size == 1 }; assertEquals(listOf(input.last().uri), events.single())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle(); assertEquals(1, events.size)
    }
    @Test fun missingCurrentFilesSkipImportAndNativeThrowDoesNotRepeat() {
        var events = 0; var current = emptyMap<String, String>()
        show(mapping = { current }, effect = { _, _ -> events++; error("native") }); loaded()
        compose.onNodeWithTag("shared-local-confirm").performClick(); compose.waitUntil { model.state.value.effect == null }; assertEquals(0, events)
        compose.runOnIdle { current = input.associate { FileSharedLocalBookPreviewRepository.id(it.uri) to it.uri } }
        compose.onNodeWithTag("shared-local-confirm").performClick(); compose.waitUntil { events == 1 }; assertNull(model.state.value.effect)
    }
    @Test fun existingAndDirectoryRowsAreDisabledAndAllOnlyChecksNewFiles() {
        val seeds = input + SharedLocalBookPreviewSeed("file:///existing", "Existing", true) + SharedLocalBookPreviewSeed("file:///folder", "Folder", directory = true)
        show(seeds = seeds); loaded()
        seeds.takeLast(2).forEach { compose.onNodeWithTag("shared-local-row-${FileSharedLocalBookPreviewRepository.id(it.uri)}").assertIsNotEnabled() }
        assertEquals(2, model.state.value.selected.size)
        compose.onNodeWithTag("shared-local-select-all").performClick(); assertTrue(model.state.value.selected.isEmpty())
    }
    @Test fun delayedProjectionRestoresScrollAfterRowsArriveAndFailureIsRetryable() {
        val seeds = (0..50).map { SharedLocalBookPreviewSeed("file:///$it.epub", "Title $it") }
        val saved = SavedStateHandle(mapOf("sharedPreview.batch" to FileSharedLocalBookPreviewRepository.batch(seeds), "sharedPreview.scrollIndex" to 20, "sharedPreview.scrollOffset" to 0))
        val repo = Fake().apply { gate = CompletableDeferred(); fail = true }; show(repo, saved, seeds)
        compose.onNodeWithTag("shared-local-confirm").assertIsNotEnabled()
        compose.runOnIdle { repo.gate!!.complete(Unit) }; compose.onNodeWithTag("shared-local-error").assertTextEquals("failed")
        compose.runOnIdle { repo.fail = false }; compose.onNodeWithTag("shared-local-retry").performClick(); loaded()
        val id = FileSharedLocalBookPreviewRepository.id(seeds[20].uri); compose.onNodeWithTag("shared-local-row-$id").assertIsDisplayed()
    }
    @Test fun cancellingClosesOnceWithoutCallingImportOrChangingOriginalFiles() {
        var events = 0; var closes = 0; show(effect = { _, _ -> events++ }, close = { closes++ }); loaded()
        compose.onNodeWithTag("shared-local-cancel").performClick(); compose.waitUntil { closes == 1 }; assertEquals(0, events)
        compose.runOnIdle { model.cancel() }; compose.waitForIdle(); assertEquals(1, closes)
    }
}
