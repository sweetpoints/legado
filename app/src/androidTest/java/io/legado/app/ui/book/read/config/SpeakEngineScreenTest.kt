package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.SpeakHttpEngine
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpeakEngineScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun rowClickAndLongPressEmitDistinctSelectionAndLoginActions() {
        val selected = mutableListOf<Long>(); val login = mutableListOf<Long>()
        compose.setContent { Content(onHttp = { selected += it }, onLogin = { login += it }) }
        compose.onNodeWithTag("speak-select:1").performClick()
        compose.onNodeWithTag("speak-select:1").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf(1L), selected); assertEquals(listOf(1L), login) }
    }
    @Test fun deleteActionStaysCenteredWithMultilineEngineName() {
        compose.setContent { Content(SpeakEngineUiState(engines = listOf(SpeakHttpEngine(1, "Long engine\nsecond line", false)))) }
        val row = compose.onNodeWithTag("speak-http:1").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("speak-delete:1").fetchSemanticsNode().boundsInRoot
        assertEquals(row.center.y, button.center.y, 1f)
        assertTrue(button.height >= 48f * context.resources.displayMetrics.density)
    }
    @Test fun footerPreservesBookGeneralAndCancelActions() {
        val applies = mutableListOf<Boolean>(); var cancels = 0
        compose.setContent { Content(onApply = { applies += it }, onCancel = { cancels++ }) }
        compose.onNodeWithText(context.getString(R.string.book)).performClick()
        compose.onNodeWithText(context.getString(R.string.general)).performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), applies); assertEquals(1, cancels) }
    }
    @Test fun onlineInputAcceptsJsonAndConfirmInvokesImport() {
        var input = ""; var imports = 0
        compose.setContent { Content(SpeakEngineUiState(online = true), onInput = { input = it }, onImport = { imports++ }) }
        compose.onNodeWithTag("speak-import-input").performTextInput("{\"name\":\"engine\"}")
        compose.onNodeWithText(context.getString(R.string.ok)).performClick()
        compose.runOnIdle { assertEquals("{\"name\":\"engine\"}", input); assertEquals(1, imports) }
    }
    @Composable private fun Content(state: SpeakEngineUiState = SpeakEngineUiState(engines = listOf(SpeakHttpEngine(1, "HTTP", true))),
        onHttp: (Long) -> Unit = {}, onLogin: (Long) -> Unit = {}, onApply: (Boolean) -> Unit = {},
        onCancel: () -> Unit = {}, onInput: (String) -> Unit = {}, onImport: () -> Unit = {}) {
        LegadoComposeTheme { SpeakEngineScreen(state, {}, onHttp, onLogin, {}, {}, {}, {}, {}, {}, {}, onInput, {}, onImport,
            {}, onApply, onCancel, {}, {}, {}, Modifier.height(500.dp)) }
    }
}
