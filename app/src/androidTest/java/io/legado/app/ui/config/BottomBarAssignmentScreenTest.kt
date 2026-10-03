package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class BottomBarAssignmentScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(BottomBarAssignmentState(loaded = true, images = listOf("a.png", "b.png"),
        slots = AppBottomBarAssignmentRepository.slots.map { BottomBarAssignmentSlot(it, if (it == "bookshelf") "a.png" else null) }))
    private fun show(actions: BottomBarAssignmentActions = BottomBarAssignmentActions()) {
        compose.setContent { LegadoComposeTheme { BottomBarAssignmentScreen(state.value, false, actions) } }
    }
    @Test fun allFourSlotsKeepNormalDisabledUntilSelectedAndExposeSeparateCallbacks() {
        val picks = mutableListOf<Pair<String, Boolean>>(); show(BottomBarAssignmentActions(palette = { id, normal -> picks += id to normal }))
        for (slot in AppBottomBarAssignmentRepository.slots) compose.onNodeWithTag("bar-assignment-slot-$slot").assertExists()
        compose.onNodeWithTag("bar-assignment-home-normal").assertIsNotEnabled()
        compose.onNodeWithTag("bar-assignment-bookshelf-normal").performClick()
        compose.onNodeWithTag("bar-assignment-home-selected").performClick()
        assertEquals(listOf("bookshelf" to true, "home" to false), picks)
    }
    @Test fun paletteClearVisibilityAndExactFilenameChoicePreserveIndependentCancel() {
        val picked = mutableListOf<String?>(); var canceled = 0
        state.value = state.value.copy(palette = "bookshelf")
        show(BottomBarAssignmentActions(choose = { picked += it }, cancelPalette = { canceled++ }))
        compose.onNodeWithTag("bar-assignment-clear").performClick()
        compose.onNodeWithTag("bar-assignment-image-b.png").performClick()
        assertEquals(listOf(null, "b.png"), picked)
        compose.runOnIdle { state.value = state.value.copy(palette = "home") }
        compose.onNodeWithTag("bar-assignment-clear").assertDoesNotExist()
        assertEquals(0, canceled)
    }
    @Test fun nameSelectionAndSaveBackCallbacksAreSeparateAndBusyDisablesMutation() {
        var saves = 0; var closes = 0
        show(BottomBarAssignmentActions(name = { text, start, end -> state.value = state.value.copy(name = text, start = start, end = end) }, save = { saves++ }, close = { closes++ }))
        compose.onNodeWithTag("bar-assignment-name").performTextReplacement(" New name ")
        compose.onNodeWithTag("bar-assignment-name").performTextInputSelection(TextRange(7, 2))
        compose.onNodeWithTag("bar-assignment-save").performClick(); compose.onNodeWithTag("bar-assignment-back").performClick()
        assertEquals(" New name ", state.value.name); assertEquals(7, state.value.start); assertEquals(2, state.value.end)
        assertEquals(1, saves); assertEquals(1, closes)
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag("bar-assignment-save").assertIsNotEnabled(); compose.onNodeWithTag("bar-assignment-back").assertIsNotEnabled()
        compose.onNodeWithTag("bar-assignment-name").assertIsNotEnabled()
    }
    @Test fun needSelectedErrorKeepsAllSlotsAvailableForCorrection() {
        state.value = state.value.copy(issue = BottomBarAssignmentIssue.NeedSelected, slots = AppBottomBarAssignmentRepository.slots.map { BottomBarAssignmentSlot(it) })
        var opened = ""; show(BottomBarAssignmentActions(palette = { slot, _ -> opened = slot }))
        compose.onNodeWithTag("bar-assignment-error").assertExists(); compose.onNodeWithTag("bar-assignment-settings-selected").performClick()
        assertEquals("settings", opened); compose.onNodeWithTag("bar-assignment-save").assertIsEnabled()
    }
    @Test fun pendingCompletionWaitsForResumedAndIsConsumedBeforeHostClose() {
        class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
        val repository = object : BottomBarAssignmentRepository {
            override suspend fun load(session: String, sizePx: Int) = BottomBarAssignmentImages(emptyList(), emptyList())
            override suspend fun preview(session: String, image: String, sizePx: Int): android.graphics.Bitmap? = null
            override suspend fun save(session: String, name: String, editName: String?, slots: List<BottomBarAssignmentSlot>) = name
            override suspend fun discard(session: String) {}
        }
        lateinit var model: BottomBarAssignmentViewModel; lateinit var owner: Owner; var closes = 0; var notices = 0
        compose.runOnIdle {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = BottomBarAssignmentViewModel(repository, SavedStateHandle(), "session", null, "", 56)
        }
        try {
            compose.setContent { LegadoComposeTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                BottomBarAssignmentRoute(model, { true }, { assertNull(model.state.value.pendingClose); closes++ }, { notices++ })
            } } }
            compose.waitUntil { model.state.value.pendingClose == BottomBarAssignmentIssue.NoImages }; assertEquals(0, closes)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { closes == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitForIdle(); assertEquals(1, closes); assertEquals(1, notices)
        } finally { compose.runOnIdle { model.stop() } }
    }

}
