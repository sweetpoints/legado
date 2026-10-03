package io.legado.app.ui.browser

import android.content.Intent
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.browser.BrowserBackAction
import io.legado.app.model.browser.BrowserHistoryItem
import io.legado.app.model.browser.browserBackAction
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference

/** Actual native core integration; the outer page is interacted with through Compose semantics. */
class BrowserHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun intent() = Intent(ApplicationProvider.getApplicationContext(), WebViewActivity::class.java)
        .putExtra("url", "https://example.com/local-browser-test")
        .putExtra("title", "Fallback title")
        .putExtra("html", "<html><head><title>Local title</title></head><body><p>Native body</p></body></html>")
    @Test fun localHtmlNativeCoreLoadsAndRecreationRestoresPrivateSessionAndToolbar() {
        ActivityScenario.launch<WebViewActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) { compose.onAllNodesWithTag("browser-title").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { it.text == "Local title" } } }
            val before = AtomicReference<String>(); scenario.onActivity { activity -> before.set(activity.model.session)
                assertTrue(activity.currentWebView.settings.javaScriptEnabled)
                assertNotNull(activity.currentWebView.parent)
                assertEquals("https://example.com/local-browser-test", activity.model.state.value.page!!.baseUrl)
            }
            scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("browser-title").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { it.text == "Local title" } } }
            scenario.onActivity { activity -> assertEquals(before.get(), activity.model.session); assertNotNull(activity.currentWebView.parent) }
            compose.onNodeWithTag("browser-menu").performClick(); compose.onNodeWithTag("browser-menu-Fullscreen").performClick()
            compose.onNodeWithTag("browser-menu").assertDoesNotExist()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("browser-menu").assertExists()
        }
    }
    @Test fun nativeCustomVideoBackHidesOnceRestoresComposeToolbarAndDoesNotFinishPage() {
        ActivityScenario.launch<WebViewActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) { compose.onAllNodesWithTag("browser-confirm").fetchSemanticsNodes().isNotEmpty() }
            var hidden = 0
            scenario.onActivity { activity ->
                activity.CustomWebChromeClient().onShowCustomView(FrameLayout(activity), WebChromeClient.CustomViewCallback { hidden++ })
            }
            compose.onNodeWithTag("browser-video-core").assertIsDisplayed(); compose.onNodeWithTag("browser-menu").assertDoesNotExist()
            scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed(); assertFalse(activity.isFinishing) }
            compose.onNodeWithTag("browser-video-core").assertDoesNotExist(); compose.onNodeWithTag("browser-menu").assertExists(); assertEquals(1, hidden)
            scenario.onActivity { it.CustomWebChromeClient().onHideCustomView() }
            assertEquals(1, hidden)
        }
    }
    @Test fun nativeHistoryProjectionUsesOriginalUrlsAndTitleToSkipDuplicateEntries() {
        ActivityScenario.launch<WebViewActivity>(intent()).use { scenario ->
            compose.waitUntil(10000) { compose.onAllNodesWithTag("browser-confirm").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { activity ->
                val history = activity.currentWebView.copyBackForwardList()
                val rows = (0 until history.size).map { history.getItemAtIndex(it).let { BrowserHistoryItem(it.originalUrl, it.title) } }
                assertEquals(BrowserBackAction.Close, browserBackAction(false, false, activity.currentWebView.canGoBack(), rows, history.currentIndex))
            }
        }
    }
}
