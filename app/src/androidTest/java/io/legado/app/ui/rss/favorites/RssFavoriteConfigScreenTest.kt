package io.legado.app.ui.rss.favorites

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class RssFavoriteConfigScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: RssFavoriteConfigViewModel
    private class Fake(var draft: RssFavoriteConfigDraft = RssFavoriteConfigDraft("Original", "Group")) : RssFavoriteConfigRepository {
        var gate: CompletableDeferred<Unit>? = null; var writeGate: CompletableDeferred<Unit>? = null; var fail = false
        override suspend fun load(id: String): RssFavoriteConfigDraft { gate?.await(); if (fail) error("failed"); return draft }
        override suspend fun write(id: String, draft: RssFavoriteConfigDraft) { writeGate?.await(); if (fail) error("failed"); if (draft.revision >= this.draft.revision) this.draft = draft }
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private fun show(repo: Fake, update: (String?, String?) -> Unit = { _, _ -> }, delete: () -> Unit = {}, close: () -> Unit = {}, owner: Owner? = null) {
        compose.runOnIdle { model = RssFavoriteConfigViewModel(repo, SavedStateHandle(), "id") }
        compose.setContent { LegadoComposeTheme {
            if (owner == null) RssFavoriteConfigRoute(model, { true }, update, delete, close, {})
            else CompositionLocalProvider(LocalLifecycleOwner provides owner) { RssFavoriteConfigRoute(model, { true }, update, delete, close, {}) }
        } }
    }
    private fun loaded() { compose.waitUntil(5000) { model.state.value.loaded } }
    @After fun cleanup() { if (::model.isInitialized) compose.runOnIdle { model.stop() } }
    @Test fun editedTitleAndGroupReachTheHostExactlyThenClose() {
        val updates = mutableListOf<Pair<String?, String?>>(); var closes = 0
        show(Fake(), { title, group -> updates += title to group }, close = { closes++ }); loaded()
        compose.onNodeWithTag("favorite-config-title").performScrollTo().performTextReplacement(" Edited title ")
        compose.onNodeWithTag("favorite-config-group").performScrollTo().performTextReplacement(" New group ")
        compose.onNodeWithTag("favorite-config-confirm").performClick(); compose.waitUntil { updates.size == 1 && closes == 1 }
        assertEquals(" Edited title " to " New group ", updates.single()); compose.runOnIdle { assertNull(model.consume()) }
    }
    @Test fun blankFieldsUseOriginalValuesAndNullableOriginalsRemainNullable() {
        val updates = mutableListOf<Pair<String?, String?>>(); show(Fake(RssFavoriteConfigDraft(null, "Original group")), { title, group -> updates += title to group }); loaded()
        compose.onNodeWithTag("favorite-config-title").performScrollTo().performTextReplacement(" ")
        compose.onNodeWithTag("favorite-config-group").performScrollTo().performTextReplacement(" ")
        compose.onNodeWithTag("favorite-config-confirm").performClick(); compose.waitUntil { updates.isNotEmpty() }
        assertEquals(null to "Original group", updates.single())
    }
    @Test fun deleteIsASeparateHostCallbackAndCancellationNeverSaves() {
        var saves = 0; var deletes = 0; var closes = 0
        show(Fake(), { _, _ -> saves++ }, { deletes++ }, { closes++ }); loaded()
        compose.onNodeWithTag("favorite-config-delete").performClick(); compose.waitUntil { closes == 1 }
        assertEquals(1, deletes); assertEquals(0, saves)
    }
    @Test fun pendingFailureRetryAndOutsideCancellationKeepHostUnchanged() {
        val repo = Fake().apply { gate = CompletableDeferred(); fail = true }; var callbacks = 0; var closes = 0
        show(repo, { _, _ -> callbacks++ }, { callbacks++ }, { closes++ })
        compose.onNodeWithTag("favorite-config-confirm").assertIsNotEnabled(); compose.onNodeWithTag("favorite-config-title").assertIsNotEnabled()
        compose.runOnIdle { repo.gate!!.complete(Unit) }; compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("favorite-config-error").assertTextEquals("failed")
        compose.runOnIdle { repo.fail = false }; compose.onNodeWithTag("favorite-config-retry").performClick(); loaded()
        compose.onNodeWithTag("favorite-config-title").performTextReplacement("Unconfirmed")
        compose.onNodeWithTag("favorite-config-outside").performTouchInput { click(Offset(8f, 8f)) }
        compose.waitUntil { closes == 1 }; assertEquals(0, callbacks)
    }
    @Test fun queuedSaveWaitsForResumeAndDoesNotReplayAcrossLifecycleChanges() {
        val owner = Owner(); val updates = mutableListOf<Pair<String?, String?>>(); var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        show(Fake(), { title, group -> updates += title to group }, close = { closes++ }, owner = owner); loaded()
        compose.onNodeWithTag("favorite-config-confirm").performClick(); compose.waitUntil { model.state.value.effect != null }; assertTrue(updates.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { updates.size == 1 && closes == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle()
        assertEquals(1, updates.size); assertEquals(1, closes)
    }
    @Test fun busySaveDisablesEditingAndDuplicateActionsUntilDiskSnapshotIsWritten() {
        val repo = Fake().apply { writeGate = CompletableDeferred() }; var saves = 0; var deletes = 0
        show(repo, { _, _ -> saves++ }, { deletes++ }); loaded(); compose.onNodeWithTag("favorite-config-confirm").performClick()
        compose.onNodeWithTag("favorite-config-confirm").assertIsNotEnabled(); compose.onNodeWithTag("favorite-config-delete").assertIsNotEnabled()
        compose.onNodeWithTag("favorite-config-cancel").assertIsNotEnabled(); compose.onNodeWithTag("favorite-config-title").assertIsNotEnabled()
        compose.runOnIdle { repo.writeGate!!.complete(Unit) }; compose.waitUntil { saves == 1 }; assertEquals(0, deletes)
    }
    @Test fun selectionAndImeNextPreserveDraftWhileDoneDoesNotConfirm() {
        var saves = 0; show(Fake(), { _, _ -> saves++ }); loaded()
        compose.onNodeWithTag("favorite-config-title").performTextInputSelection(TextRange(1, 4))
        compose.runOnIdle { assertEquals(1, model.state.value.titleStart); assertEquals(4, model.state.value.titleEnd) }
        compose.onNodeWithTag("favorite-config-title").performImeAction(); compose.onNodeWithTag("favorite-config-group").assertIsFocused()
        compose.onNodeWithTag("favorite-config-group").performImeAction(); compose.runOnIdle { assertEquals(0, saves); assertFalse(model.state.value.finished) }
    }
}
