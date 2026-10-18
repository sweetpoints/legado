package io.legado.app.ui.rss.source.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matchers.allOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.RssSourceDebugSort
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RssSourceDebugUiTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var hostView: android.view.View
    private var clearQueryFocus: () -> Unit = {}

    @Composable
    private fun CaptureHost() {
        val view = LocalView.current
        val focus = LocalFocusManager.current
        SideEffect {
            hostView = view
            clearQueryFocus = { focus.clearFocus(force = true) }
        }
    }

    private fun state() =
        RssSourceDebugState(
            loading = false,
            loaded = true,
            sorts =
                listOf(
                    RssSourceDebugSort("First", "https://first"),
                    RssSourceDebugSort("Second", "https://second"),
                ),
        )

    @Test
    fun realQueryTypingPreservesSelectionAndSearchHidesHelpAndShowsLoading() {
        var state by mutableStateOf(state())
        var submitted: String? = null
        val actions =
            RssSourceDebugActions(
                query = { text, start, end ->
                    state = state.copy(query = text, queryStart = start, queryEnd = end)
                },
                help = { state = state.copy(help = it) },
                run = {
                    submitted = it
                    state = state.copy(help = false, running = true)
                },
            )
        compose.setContent {
            LegadoComposeTheme { RssSourceDebugScreen(state, actions) }
        }
        compose.onNodeWithTag("rss-debug-query").performTextReplacement("search query")
        compose.onNodeWithTag("rss-debug-query").performTextInputSelection(TextRange(2, 5))
        compose.runOnIdle {
            assertEquals(2, state.queryStart)
            assertEquals(5, state.queryEnd)
        }
        compose.onNodeWithTag("rss-debug-search").performClick()
        compose.onNodeWithTag("rss-debug-help").assertDoesNotExist()
        compose.onNodeWithTag("rss-debug-loading").assertExists()
        assertEquals("search query", submitted)
    }

    @Test
    fun helperExamplesAndLongPressedCategoryChooserDeliverExactQueriesAndSelectedCategory() {
        val queries = mutableListOf<String?>()
        val sorts = mutableListOf<Int>()
        compose.setContent {
            LegadoComposeTheme {
                RssSourceDebugScreen(
                    state().copy(query = "https://content", queryStart = 0, queryEnd = 0),
                    RssSourceDebugActions(run = { queries += it }, sort = { sorts += it }),
                )
            }
        }
        compose.onNodeWithTag("rss-debug-example-my").performClick()
        compose.onNodeWithTag("rss-debug-example-system").performClick()
        compose.onNodeWithTag("rss-debug-example-content").performClick()
        compose.onNodeWithTag("rss-debug-category").performClick()
        compose.onNodeWithTag("rss-debug-category").performTouchInput { longClick() }
        compose.onNodeWithTag("rss-debug-sort-1").performClick()
        assertEquals(listOf("我的", "系统", "https://content"), queries)
        assertEquals(listOf(0, 1), sorts)
    }

    @Test
    fun htmlMenusPreserveSeparateNativeListAndContentEntriesAndDarkSmallScreenScrollsHelpers() {
        val html = mutableListOf<Boolean>()
        var closed = 0
        compose.setContent {
            CaptureHost()
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(320.dp, 420.dp)) {
                    RssSourceDebugScreen(
                        state(),
                        RssSourceDebugActions(html = { html += it }, close = { closed++ }),
                    )
                }
            }
        }
        settleIme(visible = false) {
            compose.runOnIdle {
                clearQueryFocus()
                ViewCompat.getWindowInsetsController(hostView)?.hide(WindowInsetsCompat.Type.ime())
            }
        }
        compose.onNodeWithTag("rss-debug-example-content").performScrollTo().assertExists()
        compose.onNodeWithTag("rss-debug-menu").performClick()
        compose.onNodeWithTag("rss-debug-list-html").performClick()
        compose.onNodeWithTag("rss-debug-menu").performClick()
        compose.onNodeWithTag("rss-debug-content-html").performClick()
        assertEquals(listOf(false, true), html)
        compose.onNodeWithTag("rss-debug-back").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun logUsesActualUriHandlerForBareWebLinkAndExcludesEmailDomain() {
        val urls = mutableListOf<String>()
        val uri =
            object : UriHandler {
                override fun openUri(uri: String) {
                    urls += uri
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uri) {
                LegadoComposeTheme {
                    RssSourceDebugScreen(
                        state().copy(help = false, output = "example.com\nreader@example.com"),
                        RssSourceDebugActions(),
                    )
                }
            }
        }
        compose.onNodeWithTag("rss-debug-log").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(40f, 10f))
        }
        compose.runOnIdle {
            assertEquals(listOf("http://example.com"), urls)
            urls.clear()
        }
        compose.onNodeWithTag("rss-debug-log").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(100f, height - 10f))
        }
        compose.runOnIdle { assertTrue(urls.isEmpty()) }
    }

    @Test
    fun actualSelectionToolbarCopiesLogTextToClipboardWithoutReplacingDebugOutput() {
        val clipboard =
            context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        val before = clipboard.primaryClip
        try {
            compose.setContent {
                CaptureHost()
                LegadoComposeTheme {
                    RssSourceDebugScreen(
                        state().copy(help = false, output = "copyable"),
                        RssSourceDebugActions(),
                    )
                }
            }
            compose.waitUntil { hostView.hasWindowFocus() }
            committedFrame(hostView.rootView)
            val log = compose.onNodeWithTag("rss-debug-log")
            val layouts = mutableListOf<TextLayoutResult>()
            log.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(layouts))
            }
            assertEquals(1, layouts.size)
            val glyph = layouts.single().getBoundingBox(2).center
            log.performTouchInput { longClick(glyph) }
            // Foundation 1.12 uses the platform context-menu provider. LocalTextToolbar is
            // only consulted by the disabled legacy branch, so click the actual Copy item.
            val copy = onView(allOf(withText(android.R.string.copy), isDisplayed()))
                .inRoot(isPlatformPopup())
                .check(matches(isCompletelyDisplayed()))
            var popup: android.view.View? = null
            copy.check { view, failure ->
                if (failure != null) throw failure
                popup = view.rootView
            }
            committedFrame(checkNotNull(popup))
            copy.perform(click())
            compose.waitUntil {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyable"
            }
            compose.onNodeWithText("copyable").assertExists()
        } finally {
            compose.runOnIdle {
                clipboard.setPrimaryClip(before ?: android.content.ClipData.newPlainText("", ""))
            }
        }
    }

    private fun committedFrame(view: android.view.View) {
        val committed = CountDownLatch(1)
        compose.runOnIdle {
            assertTrue(view.isHardwareAccelerated)
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.postInvalidateOnAnimation()
        }
        assertTrue("Frame did not commit", committed.await(1, TimeUnit.SECONDS))
    }

    private fun settleIme(visible: Boolean, action: () -> Unit) {
        val root = hostView.rootView
        val running = mutableSetOf<WindowInsetsAnimationCompat>()
        compose.runOnIdle {
            ViewCompat.setWindowInsetsAnimationCallback(root,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) running += animation
                    }
                    override fun onProgress(insets: WindowInsetsCompat,
                        runningAnimations: MutableList<WindowInsetsAnimationCompat>) = insets
                    override fun onEnd(animation: WindowInsetsAnimationCompat) { running -= animation }
                })
        }
        try {
            action()
            compose.waitUntil {
                running.isEmpty() && ViewCompat.getRootWindowInsets(root)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
            committedFrame(root)
        } finally {
            compose.runOnIdle { ViewCompat.setWindowInsetsAnimationCallback(root, null) }
        }
    }
}
