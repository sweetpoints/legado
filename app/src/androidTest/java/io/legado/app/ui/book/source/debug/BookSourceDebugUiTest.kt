package io.legado.app.ui.book.source.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.BookSourceDebugSort
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class BookSourceDebugUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun state() = BookSourceDebugState(loading = false, loaded = true, sorts = listOf(BookSourceDebugSort("First", "https://first"), BookSourceDebugSort("Second", "https://second")))
    @Test fun loadedDraftWriteErrorKeepsInputAndOffersExplicitRetry() {
        var retries = 0
        compose.setContent { LegadoComposeTheme { BookSourceDebugScreen(BookSourceDebugState(loaded=true,loading=false,query="kept draft",queryStart=10,queryEnd=10,error="disk full"),BookSourceDebugActions(retry={retries++})) } }
        compose.onNodeWithTag("book-debug-query").assertTextContains("kept draft")
        compose.onNodeWithTag("book-debug-retry").performClick(); assertEquals(1,retries)
    }
    @Test fun realQueryTypingPreservesSelectionAndSearchHidesHelpAndShowsLoading() {
        var state by mutableStateOf(state()); var submitted: String? = null
        val actions = BookSourceDebugActions(query = { text, start, end -> state = state.copy(query = text, queryStart = start, queryEnd = end) }, help = { state = state.copy(help = it) }, run = { submitted = it; state = state.copy(help = false, running = true) })
        compose.setContent { LegadoComposeTheme { BookSourceDebugScreen(state, actions) } }
        compose.onNodeWithTag("book-debug-query").performTextReplacement("search query")
        compose.onNodeWithTag("book-debug-query").performTextInputSelection(TextRange(2, 5)); compose.runOnIdle { assertEquals(2, state.queryStart); assertEquals(5, state.queryEnd) }
        compose.onNodeWithTag("book-debug-search").performClick(); compose.onNodeWithTag("book-debug-help").assertDoesNotExist(); compose.onNodeWithTag("book-debug-loading").assertExists()
        assertEquals("search query", submitted)
    }
    @Test fun allStageHelpersAndLongPressedExploreChooserDeliverExactActions() {
        val queries=mutableListOf<String?>(); val sorts=mutableListOf<Int>(); val prefixes=mutableListOf<String>(); var detail=0
        compose.setContent { LegadoComposeTheme { BookSourceDebugScreen(state().copy(keyword="Custom keyword",query="https://content"),BookSourceDebugActions(run={queries+=it},sort={sorts+=it},prefix={prefixes+=it},detail={detail++})) } }
        compose.onNodeWithTag("book-debug-example-my").performClick(); compose.onNodeWithTag("book-debug-example-system").performClick(); compose.onNodeWithTag("book-debug-example-info").performScrollTo().performClick()
        compose.onNodeWithTag("book-debug-example-toc").performScrollTo().performClick(); compose.onNodeWithTag("book-debug-example-content").performScrollTo().performClick()
        compose.onNodeWithTag("book-debug-category").performScrollTo().performClick(); compose.onNodeWithTag("book-debug-category").performTouchInput { longClick() }; compose.onNodeWithTag("book-debug-sort-1").performClick()
        assertEquals(listOf("Custom keyword","系统"),queries); assertEquals(listOf("++","--"),prefixes); assertEquals(1,detail); assertEquals(listOf(0,1),sorts)
    }
    @Test fun fourHtmlEntriesQrRefreshAndInstructionsRemainAccessibleInDarkSmallViewport() {
        val html=mutableListOf<BookSourceDebugStage>(); var scans=0; var refreshes=0; var instructions=0
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) { Box(Modifier.size(320.dp,420.dp)) { BookSourceDebugScreen(state(),BookSourceDebugActions(html={html+=it},scan={scans++},refresh={refreshes++},instructions={instructions++})) } } }
        compose.onNodeWithTag("book-debug-example-content").performScrollTo().assertExists()
        BookSourceDebugStage.entries.forEach { stage -> compose.onNodeWithTag("book-debug-menu").performClick(); compose.onNodeWithTag("book-debug-html-${stage.name}").performClick() }; assertEquals(BookSourceDebugStage.entries,html)
        compose.onNodeWithTag("book-debug-scan").performClick(); compose.onNodeWithTag("book-debug-menu").performClick(); compose.onNodeWithTag("book-debug-refresh").performClick(); compose.onNodeWithTag("book-debug-menu").performClick(); compose.onNodeWithTag("book-debug-instructions").performClick()
        assertEquals(1,scans);assertEquals(1,refreshes);assertEquals(1,instructions)
    }
    @Test fun logUsesActualUriHandlerForBareWebLinkAndExcludesEmailDomain() {
        val urls = mutableListOf<String>(); val uri = object : UriHandler { override fun openUri(uri: String) { urls += uri } }
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides uri) { LegadoComposeTheme {
            BookSourceDebugScreen(state().copy(help = false, output = "example.com\nreader@example.com"), BookSourceDebugActions())
        } } }
        compose.onNodeWithTag("book-debug-log").performTouchInput { click(androidx.compose.ui.geometry.Offset(40f, 10f)) }
        compose.runOnIdle { assertEquals(listOf("http://example.com"), urls); urls.clear() }
        compose.onNodeWithTag("book-debug-log").performTouchInput { click(androidx.compose.ui.geometry.Offset(100f, height - 10f)) }
        compose.runOnIdle { assertTrue(urls.isEmpty()) }
    }
    @Test fun actualSelectionToolbarCopiesLogTextToClipboardWithoutReplacingDebugOutput() {
        val toolbar = CaptureToolbar(); val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val before = clipboard.primaryClip
        try {
            compose.setContent { CompositionLocalProvider(LocalTextToolbar provides toolbar) { LegadoComposeTheme {
                BookSourceDebugScreen(state().copy(help = false, output = "copyable"), BookSourceDebugActions())
            } } }
            compose.onNodeWithTag("book-debug-log").performTouchInput { longClick(androidx.compose.ui.geometry.Offset(40f, center.y)) }
            compose.waitUntil { toolbar.copy != null }; compose.runOnIdle { toolbar.copy!!.invoke() }
            compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyable" }
            compose.onNodeWithText("copyable").assertExists()
        } finally { compose.runOnIdle { clipboard.setPrimaryClip(before ?: android.content.ClipData.newPlainText("", "")) } }
    }
    @Test fun transparentHelpPanelShowsBackgroundAndHidesLogsAndSpinnerUntilSubmission() {
        var state by mutableStateOf(state().copy(running=true))
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) { Box(Modifier.background(Color.Red)) { BookSourceDebugScreen(state,BookSourceDebugActions(),BookSourceDebugStyle(transparent=true,toolbarForeground=Color.White)) } } }
        compose.onNodeWithTag("book-debug-log").assertDoesNotExist(); compose.onNodeWithTag("book-debug-loading").assertDoesNotExist()
        val image=compose.onNodeWithTag("book-debug-help").captureToImage().toPixelMap(); assertEquals(android.graphics.Color.RED,image[image.width-2,2].toArgb())
        compose.runOnIdle { state=state.copy(help=false,output="Running log") }; compose.onNodeWithTag("book-debug-help").assertDoesNotExist();compose.onNodeWithTag("book-debug-loading").assertIsDisplayed();compose.onNodeWithTag("book-debug-log").assertTextEquals("Running log")
    }
    @Test fun restoredQueryCursorAndOpenExploreChooserDeliverOnlyExplicitSelection() {
        var state by mutableStateOf(state().copy(query="restored query",queryStart=2,queryEnd=4));val selections=mutableListOf<Int>()
        val tester=StateRestorationTester(compose)
        val actions=BookSourceDebugActions(query={text,start,end->state=state.copy(query=text,queryStart=start,queryEnd=end)},sort={selections+=it})
        tester.setContent { LegadoComposeTheme { BookSourceDebugScreen(state,actions) } }
        compose.onNodeWithTag("book-debug-query").performTextInputSelection(TextRange(1,3));compose.onNodeWithTag("book-debug-category").performTouchInput { longClick() }
        tester.emulateSavedInstanceStateRestore();compose.onNodeWithTag("book-debug-sort-1").assertExists();assertTrue(selections.isEmpty())
        compose.onNodeWithTag("book-debug-sort-1").performClick();assertEquals(listOf(1),selections)
        compose.onNodeWithTag("book-debug-query").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(1,3)))
    }
    private class CaptureToolbar : TextToolbar {
        var copy: (() -> Unit)? = null; override var status = TextToolbarStatus.Hidden
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested; status = TextToolbarStatus.Shown
        }
        override fun hide() { status = TextToolbarStatus.Hidden }
    }
}
