package io.legado.app.ui.rss.source.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.RssSourceDebugSort
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RssSourceDebugUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun state() = RssSourceDebugState(loading = false, loaded = true, sorts = listOf(RssSourceDebugSort("First", "https://first"), RssSourceDebugSort("Second", "https://second")))
    @Test fun realQueryTypingPreservesSelectionAndSearchHidesHelpAndShowsLoading() {
        var state by mutableStateOf(state()); var submitted: String? = null
        val actions = RssSourceDebugActions(query = { text, start, end -> state = state.copy(query = text, queryStart = start, queryEnd = end) }, help = { state = state.copy(help = it) }, run = { submitted = it; state = state.copy(help = false, running = true) })
        compose.setContent { LegadoComposeTheme { RssSourceDebugScreen(state, actions) } }
        compose.onNodeWithTag("rss-debug-query").performTextReplacement("search query")
        compose.onNodeWithTag("rss-debug-query").performTextInputSelection(TextRange(2, 5)); compose.runOnIdle { assertEquals(2, state.queryStart); assertEquals(5, state.queryEnd) }
        compose.onNodeWithTag("rss-debug-search").performClick(); compose.onNodeWithTag("rss-debug-help").assertDoesNotExist(); compose.onNodeWithTag("rss-debug-loading").assertExists()
        assertEquals("search query", submitted)
    }
    @Test fun helperExamplesAndLongPressedCategoryChooserDeliverExactQueriesAndSelectedCategory() {
        val queries = mutableListOf<String?>(); val sorts = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { RssSourceDebugScreen(state().copy(query = "https://content", queryStart = 0, queryEnd = 0), RssSourceDebugActions(run = { queries += it }, sort = { sorts += it })) } }
        compose.onNodeWithTag("rss-debug-example-my").performClick(); compose.onNodeWithTag("rss-debug-example-system").performClick()
        compose.onNodeWithTag("rss-debug-example-content").performClick(); compose.onNodeWithTag("rss-debug-category").performClick()
        compose.onNodeWithTag("rss-debug-category").performTouchInput { longClick() }; compose.onNodeWithTag("rss-debug-sort-1").performClick()
        assertEquals(listOf("我的", "系统", "https://content"), queries); assertEquals(listOf(0, 1), sorts)
    }
    @Test fun htmlMenusPreserveSeparateNativeListAndContentEntriesAndDarkSmallScreenScrollsHelpers() {
        val html = mutableListOf<Boolean>(); var closed = 0
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(320.dp, 420.dp)) { RssSourceDebugScreen(state(), RssSourceDebugActions(html = { html += it }, close = { closed++ })) } } }
        compose.onNodeWithTag("rss-debug-example-content").performScrollTo().assertExists()
        compose.onNodeWithTag("rss-debug-menu").performClick(); compose.onNodeWithTag("rss-debug-list-html").performClick()
        compose.onNodeWithTag("rss-debug-menu").performClick(); compose.onNodeWithTag("rss-debug-content-html").performClick()
        assertEquals(listOf(false, true), html); compose.onNodeWithTag("rss-debug-back").performClick(); assertEquals(1, closed)
    }
    @Test fun logUsesActualUriHandlerForBareWebLinkAndExcludesEmailDomain() {
        val urls = mutableListOf<String>(); val uri = object : UriHandler { override fun openUri(uri: String) { urls += uri } }
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides uri) { LegadoComposeTheme {
            RssSourceDebugScreen(state().copy(help = false, output = "example.com\nreader@example.com"), RssSourceDebugActions())
        } } }
        compose.onNodeWithTag("rss-debug-log").performTouchInput { click(androidx.compose.ui.geometry.Offset(40f, 10f)) }
        compose.runOnIdle { assertEquals(listOf("http://example.com"), urls); urls.clear() }
        compose.onNodeWithTag("rss-debug-log").performTouchInput { click(androidx.compose.ui.geometry.Offset(100f, height - 10f)) }
        compose.runOnIdle { assertTrue(urls.isEmpty()) }
    }
    @Test fun actualSelectionToolbarCopiesLogTextToClipboardWithoutReplacingDebugOutput() {
        val toolbar = CaptureToolbar(); val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val before = clipboard.primaryClip
        try {
            compose.setContent { CompositionLocalProvider(LocalTextToolbar provides toolbar) { LegadoComposeTheme {
                RssSourceDebugScreen(state().copy(help = false, output = "copyable"), RssSourceDebugActions())
            } } }
            compose.onNodeWithTag("rss-debug-log").performTouchInput { longClick(androidx.compose.ui.geometry.Offset(40f, center.y)) }
            compose.waitUntil { toolbar.copy != null }; compose.runOnIdle { toolbar.copy!!.invoke() }
            compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyable" }
            compose.onNodeWithText("copyable").assertExists()
        } finally { compose.runOnIdle { clipboard.setPrimaryClip(before ?: android.content.ClipData.newPlainText("", "")) } }
    }
    private class CaptureToolbar : TextToolbar {
        var copy: (() -> Unit)? = null; override var status = TextToolbarStatus.Hidden
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested; status = TextToolbarStatus.Shown
        }
        override fun hide() { status = TextToolbarStatus.Hidden }
    }
}
