package io.legado.app.ui.widget.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.textlist.TextListScreen
import io.legado.app.ui.widget.dialog.textlist.TextListUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextListScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleAndDuplicateRowsRenderAndCloseButtonEmitsCallback() {
        var closes = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { LegadoComposeTheme { TextListScreen(TextListUiState.from("title", listOf("duplicate", "duplicate")), { closes++ }) } }
        compose.onNodeWithText("title").assertExists()
        compose.onAllNodesWithText("duplicate").assertCountEquals(2)
        compose.onNodeWithTag("text-list").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun longListScrollPositionSurvivesRestoration() {
        val restoration = StateRestorationTester(compose)
        val state = TextListUiState.from("title", List(200) { "Line $it" })
        restoration.setContent { LegadoComposeTheme { TextListScreen(state, {}) } }
        compose.onNodeWithTag("text-list").performScrollToIndex(199)
        compose.onNodeWithText("Line 199").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Line 199").assertExists()
    }

    @Test fun selectedTextCanBeCopied() {
        val toolbar = CaptureToolbar()
        val state = TextListUiState.from("title", listOf("copyable"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                LegadoComposeTheme { TextListScreen(state, {}) }
            }
        }
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("compose-test", "baseline")) }
        compose.onNodeWithTag("text-list-row-${state.entries.single().id}").performTouchInput { longClick(Offset(40f, center.y)) }
        compose.waitUntil { toolbar.copy != null }
        compose.runOnIdle { toolbar.copy?.invoke() }
        compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyable" }
    }

    @Test fun webLinksKeepTheOriginalAutolinkBehavior() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                LegadoComposeTheme { TextListScreen(TextListUiState.from("title", listOf("https://example.com")), {}) }
            }
        }
        compose.onNodeWithText("https://example.com").performTouchInput { click(Offset(40f, center.y)) }
        compose.runOnIdle { assertEquals(listOf("https://example.com"), opened) }
    }

    @Test fun schemeLessWebAddressUsesHttpAndEmailDomainDoesNotBecomeALink() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                LegadoComposeTheme { TextListScreen(TextListUiState.from("title", listOf("example.com", "user@example.com")), {}) }
            }
        }
        compose.onNodeWithText("example.com").performTouchInput { click(Offset(40f, center.y)) }
        compose.runOnIdle { assertEquals(listOf("http://example.com"), opened); opened.clear() }
        compose.onNodeWithText("user@example.com").performTouchInput { click(Offset(100f, center.y)) }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test fun constructorSnapshotsMutableArrayListWithoutChangingItsArgumentContract() {
        val values = arrayListOf("first", "second")
        val dialog = TextListDialog("title", values)
        values.clear()
        assertEquals("title", dialog.arguments?.getString("title"))
        assertEquals(arrayListOf("first", "second"), dialog.arguments?.getStringArrayList("values"))
    }

    private class CaptureToolbar : TextToolbar {
        var copy: (() -> Unit)? = null
        override var status = TextToolbarStatus.Hidden
        override fun hide() { status = TextToolbarStatus.Hidden }
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested
            status = TextToolbarStatus.Shown
        }
    }
}
