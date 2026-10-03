package io.legado.app.ui.config

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class BottomBarSkinCatalogScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(BottomBarSkinCatalogState(loaded = true, names = listOf("A", "B"), active = "A"))
    private fun show(actions: BottomBarSkinCatalogActions = BottomBarSkinCatalogActions()) {
        compose.setContent { LegadoComposeTheme { BottomBarSkinCatalogScreen(state.value, actions) } }
    }
    @Test fun defaultAndNamedSelectionUseDistinctStableTargetsAndLongPressOnlyCustomOpensMenu() {
        val activated = mutableListOf<String>(); val menus = mutableListOf<String>()
        show(BottomBarSkinCatalogActions(activate = { activated += it }, menu = { menus += it }))
        compose.onNodeWithTag("skin-catalog-item-A").assertIsSelected(); compose.onNodeWithTag("skin-catalog-default").assertIsNotSelected()
        compose.onNodeWithTag("skin-catalog-default").performClick(); compose.onNodeWithTag("skin-catalog-item-B").performClick()
        compose.onNodeWithTag("skin-catalog-default").performTouchInput { longClick() }
        compose.onNodeWithTag("skin-catalog-item-A").performTouchInput { longClick() }
        assertEquals(listOf("", "B"), activated); assertEquals(listOf("A"), menus)
    }
    @Test fun customMenuExposesAllFourActionsWithExactTargetAndCancel() {
        state.value = state.value.copy(menu = "B"); val actions = mutableListOf<String>(); var cancels = 0
        show(BottomBarSkinCatalogActions(edit = { actions += "edit:$it" }, export = { actions += "export:$it" }, share = { actions += "share:$it" }, delete = { actions += "delete:$it" }, cancelMenu = { cancels++ }))
        for (action in listOf("edit", "export", "share", "delete")) compose.onNodeWithTag("skin-catalog-menu-$action").performClick()
        compose.onNodeWithTag("skin-catalog-menu-cancel").performClick()
        assertEquals(listOf("edit:B", "export:B", "share:B", "delete:B"), actions); assertEquals(1, cancels)
    }
    @Test fun deleteConfirmationSeparatesCancelAndYesAndBusyPreventsDuplicateActions() {
        state.value = state.value.copy(delete = "A"); var confirmed = 0; var canceled = 0
        show(BottomBarSkinCatalogActions(confirmDelete = { confirmed++ }, cancelDelete = { canceled++ }))
        compose.onNodeWithTag("skin-catalog-delete-cancel").performClick(); compose.onNodeWithTag("skin-catalog-delete-confirm").performClick()
        assertEquals(1, confirmed); assertEquals(1, canceled)
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag("skin-catalog-delete-confirm").assertIsNotEnabled(); compose.onNodeWithTag("skin-catalog-delete-cancel").assertIsNotEnabled()
    }
    @Test fun importAndBackCallbacksRemainIndependentAndBusyDisablesDataChanges() {
        var imports = 0; var closes = 0; show(BottomBarSkinCatalogActions(importPicker = { imports++ }, close = { closes++ }))
        compose.onNodeWithTag("skin-catalog-import").performClick(); compose.onNodeWithTag("skin-catalog-back").performClick()
        assertEquals(1, imports); assertEquals(1, closes)
        compose.runOnIdle { state.value = state.value.copy(closeBlocked = true) }; compose.onNodeWithTag("skin-catalog-back").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag("skin-catalog-import").assertIsNotEnabled(); compose.onNodeWithTag("skin-catalog-item-A").assertIsNotEnabled()
    }
    @Test fun restoredGridScrollWaitsForLoadedRowsAndCanRevealLastTarget() {
        state.value = state.value.copy(loaded = false, names = emptyList(), scroll = 60)
        show(); compose.runOnIdle { state.value = state.value.copy(loaded = true, names = (0..75).map { "Skin-$it" }) }
        compose.onNodeWithTag("skin-catalog-item-Skin-59").assertIsDisplayed()
        compose.onNodeWithTag("skin-catalog-grid").performScrollToNode(hasTestTag("skin-catalog-item-Skin-75"))
        compose.onNodeWithTag("skin-catalog-item-Skin-75").assertIsDisplayed()
    }
    @Test fun nativeTicketWaitsForResumedAndAcknowledgesBeforeCallbackAcrossRepeatedResume() {
        class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
        val repo = object : BottomBarSkinCatalogRepository {
            override suspend fun load() = BottomBarSkinCatalog(listOf("A"), "")
            override suspend fun preview(name: String, sizePx: Int) = emptyList<Bitmap>()
            override suspend fun activate(name: String) = name
            override suspend fun delete(name: String) {}
            override suspend fun importZip(uri: String) = BottomBarSkinStaged("session", "New")
            override suspend fun edit(name: String) = BottomBarSkinStaged("session", name, name)
            override suspend fun zip(name: String) = "/cache/$name.zip"
            override suspend fun discard(session: String) {}
        }
        lateinit var owner: Owner; lateinit var model: BottomBarSkinCatalogViewModel; val delivered = mutableListOf<BottomBarSkinCatalogEffect>()
        compose.runOnIdle { owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }; model = BottomBarSkinCatalogViewModel(repo, SavedStateHandle(), 24) }
        try {
            compose.setContent { LegadoComposeTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                BottomBarSkinCatalogRoute(model, { true }, {}, { assertNull(model.state.value.effect); delivered += it }, {})
            } } }
            compose.waitUntil { model.state.value.loaded }; compose.runOnIdle { model.edit("A") }
            compose.waitUntil { model.state.value.effect != null }; assertTrue(delivered.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { delivered.size == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitForIdle(); assertEquals(1, delivered.size); assertEquals("session", delivered.single().session)
        } finally { compose.runOnIdle { model.stop() } }
    }
}
