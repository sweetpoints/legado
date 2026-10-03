package io.legado.app.ui.login

import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.RssSource
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class SourceWebLoginControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val source = RssSource(sourceUrl = "https://native-login-${UUID.randomUUID()}.invalid", sourceName = "Feed")
    @Test fun nativeBrowserRetainsHeaderUserAgentAndOriginalViewportSettings() {
        instrumentation.runOnMainSync {
            val controller = SourceWebLoginController(instrumentation.targetContext, source, mapOf("User-Agent" to "Exact custom agent", "Auth" to "Value"))
            try {
                assertEquals("Exact custom agent", controller.webView.settings.userAgentString)
                assertTrue(controller.webView.settings.useWideViewPort); assertTrue(controller.webView.settings.loadWithOverviewMode)
                controller.webView.webChromeClient!!.onProgressChanged(controller.webView, 38)
                assertEquals(38, controller.state.value.progress)
            } finally { controller.release(); controller.release() }
        }
    }
    @Suppress("DEPRECATION")
    @Test fun httpStaysInBrowserAndExternalSchemeRequiresOneConfirmablePendingUrl() {
        instrumentation.runOnMainSync {
            val controller = SourceWebLoginController(instrumentation.targetContext, source, emptyMap())
            try {
                assertFalse(controller.webView.webViewClient.shouldOverrideUrlLoading(controller.webView, "https://normal.invalid/path"))
                assertNull(controller.state.value.external)
                assertTrue(controller.webView.webViewClient.shouldOverrideUrlLoading(controller.webView, "customapp://exact/path"))
                assertEquals("customapp://exact/path", controller.state.value.external)
                controller.consumeExternal(); assertNull(controller.state.value.external)
            } finally { controller.release() }
        }
    }

    private class Cookies : io.legado.app.data.repository.SourceLoginCookieRepository {
        val captures = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val began = java.util.concurrent.CountDownLatch(1)
        override suspend fun store(sourceKey: String, cookie: String?) {
            captures += cookie; began.countDown()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
        }
    }
    @Test fun delayedOrderedCookieWritesCannotFinishBeforeDurabilityAndReleasedOwnerNeverPublishesLate() {
        val cookies = Cookies(); lateinit var controller: SourceWebLoginController
        try {
            instrumentation.runOnMainSync {
                controller = SourceWebLoginController(instrumentation.targetContext, source, emptyMap(), cookies)
                val manager = CookieManager.getInstance()
                manager.setCookie(source.sourceUrl, "ownedSession=first")
                controller.webView.webViewClient.onPageStarted(controller.webView, source.sourceUrl, null)
                controller.check()
                manager.setCookie(source.sourceUrl, "ownedSession=last")
                controller.webView.webViewClient.onPageFinished(controller.webView, source.sourceUrl)
                assertTrue(controller.state.value.checking); assertFalse(controller.state.value.completed)
            }
            assertTrue(cookies.began.await(10, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertEquals(1, cookies.captures.size); assertFalse(controller.state.value.completed) }
            cookies.gate.complete(Unit)
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { controller.state.first { it.completed } } }
            assertTrue(controller.state.value.completed); assertEquals(2, cookies.captures.size)
            assertTrue(cookies.captures.first().orEmpty().contains("ownedSession=first"))
            assertTrue(cookies.captures.last().orEmpty().contains("ownedSession=last"))
        } finally { cookies.gate.complete(Unit); instrumentation.runOnMainSync { controller.release() } }
        val late = Cookies(); lateinit var stopped: SourceWebLoginController
        try {
            instrumentation.runOnMainSync {
                stopped = SourceWebLoginController(instrumentation.targetContext, source, emptyMap(), late)
                stopped.check(); stopped.webView.webViewClient.onPageFinished(stopped.webView, source.sourceUrl)
            }
            assertTrue(late.began.await(10, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { stopped.release(); late.gate.complete(Unit) }
            instrumentation.waitForIdleSync()
            assertFalse(stopped.state.value.completed)
        } finally { late.gate.complete(Unit); instrumentation.runOnMainSync { stopped.release() } }
    }
}
