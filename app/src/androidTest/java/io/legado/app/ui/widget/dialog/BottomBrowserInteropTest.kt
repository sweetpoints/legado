package io.legado.app.ui.widget.dialog

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.webView.PooledWebView
import io.legado.app.ui.about.AboutActivity
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomBrowserInteropTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<AboutActivity>
    private val source =
        BookSource(
            bookSourceUrl = "https://example.invalid/compose-browser/${UUID.randomUUID()}",
            bookSourceName = "Compose browser fixture",
            enabled = false,
            enabledExplore = false,
        )

    @Before
    fun setup() {
        appDb.bookSourceDao.insert(source)
        scenario = ActivityScenario.launch(AboutActivity::class.java)
    }

    @After
    fun cleanup() {
        try {
            scenario.close()
        } finally {
            appDb.bookSourceDao.delete(source.bookSourceUrl)
        }
    }

    private fun newBrowser() =
        BottomWebViewDialog(
            source.bookSourceUrl,
            0,
            "${source.bookSourceUrl}/page",
            "<html><head><title>Fixture</title></head><body>Page</body></html>",
            config = """{"heightPercentage":0.6}""",
        )

    private fun lease(browser: BottomWebViewDialog): PooledWebView? =
        BottomWebViewDialog::class
            .java
            .getDeclaredField("pooledWebView")
            .apply { isAccessible = true }
            .get(browser) as PooledWebView?

    private fun show(): BottomWebViewDialog {
        lateinit var browser: BottomWebViewDialog
        scenario.onActivity {
            browser = newBrowser()
            browser.show(it.supportFragmentManager, "compose-browser")
        }
        compose.onNodeWithTag("bottom-browser").assertExists()
        return browser
    }

    @Test
    fun composePageOwnsOneRealWebViewAndDismissReleasesItsLease() {
        val browser = show()
        lateinit var pooled: PooledWebView
        scenario.onActivity {
            assertTrue(browser.requireView() is ComposeView)
            val container = browser.requireView().findViewById<ViewGroup>(R.id.web_view_container)
            assertEquals(1, container.childCount)
            assertTrue(container.getChildAt(0) is WebView)
            pooled = requireNotNull(lease(browser))
            assertSame(pooled.realWebView, container.getChildAt(0))
            assertTrue(pooled.isInUse)
            browser.dismissNow()
            assertNull(lease(browser))
            assertFalse(pooled.isInUse)
            assertNull(pooled.realWebView.parent)
        }
        compose.onNodeWithTag("bottom-browser").assertDoesNotExist()
    }

    @Test
    fun fullscreenMountsVideoAndBackRestoresTheSamePageWithoutClosing() {
        val browser = show()
        lateinit var video: View
        lateinit var pooled: PooledWebView
        var hidden = 0
        scenario.onActivity { activity ->
            pooled = requireNotNull(lease(browser))
            val chrome = browser.CustomWebChromeClient()
            video = View(activity)
            chrome.onShowCustomView(
                video,
                object : WebChromeClient.CustomViewCallback {
                    override fun onCustomViewHidden() {
                        hidden++
                        chrome.onHideCustomView()
                    }
                },
            )
        }
        compose.waitForIdle()
        scenario.onActivity {
            val container = browser.requireView().findViewById<ViewGroup>(R.id.custom_web_view)
            assertSame(video, container.getChildAt(0))
            assertEquals(
                View.INVISIBLE,
                browser.requireView().findViewById<View>(R.id.web_view_container).visibility,
            )
            (browser.requireDialog() as BottomSheetDialog).onBackPressedDispatcher.onBackPressed()
            assertEquals(1, hidden)
            assertTrue(browser.isAdded)
            assertSame(pooled, lease(browser))
        }
        compose.waitForIdle()
        scenario.onActivity {
            assertNull(browser.requireView().findViewById<View>(R.id.custom_web_view))
            assertEquals(
                View.VISIBLE,
                browser.requireView().findViewById<View>(R.id.web_view_container).visibility,
            )
            assertNull(video.parent)
        }
    }

    @Test
    fun recreationReleasesTheOldLeaseAndRestoresOneComposeBrowserWithItsArguments() {
        val old = show()
        lateinit var oldLease: PooledWebView
        scenario.onActivity { oldLease = requireNotNull(lease(old)) }
        scenario.recreate()
        compose.onNodeWithTag("bottom-browser").assertExists()
        scenario.onActivity { activity ->
            assertNull(lease(old))
            val restored =
                activity.supportFragmentManager.fragments
                    .filterIsInstance<BottomWebViewDialog>()
                    .single()
            assertTrue(restored.requireView() is ComposeView)
            assertEquals(source.bookSourceUrl, restored.requireArguments().getString("sourceKey"))
            assertEquals(
                "${source.bookSourceUrl}/page",
                restored.requireArguments().getString("url"),
            )
            val current = requireNotNull(lease(restored))
            assertTrue(current.isInUse)
            val container = restored.requireView().findViewById<ViewGroup>(R.id.web_view_container)
            assertSame(current.realWebView, container.getChildAt(0))
            // The pool may legally reuse the old lease, but the old fragment no longer owns it.
            restored.dismissNow()
            assertFalse(current.isInUse)
            assertFalse(oldLease.isInUse)
        }
    }

    @Test
    fun synchronousDuplicateShowDoesNotAcquireAnotherWebViewAndCanReopenAfterDismiss() {
        scenario.onActivity { activity ->
            val first = newBrowser()
            first.show(activity.supportFragmentManager, "first")
            val duplicate = newBrowser()
            duplicate.show(activity.supportFragmentManager, "duplicate")
            assertFalse(duplicate.isAdded)
            assertNull(lease(duplicate))
            assertEquals(
                1,
                activity.supportFragmentManager.fragments
                    .filterIsInstance<BottomWebViewDialog>()
                    .size,
            )
            first.dismissNow()
            duplicate.show(activity.supportFragmentManager, "reopened")
            assertTrue(duplicate.isAdded)
            assertNotNull(lease(duplicate))
        }
        compose.onNodeWithTag("bottom-browser").assertExists()
    }

    @Test
    fun statelessShellMountsVideoOnlyDuringFullscreen() {
        val fullscreen = mutableStateOf(false)
        scenario.onActivity { activity ->
            activity.setContentView(
                ComposeView(activity).apply {
                    setContent {
                        BottomBrowserScreen(
                            fullscreen.value,
                            page = { Box(Modifier.fillMaxSize().testTag("fixture-page")) },
                            video = { Box(Modifier.fillMaxSize().testTag("fixture-video")) },
                        )
                    }
                }
            )
        }
        compose.onNodeWithTag("fixture-page").assertExists()
        compose.onNodeWithTag("fixture-video").assertDoesNotExist()
        compose.runOnIdle { fullscreen.value = true }
        compose.onNodeWithTag("fixture-video").assertExists()
        compose.runOnIdle { fullscreen.value = false }
        compose.onNodeWithTag("fixture-page").assertExists()
        compose.onNodeWithTag("fixture-video").assertDoesNotExist()
    }
}
