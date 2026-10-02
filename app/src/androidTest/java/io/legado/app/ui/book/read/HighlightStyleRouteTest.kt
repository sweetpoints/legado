package io.legado.app.ui.book.read

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.HighlightChannel
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HighlightStyleRouteTest {
    @get:Rule val compose = createComposeRule()
    @Test fun liveApplyPrecedesShadowEditorAndConsumePreventsReplayAcrossPauseAndCompositionRecreation() {
        val owner = Owner(); lateinit var model: HighlightStyleViewModel; var current = HighlightStyle()
        var visible by mutableStateOf(true); val actions = mutableListOf<String>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; model = HighlightStyleViewModel(SavedStateHandle()); model.attach(current)
            model.toggle(HighlightChannel.Shadow, true) }
        compose.setContent { if (visible) CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
            HighlightStyleRoute(model, 20, 0, { true }, { current }, { value, _ ->
                assertTrue(model.state.value.effects.none { it.action == HighlightStyleAction.Apply }); current = value; actions += "apply"
            }, { _, _ -> error("color unused") }, { error("font unused") }, { value ->
                assertEquals(current.shadow, value); assertTrue(model.state.value.effects.isEmpty()); actions += "shadow"
                owner.registry.currentState = Lifecycle.State.CREATED
            }, { error("underline unused") })
        } } }
        compose.waitForIdle(); assertTrue(actions.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { actions.size == 2 }
        assertEquals(listOf("apply", "shadow"), actions)
        compose.runOnIdle { visible = false }; compose.waitForIdle()
        compose.runOnIdle { visible = true; owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle()
        assertEquals(2, actions.size)
    }
    @Test fun externalRefreshUpdatesComposeCheckboxWithoutWritingBackAndToggleWritesOneIndependentStyle() {
        lateinit var model: HighlightStyleViewModel; var current = HighlightStyle(); val writes = mutableListOf<HighlightStyle>()
        compose.runOnIdle { model = HighlightStyleViewModel(SavedStateHandle()); model.attach(current) }
        compose.setContent { LegadoComposeTheme {
            HighlightStyleRoute(model, 20, 0, { true }, { current }, { value, _ -> current = value; writes += value }, { _, _ -> }, {}, {}, {})
        } }
        compose.runOnIdle { current = HighlightStyle(bold = true, fill = 7); model.refresh(current) }
        compose.onNodeWithTag("highlight-style-toggle-Bold").assertIsOn(); assertTrue(writes.isEmpty())
        compose.onNodeWithTag("highlight-style-toggle-Bold").performClick(); compose.waitUntil { writes.size == 1 }
        assertEquals(HighlightStyle(fill = 7), writes.single()); compose.onNodeWithTag("highlight-style-toggle-Bold").assertIsOff()
    }
    @Test fun numberInputClampsAndDefaultOnlyChangesRequestedPropertyAfterActualConfirm() {
        lateinit var model: HighlightStyleViewModel; var current = HighlightStyle(fontSize = 100f, fill = 7)
        val writes = mutableListOf<HighlightStyle>()
        compose.runOnIdle { model = HighlightStyleViewModel(SavedStateHandle()); model.attach(current) }
        compose.setContent { LegadoComposeTheme {
            HighlightStyleRoute(model, 20, 0, { true }, { current }, { value, _ -> current = value; writes += value }, { _, _ -> }, {}, {}, {})
        } }
        compose.onNodeWithTag("highlight-style-font-size").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-number-input").performTextReplacement("999")
        compose.onNodeWithTag("highlight-style-number-input").assertTextEquals("100")
        assertTrue(writes.isEmpty()); compose.onNodeWithTag("highlight-style-number-save").performClick()
        compose.waitUntil { writes.size == 1 }; assertEquals(100f, writes.single().fontSize)
        compose.onNodeWithTag("highlight-style-font-size").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-number-default").performClick(); compose.waitUntil { writes.size == 2 }
        assertNull(writes.last().fontSize); assertEquals(7, writes.last().fill)
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
}
