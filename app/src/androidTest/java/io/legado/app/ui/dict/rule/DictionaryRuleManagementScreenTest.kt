package io.legado.app.ui.dict.rule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.DictionaryRuleManagementRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DictionaryRuleManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun show(repo: Fake = Fake(), saved: SavedStateHandle = SavedStateHandle(), effects: (DictionaryManagementEffect) -> Unit = {}, edit: (String) -> Unit = {}): DictionaryRuleManagementViewModel {
        val model = DictionaryRuleManagementViewModel(repo, saved)
        compose.setContent { LegadoComposeTheme { DictionaryRuleManagementRoute(model, {}, {}, edit, {}, {}, {}, effects) } }
        compose.waitUntil { !model.state.value.loading }
        return model
    }
    @Test fun checkboxAndNameSelectThenShareOnlyCheckedRule() {
        val repo = Fake(); var effect: DictionaryManagementEffect? = null
        show(repo, effects = { effect = it })
        compose.onNodeWithTag("dictionary-selection-menu").assertIsNotEnabled()
        compose.onNodeWithTag("dictionary-name-b").performClick()
        compose.onNodeWithTag("dictionary-select-b").assertIsOn()
        compose.onNodeWithTag("dictionary-selection-menu").performClick()
        compose.onNodeWithTag("dictionary-share").performClick()
        compose.waitUntil { effect != null }
        compose.runOnIdle { assertEquals(listOf("b"), repo.shared.single().map { it.name }); assertEquals(DictionaryManagementEffectKind.ShareFile, effect?.kind) }
    }
    @Test fun switchAndEditDeliverRuleIdentityAndBatchDeleteIsImmediate() {
        val repo = Fake(); var edited = ""
        show(repo, edit = { edited = it })
        compose.onNodeWithTag("dictionary-enabled-b").performClick()
        compose.waitUntil { repo.enabled.isNotEmpty() }
        compose.onNodeWithTag("dictionary-edit-c").performClick()
        compose.onNodeWithTag("dictionary-select-a").performClick()
        compose.onNodeWithTag("dictionary-delete-selection").performClick()
        compose.waitUntil { repo.deleted.isNotEmpty() }
        compose.runOnIdle { assertEquals("c", edited); assertEquals(listOf("b") to false, repo.enabled.single()); assertEquals(listOf("a"), repo.deleted.single()) }
    }
    @Test fun slidingCheckboxStripSelectsRangeThenReversalRestoresOutsideRange() {
        val model = show()
        val a = compose.onNodeWithTag("dictionary-row-a").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("dictionary-row-c").fetchSemanticsNode().boundsInRoot
        val listTop = compose.onNodeWithTag("dictionary-list").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("dictionary-list").performTouchInput {
            down(Offset(24f, a.center.y - listTop))
            moveTo(Offset(24f, c.center.y - listTop), 400)
            moveTo(Offset(24f, a.center.y - listTop), 400)
            up()
        }
        compose.runOnIdle { assertEquals(setOf("a"), model.state.value.selected) }
    }
    @Test fun longPressDragPersistsDisplayedOrderWithoutChangingSelection() {
        val repo = Fake(); val model = show(repo)
        val a = compose.onNodeWithTag("dictionary-row-a").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("dictionary-row-c").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("dictionary-name-a").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(0f, c.center.y - a.center.y), 400); up()
        }
        compose.waitUntil { repo.orders.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("b", "c", "a"), repo.orders.single()); assertTrue(model.state.value.selected.isEmpty()) }
    }
    @Test fun syntheticTouchCancelRestoresSelectionBaselineAndDoesNotClickCheckbox() {
        val repo = Fake(); val model = show(repo)
        compose.onNodeWithTag("dictionary-select-b").performClick()
        val a = compose.onNodeWithTag("dictionary-row-a").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("dictionary-row-c").fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("dictionary-list").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("dictionary-list").performTouchInput {
            down(Offset(24f, a.center.y - top)); moveTo(Offset(24f, c.center.y - top), 400); cancel()
        }
        compose.runOnIdle { assertEquals(setOf("b"), model.state.value.selected); assertTrue(repo.orders.isEmpty()) }
    }
    @Test fun syntheticTouchCancelRestoresOrderAndDoesNotCommitOrToggle() {
        val repo = Fake(); val model = show(repo)
        val a = compose.onNodeWithTag("dictionary-row-a").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("dictionary-row-c").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("dictionary-name-a").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(0f, c.center.y - a.center.y), 400); cancel()
        }
        compose.runOnIdle {
            assertEquals(listOf("a", "b", "c"), model.state.value.rules.map { it.name })
            assertTrue(model.state.value.selected.isEmpty()); assertTrue(repo.orders.isEmpty())
            model.finishReorder()
        }
        compose.runOnIdle { assertTrue(repo.orders.isEmpty()) }
    }
    @Test fun restoredOnlineDraftImportsJsonOnceAndClearsPendingEvent() {
        val saved = SavedStateHandle(mapOf("dictionary.management.online" to true, "dictionary.management.input" to "[{\"name\":\"draft\"}]"))
        val effects = mutableListOf<DictionaryManagementEffect>(); val model = show(saved = saved, effects = effects::add)
        compose.onNodeWithTag("dictionary-online-input").assertTextContains("[{\"name\":\"draft\"}]")
        compose.onNodeWithTag("dictionary-online-confirm").performClick()
        compose.waitUntil { effects.isNotEmpty() }
        compose.runOnIdle { assertEquals(DictionaryManagementEffect(DictionaryManagementEffectKind.ImportText, "[{\"name\":\"draft\"}]"), effects.single()); assertNull(model.consumeEffect()) }
    }
    @Test fun restoredEffectIsDeliveredOnceAcrossRouteRecomposition() {
        val saved = SavedStateHandle(mapOf("dictionary.management.effect.kind" to "Clipboard", "dictionary.management.effect.value" to "original"))
        val effects = mutableListOf<DictionaryManagementEffect>(); val model = show(saved = saved, effects = effects::add)
        compose.waitUntil { effects.isNotEmpty() }
        compose.onNodeWithTag("dictionary-all").performClick()
        compose.runOnIdle { assertEquals(listOf(DictionaryManagementEffect(DictionaryManagementEffectKind.Clipboard, "original")), effects); assertNull(model.consumeEffect()) }
    }
    @Test fun selectionMenuRetainsEnableDisableExportShareOrder() {
        show()
        compose.onNodeWithTag("dictionary-name-b").performClick()
        compose.onNodeWithTag("dictionary-selection-menu").performClick()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val labels = listOf(io.legado.app.R.string.enable_selection, io.legado.app.R.string.disable_selection,
            io.legado.app.R.string.export_selection, io.legado.app.R.string.share_selected_source)
        val positions = labels.map { compose.onNodeWithText(context.getString(it)).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (first, next) -> first < next })
    }
    private class Fake : DictionaryRuleManagementRepository {
        val rows = MutableStateFlow(listOf("a", "b", "c").map { DictionaryRuleSnapshot(it, "url-$it", "show-$it") })
        val shared = mutableListOf<List<DictionaryRuleSnapshot>>(); val orders = mutableListOf<List<String>>()
        val enabled = mutableListOf<Pair<List<String>, Boolean>>(); val deleted = mutableListOf<List<String>>()
        override fun observe() = rows
        override suspend fun setEnabled(names: List<String>, enabled: Boolean) { this.enabled += names to enabled }
        override suspend fun delete(names: List<String>) { deleted += names }
        override suspend fun reorder(names: List<String>) { orders += names }
        override suspend fun importDefault() {}
        override suspend fun history() = emptyList<String>()
        override suspend fun rememberUrl(url: String) {}
        override suspend fun removeUrl(url: String) {}
        override suspend fun json(rules: List<DictionaryRuleSnapshot>) = "json"
        override suspend fun shareFile(rules: List<DictionaryRuleSnapshot>): String { shared += rules; return "/share.json" }
        override suspend fun exportSummary(url: String) = "summary"
        override suspend fun passphrase(url: String) = "phrase"
    }
}
