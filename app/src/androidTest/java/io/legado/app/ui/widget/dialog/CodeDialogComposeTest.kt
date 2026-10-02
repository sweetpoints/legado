package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CodeDialogComposeTest {
    @get:Rule val compose = createComposeRule()
    private class Store : CodeDialogRepository {
        val disk = mutableMapOf<String, CodeDialogDraft>()
        override suspend fun read(id: String) = disk[id]
        override suspend fun write(id: String, draft: CodeDialogDraft) { disk[id] = draft }
    }
    @Test fun originalAndReadOnlyPreviewToggleWithoutOverwritingEditedDraft() {
        lateinit var model: CodeDialogViewModel
        compose.runOnIdle { model = CodeDialogViewModel(Store(), SavedStateHandle(), "original", "derived", true, true) }
        try {
            compose.setContent { LegadoComposeTheme { CodeDialogRoute(model, true, { true }, {}, {}, Modifier.height(320.dp)) } }
            compose.waitUntil { model.state.value.loaded }
            compose.onNodeWithTag("code-body").performTextReplacement("edited")
            compose.onNodeWithTag("code-preview-toggle").performClick().assertIsOn()
            compose.onNodeWithTag("code-body").assertTextEquals("derived")
            assertFalse(compose.onNodeWithTag("code-body").fetchSemanticsNode().config.contains(SemanticsActions.SetText))
            compose.onNodeWithTag("code-body").performTextInputSelection(TextRange(0, 7))
            compose.onNodeWithTag("code-body").performSemanticsAction(SemanticsActions.CopyText) { it() }
            compose.runOnIdle {
                val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.content.ClipboardManager::class.java)
                assertEquals("derived", clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            compose.runOnIdle { assertEquals("edited", model.state.value.original) }
            compose.onNodeWithTag("code-preview-toggle").performClick().assertIsOff()
            compose.onNodeWithTag("code-body").assertTextEquals("edited")
        } finally { compose.runOnIdle { model.stop() } }
    }
    @Test fun searchUsesPreviousNextDescriptionsAndKeepsTypedCursorAfterEdits() {
        lateinit var model: CodeDialogViewModel
        compose.runOnIdle { model = CodeDialogViewModel(Store(), SavedStateHandle(), "abc ABC abc", null, true, false) }
        try {
            compose.setContent { LegadoComposeTheme { CodeDialogRoute(model, false, { true }, {}, {}, Modifier.height(320.dp)) } }
            compose.waitUntil { model.state.value.loaded }
            compose.onNodeWithTag("code-search-toggle").performClick()
            compose.onNodeWithTag("code-query").performTextReplacement("abc")
            compose.waitUntil { model.state.value.matches.size == 3 }
            compose.onNodeWithTag("code-previous").assertContentDescriptionEquals(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.help_search_prev)).performClick()
            compose.runOnIdle { assertEquals(8, model.state.value.selectionStart) }
            compose.onNodeWithTag("code-next").assertContentDescriptionEquals(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.help_search_next)).performClick()
            compose.runOnIdle { assertEquals(0, model.state.value.selectionStart) }
            compose.onNodeWithTag("code-body").performTextInputSelection(TextRange(11))
            compose.onNodeWithTag("code-body").performTextInput("x")
            compose.waitUntil { model.state.value.matches.size == 3 }
            compose.runOnIdle { assertEquals(12, model.state.value.selectionStart); assertEquals("abc ABC abcx", model.state.value.original) }
        } finally { compose.runOnIdle { model.stop() } }
    }
    @Test fun positionBarScrollsActualLongTextWithoutMovingSelectionAndRestoresViewport() {
        lateinit var model: CodeDialogViewModel
        val restoration = StateRestorationTester(compose)
        compose.runOnIdle { model = CodeDialogViewModel(Store(), SavedStateHandle(), (0..199).joinToString("\n") { "line $it" }, null, true, false) }
        try {
            restoration.setContent { LegadoComposeTheme { CodeDialogRoute(model, false, { true }, {}, {}, Modifier.height(320.dp)) } }
            compose.waitUntil { model.state.value.loaded }
            compose.onNodeWithTag("code-position").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            compose.waitForIdle(); compose.runOnIdle { assertEquals(0, model.state.value.selectionStart) }
            val ranges = compose.onNodeWithTag("code-scroll").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(ranges.value() > 0); assertEquals(ranges.maxValue(), ranges.value(), 1f)
            restoration.emulateSavedInstanceStateRestore()
            val restored = compose.onNodeWithTag("code-scroll").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(restored.value() > 0); assertEquals(restored.maxValue(), restored.value(), 1f)
        } finally { compose.runOnIdle { model.stop() } }
    }
    @Test fun sourceEditorSavedEffectWaitsForResumeAndFinishedRestoreClosesWithoutSave() {
        val owner = Owner(); val saved = SavedStateHandle(); val store = Store()
        lateinit var model: CodeDialogViewModel; var saves = 0; var closes = 0
        var active by mutableStateOf<CodeDialogViewModel?>(null)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; model = CodeDialogViewModel(store, saved, "original", "derived", true, true); active = model }
        try {
            compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { active?.let { current -> LegadoComposeTheme {
                CodeDialogRoute(current, true, { true }, { assertTrue(current.state.value.effects.none { pending -> pending.id == it.id }); saves++ }, { closes++ }, Modifier.height(320.dp))
            } } } }
            compose.waitUntil { model.state.value.loaded }
            compose.runOnIdle { model.action(CodeDialogAction.Editor); model.consume(model.state.value.effects.single().id); model.editorResult("edited", 3) }
            compose.waitForIdle(); assertEquals(0, saves)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { saves == 1 }
            compose.runOnIdle { assertFalse(model.state.value.finished); model.close() }; compose.waitUntil { closes == 1 }
            compose.runOnIdle {
                active = null; model.stop(); model = CodeDialogViewModel(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), "", null, true, true); active = model
            }
            compose.waitUntil { closes == 2 }; assertEquals(1, saves)
        } finally { compose.runOnIdle { model.stop() } }
    }
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry
    }
}
