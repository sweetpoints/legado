package io.legado.app.ui.book.toc.rule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.TxtTocRuleManagementRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TxtTocRuleManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun show(repo: Fake = Fake(), saved: SavedStateHandle = SavedStateHandle(), effects: (TxtTocManagementEffect) -> Unit = {}, edit: (Long) -> Unit = {}, picker: Boolean = false, back: () -> Unit = {}): TxtTocRuleManagementViewModel {
        val model = TxtTocRuleManagementViewModel(repo, saved, picker)
        compose.setContent { LegadoComposeTheme { TxtTocRuleManagementRoute(model, back, {}, edit, {}, {}, {}, effects, picker) } }
        compose.waitUntil { !model.state.value.loading }
        return model
    }
    @Test fun checkboxAndNameSelectThenShareOnlyCheckedRule() {
        val repo = Fake(); var effect: TxtTocManagementEffect? = null
        show(repo, effects = { effect = it })
        compose.onNodeWithTag("txt-toc-selection-menu").assertIsNotEnabled()
        compose.onNodeWithTag("txt-toc-name-2").performClick()
        compose.onNodeWithTag("txt-toc-select-2").assertIsOn()
        compose.onNodeWithTag("txt-toc-selection-menu").performClick()
        compose.onNodeWithTag("txt-toc-share").performClick()
        compose.waitUntil { effect != null }
        compose.runOnIdle { assertEquals(listOf(2L), repo.shared.single().map { it.id }); assertEquals(TxtTocManagementEffectKind.ShareFile, effect?.kind) }
    }
    @Test fun switchAndEditDeliverIdentityAndBatchDeleteNeedsConfirmation() {
        val repo = Fake(); var edited = 0L
        show(repo, edit = { edited = it })
        compose.onNodeWithTag("txt-toc-enabled-2").performClick()
        compose.waitUntil { repo.enabled.isNotEmpty() }
        compose.onNodeWithTag("txt-toc-edit-3").performClick()
        compose.onNodeWithTag("txt-toc-select-1").performClick()
        compose.onNodeWithTag("txt-toc-delete-selection").performClick()
        compose.runOnIdle { assertTrue(repo.deleted.isEmpty()) }
        compose.onNodeWithTag("txt-toc-delete-confirm").performClick()
        compose.waitUntil { repo.deleted.isNotEmpty() }
        compose.runOnIdle { assertEquals(3L, edited); assertEquals(listOf(2L) to false, repo.enabled.single()); assertEquals(listOf(1L), repo.deleted.single()) }
    }
    @Test fun slidingCheckboxStripSelectsRangeThenReversalRestoresOutsideRange() {
        val model = show()
        val a = compose.onNodeWithTag("txt-toc-row-1").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("txt-toc-row-3").fetchSemanticsNode().boundsInRoot
        val listTop = compose.onNodeWithTag("txt-toc-list").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("txt-toc-list").performTouchInput {
            down(Offset(24f, a.center.y - listTop))
            moveTo(Offset(24f, c.center.y - listTop), 400)
            moveTo(Offset(24f, a.center.y - listTop), 400)
            up()
        }
        compose.runOnIdle { assertEquals(setOf(1L), model.state.value.selected) }
    }
    @Test fun longPressDragPersistsDisplayedOrderWithoutChangingSelection() {
        val repo = Fake(); val model = show(repo)
        val a = compose.onNodeWithTag("txt-toc-row-1").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("txt-toc-row-3").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("txt-toc-name-1").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(0f, c.center.y - a.center.y), 400); up()
        }
        compose.waitUntil { repo.orders.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(2L, 3L, 1L), repo.orders.single()); assertTrue(model.state.value.selected.isEmpty()) }
    }
    @Test fun syntheticTouchCancelRestoresSelectionBaselineAndDoesNotClickCheckbox() {
        val repo = Fake(); val model = show(repo)
        compose.onNodeWithTag("txt-toc-select-2").performClick()
        val a = compose.onNodeWithTag("txt-toc-row-1").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("txt-toc-row-3").fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("txt-toc-list").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("txt-toc-list").performTouchInput {
            down(Offset(24f, a.center.y - top)); moveTo(Offset(24f, c.center.y - top), 400); cancel()
        }
        compose.runOnIdle { assertEquals(setOf(2L), model.state.value.selected); assertTrue(repo.orders.isEmpty()) }
    }
    @Test fun syntheticTouchCancelRestoresOrderAndDoesNotCommitOrToggle() {
        val repo = Fake(); val model = show(repo)
        val a = compose.onNodeWithTag("txt-toc-row-1").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithTag("txt-toc-row-3").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("txt-toc-name-1").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(0f, c.center.y - a.center.y), 400); cancel()
        }
        compose.runOnIdle {
            assertEquals(listOf(1L, 2L, 3L), model.state.value.rules.map { it.id })
            assertTrue(model.state.value.selected.isEmpty()); assertTrue(repo.orders.isEmpty())
            model.finishReorder()
        }
        compose.runOnIdle { assertTrue(repo.orders.isEmpty()) }
    }
    @Test fun restoredOnlineDraftImportsJsonOnceAndClearsPendingEvent() {
        val saved = SavedStateHandle(mapOf("txt.toc.management.online" to true, "txt.toc.management.input" to "[{\"name\":\"draft\"}]"))
        val effects = mutableListOf<TxtTocManagementEffect>(); val model = show(saved = saved, effects = effects::add)
        compose.onNodeWithTag("txt-toc-online-input").assertTextContains("[{\"name\":\"draft\"}]")
        compose.onNodeWithTag("txt-toc-online-confirm").performClick()
        compose.waitUntil { effects.isNotEmpty() }
        compose.runOnIdle { assertEquals(TxtTocManagementEffect(TxtTocManagementEffectKind.ImportText, "[{\"name\":\"draft\"}]"), effects.single()); assertNull(model.consumeEffect()) }
    }
    @Test fun restoredEffectIsDeliveredOnceAcrossRouteRecomposition() {
        val saved = SavedStateHandle(mapOf("txt.toc.management.effect.kind" to "Clipboard", "txt.toc.management.effect.value" to "original"))
        val effects = mutableListOf<TxtTocManagementEffect>(); val model = show(saved = saved, effects = effects::add)
        compose.waitUntil { effects.isNotEmpty() }
        compose.onNodeWithTag("txt-toc-all").performClick()
        compose.runOnIdle { assertEquals(listOf(TxtTocManagementEffect(TxtTocManagementEffectKind.Clipboard, "original")), effects); assertNull(model.consumeEffect()) }
    }
    @Test fun selectionMenuRetainsEnableDisableExportShareOrder() {
        show()
        compose.onNodeWithTag("txt-toc-name-2").performClick()
        compose.onNodeWithTag("txt-toc-selection-menu").performClick()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val labels = listOf(io.legado.app.R.string.enable_selection, io.legado.app.R.string.disable_selection,
            io.legado.app.R.string.export_selection, io.legado.app.R.string.share_selected_source)
        val positions = labels.map { compose.onNodeWithText(context.getString(it)).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (first, next) -> first < next })
    }
    @Test fun rowMenuKeepsTopBottomDeleteOrderAndDeleteCancelDoesNotWrite() {
        val repo = Fake(); show(repo)
        compose.onNodeWithTag("txt-toc-row-menu-2").performClick()
        val tags = listOf("txt-toc-top-2", "txt-toc-bottom-2", "txt-toc-delete-2")
        val positions = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (first, next) -> first < next })
        compose.onNodeWithTag("txt-toc-top-2").performClick(); compose.waitUntil { repo.edges.isNotEmpty() }
        compose.onNodeWithTag("txt-toc-row-menu-2").performClick(); compose.onNodeWithTag("txt-toc-delete-2").performClick()
        compose.onNodeWithTag("txt-toc-delete-cancel").performClick()
        compose.runOnIdle { assertEquals(listOf(2L) to true, repo.edges.single()); assertTrue(repo.deleted.isEmpty()) }
    }
    @Test fun searchClearsBatchSelectionAndMatchesExampleAndShowsThatExample() {
        val model = show(); compose.onNodeWithTag("txt-toc-select-1").performClick()
        compose.onNodeWithTag("txt-toc-search").performTextInput("EXAMPLE-2")
        compose.onNodeWithTag("txt-toc-row-2").assertExists(); compose.onNodeWithTag("txt-toc-row-1").assertDoesNotExist()
        compose.onNodeWithTag("txt-toc-example-2").assertTextEquals("example-2")
        compose.runOnIdle { assertTrue(model.state.value.selected.isEmpty()) }
    }
    @Test fun readerSelectionRemainsChosenWhenSearchHidesItAndConfirmDeliversOnce() {
        val effects = mutableListOf<TxtTocManagementEffect>(); var closes = 0
        show(effects = effects::add, picker = true, back = { closes++ })
        compose.onNodeWithTag("txt-toc-select-2").performClick(); compose.onNodeWithTag("txt-toc-select-2").assertIsSelected()
        compose.onNodeWithTag("txt-toc-selection-menu").assertDoesNotExist()
        compose.onNodeWithTag("txt-toc-search").performTextInput("name-1")
        compose.onNodeWithTag("txt-toc-select-2").assertDoesNotExist(); compose.onNodeWithTag("txt-toc-confirm").performClick()
        compose.waitUntil { closes > 0 }
        compose.runOnIdle { assertEquals(listOf(TxtTocManagementEffect(TxtTocManagementEffectKind.ReturnRegex, "regex-2" + io.legado.app.model.localBook.TextFile.spaceChars + "replace-2")), effects) }
    }
    @Test fun restoredFinishedReaderPickerClosesWithoutDuplicateResultOrWrite() {
        val repo = Fake(); val effects = mutableListOf<TxtTocManagementEffect>(); var closes = 0
        show(repo, SavedStateHandle(mapOf("txt.toc.management.picker.finished" to true)), effects::add, picker = true, back = { closes++ })
        compose.waitUntil { closes > 0 }
        compose.runOnIdle { assertTrue(effects.isEmpty()); assertTrue(repo.orders.isEmpty()); assertTrue(repo.deleted.isEmpty()) }
    }
    @Test fun toolbarKeepsEveryImportAndHelpEntryAndDispatchesHostActions() {
        val repo = Fake(); val model = TxtTocRuleManagementViewModel(repo, SavedStateHandle())
        var add = 0; var local = 0; var qr = 0; var help = 0
        compose.setContent { LegadoComposeTheme { TxtTocRuleManagementRoute(model, {}, { add++ }, {}, { local++ }, { qr++ }, { help++ }, {}) } }
        compose.waitUntil { !model.state.value.loading }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("txt-toc-add").performClick()
        fun choose(label: Int) { compose.onNodeWithTag("txt-toc-menu").performClick(); compose.onNodeWithText(context.getString(label)).performClick() }
        choose(io.legado.app.R.string.import_local); choose(io.legado.app.R.string.import_by_qr_code); choose(io.legado.app.R.string.help)
        choose(io.legado.app.R.string.import_default_rule); compose.waitUntil { repo.defaults > 0 }
        choose(io.legado.app.R.string.import_on_line); compose.onNodeWithTag("txt-toc-online-input").assertExists()
        compose.runOnIdle { assertEquals(1, add); assertEquals(1, local); assertEquals(1, qr); assertEquals(1, help); assertEquals(1, repo.defaults) }
    }
    @Test fun deletingDefaultHistoryKeepsItHiddenUntilTheNextOnlineDialog() {
        val url = io.legado.app.data.repository.DEFAULT_TXT_TOC_RULE_URL
        val repo = Fake().apply { history = listOf(url) }; show(repo)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        fun open() { compose.onNodeWithTag("txt-toc-menu").performClick(); compose.onNodeWithText(context.getString(io.legado.app.R.string.import_on_line)).performClick() }
        open(); compose.onNodeWithText(url).assertExists(); compose.onNodeWithTag("txt-toc-history-delete-$url").performClick()
        compose.waitUntil { repo.removed.isNotEmpty() }; compose.onNodeWithText(url).assertDoesNotExist()
        compose.onNodeWithText(context.getString(io.legado.app.R.string.cancel)).performClick()
        open(); compose.onNodeWithText(url).assertExists()
        compose.runOnIdle { assertEquals(listOf(url), repo.removed) }
    }
    private class Fake : TxtTocRuleManagementRepository {
        val rows = MutableStateFlow(listOf(1L, 2L, 3L).map { TxtTocRuleSnapshot(it, "name-$it", "regex-$it", "replace-$it", "example-$it", 42, true) })
        val shared = mutableListOf<List<TxtTocRuleSnapshot>>(); val orders = mutableListOf<List<Long>>()
        val enabled = mutableListOf<Pair<List<Long>, Boolean>>(); val deleted = mutableListOf<List<Long>>()
        override fun observe() = rows
        override suspend fun setEnabled(names: List<Long>, enabled: Boolean) { this.enabled += names to enabled }
        override suspend fun delete(names: List<Long>) { deleted += names }
        override suspend fun reorder(names: List<Long>) { orders += names }
        val edges = mutableListOf<Pair<List<Long>, Boolean>>()
        override suspend fun moveToEdge(ids: List<Long>, top: Boolean) { edges += ids to top }
        var defaults = 0
        override suspend fun importDefault() { defaults++ }
        var history = emptyList<String>(); val removed = mutableListOf<String>()
        override suspend fun history() = history
        override suspend fun rememberUrl(url: String) {}
        override suspend fun removeUrl(url: String) { removed += url }
        override suspend fun json(rules: List<TxtTocRuleSnapshot>) = "json"
        override suspend fun shareFile(rules: List<TxtTocRuleSnapshot>): String { shared += rules; return "/share.json" }
        override suspend fun exportSummary(url: String) = "summary"
        override suspend fun passphrase(url: String) = "phrase"
    }
}
