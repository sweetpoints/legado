package io.legado.app.help.source

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.repository.AppBrowserNavigationStore
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.browser.BrowserRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.browser.BrowserNavigation
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceNavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun backgroundRequestsDoNotBorrowOrReturnAnInteractiveWebViewAndCancellationReleasesTheirLease() = runBlocking {
        val loaded = CountDownLatch(1)
        val recycled = CountDownLatch(1)
        var interactive: PooledWebView? = null
        var background: PooledWebView? = null
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                entered.countDown()
                unblock.await(5, TimeUnit.SECONDS)
                return newFixedLengthResponse("<p>Cancellation fixture</p>")
            }
        }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val lease = WebViewPool.acquire(activity)
                    interactive = lease
                    (activity.findViewById<ViewGroup>(android.R.id.content)).addView(lease.realWebView)
                    lease.realWebView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loaded.countDown()
                        }
                    }
                    lease.realWebView.loadDataWithBaseURL("https://interactive.invalid/", "<button>UI fixture</button>",
                        "text/html", "utf-8", null)
                }
                assertTrue("The attached interactive page really loads", loaded.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    val lease = checkNotNull(interactive)
                    assertTrue(lease.realWebView.isAttachedToWindow)
                    WebViewPool.release(lease)
                    // Observe the original pool recycle completion rather than guessing its delay.
                    val recycleClient = lease.realWebView.webViewClient
                    lease.realWebView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            recycleClient.onPageFinished(view, url)
                            if (!lease.isInUse) recycled.countDown()
                        }
                    }
                }
                assertTrue("The interactive instance returns to the pool", recycled.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    background = WebViewPool.acquire(context, recyclable = false)
                    assertNotSame(interactive, background)
                    assertNotSame(checkNotNull(interactive).realWebView, checkNotNull(background).realWebView)
                    WebViewPool.release(checkNotNull(background))
                    assertFalse("The dedicated lease is invalid after disposal", checkNotNull(background).isInUse)
                    val nextUi = WebViewPool.acquire(context)
                    assertSame("Background disposal retains the existing UI instance", interactive, nextUi)
                    assertNotSame(background, nextUi)
                    WebViewPool.release(nextUi)
                }
                assertEquals("owned-background", BackstageWebView(
                    url = "https://background.invalid/", html = "<p>Background fixture</p>",
                    javaScript = "'owned-background'",
                ).getStrResponse().body)
                server.start()
                val request = BackstageWebView(url = "http://127.0.0.1:${server.listeningPort}/cancel")
                val pending = launch(Dispatchers.IO) { request.getStrResponse() }
                try {
                    assertTrue("Cancellation occurs during an actual submitted request", entered.await(5, TimeUnit.SECONDS))
                    val leaseSlot = BackstageWebView::class.java.getDeclaredField("pooledWebView")
                        .apply { isAccessible = true }
                    var cancellationLease: PooledWebView? = null
                    instrumentation.runOnMainSync {
                        cancellationLease = leaseSlot.get(request) as? PooledWebView
                        assertNotNull(cancellationLease)
                        assertNotSame(interactive, cancellationLease)
                    }
                    pending.cancelAndJoin()
                    assertTrue(pending.isCancelled)
                    // No calls on a destroyed WebView: validate the request's ownership slot only.
                    assertNull("Cancellation finishes Main-thread disposal before returning", leaseSlot.get(request))
                    assertFalse("Cancellation invalidates its dedicated lease", checkNotNull(cancellationLease).isInUse)
                } finally {
                    unblock.countDown()
                    pending.cancelAndJoin()
                }
            } finally {
                unblock.countDown()
                server.stop()
                instrumentation.runOnMainSync {
                    interactive?.takeIf { it.isInUse }?.let(WebViewPool::release)
                    background?.takeIf { it.isInUse }?.let(WebViewPool::release)
                }
            }
        }
    }

    @Test
    fun verificationBrowserHandoffCarriesOnlyTicketAndKeepsTheRegisteredAttemptPayload() {
        val source = BookSource(bookSourceUrl = "https://prepared-verification.invalid/source")
        val key = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val largeHtml = "<html><body>verification-private-".plus("full-html-".repeat(40_000))
        val starts = CopyOnWriteArrayList<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className == WebViewActivity::class.java.name) {
                        starts += intent
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
        instrumentation.addMonitor(monitor)
        var ticket: String? = null
        try {
            SourceVerificationHelp.startBrowser(
                source,
                "https://prepared-verification.invalid/challenge?" + "opaque=".repeat(8_000),
                "Verification page",
                saveResult = true,
                refetchAfterSuccess = false,
                html = largeHtml,
                verificationResultKey = key,
            )
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (starts.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, starts.size)
            val intent = starts.single()
            assertEquals(setOf(BrowserNavigation.PREPARED_TICKET), intent.extras!!.keySet())
            ticket = requireNotNull(intent.getStringExtra(BrowserNavigation.PREPARED_TICKET))

            val request =
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    AppBrowserNavigationStore(context).read(requireNotNull(ticket))
                }
            assertEquals(
                BrowserRequest(
                    url =
                        "https://prepared-verification.invalid/challenge?" +
                            "opaque=".repeat(8_000),
                    title = "Verification page",
                    sourceName = source.bookSourceName,
                    sourceOrigin = source.bookSourceUrl,
                    sourceType = source.getSourceType(),
                    html = largeHtml,
                    verificationEnabled = true,
                    refetchAfterSuccess = false,
                    verificationKey = key,
                ),
                request,
            )
        } finally {
            ticket?.let { id ->
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    AppBrowserNavigationStore(context).abandon(id)
                }
            }
            SourceVerificationHelp.cancelVerificationAttempt(key)
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun canceledVerificationAttemptCleansPreparedTicketBeforeMainThreadHandoff() {
        val source = BookSource(bookSourceUrl = "https://cancel-verification.invalid/source")
        val key = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val neighborKey = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val starts = CopyOnWriteArrayList<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className == WebViewActivity::class.java.name) {
                        starts += intent
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
        val directory = java.io.File(context.filesDir, "browser-navigation")
        val before = directory.listFiles()?.map { it.name }.orEmpty().toSet()
        val enteredMain = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        var preparedFile: java.io.File? = null
        var neighborFile: java.io.File? = null
        instrumentation.addMonitor(monitor)
        Handler(Looper.getMainLooper()).post {
            enteredMain.countDown()
            releaseMain.await(15, TimeUnit.SECONDS)
        }
        try {
            assertTrue(enteredMain.await(5, TimeUnit.SECONDS))
            SourceVerificationHelp.startBrowser(
                source,
                "https://cancel-verification.invalid/challenge",
                "Cancelled verification",
                saveResult = true,
                verificationResultKey = key,
                html = "cancel-before-handoff-".repeat(150_000),
            )
            preparedFile = awaitPreparedFile(directory, before, key)
            SourceVerificationHelp.startBrowser(
                source,
                "https://cancel-verification.invalid/neighbor",
                "Neighbor verification",
                saveResult = true,
                verificationResultKey = neighborKey,
                html = "neighbor-private-html",
            )
            neighborFile = awaitPreparedFile(directory, before + preparedFile.name, neighborKey)
            assertNotNull("Browser request was not prepared before the handoff", preparedFile)
            assertNotNull("Neighbor request was not prepared before the handoff", neighborFile)

            SourceVerificationHelp.cancelVerificationAttempt(key)
            releaseMain.countDown()
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (preparedFile.exists() && System.nanoTime() < cleanupDeadline) Thread.sleep(10)
            assertFalse("Canceled request file should be abandoned", preparedFile.exists())
            val handoffDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (starts.isEmpty() && System.nanoTime() < handoffDeadline) Thread.sleep(10)
            assertEquals("The active neighboring request must still launch", 1, starts.size)
            val neighborTicket =
                requireNotNull(starts.single().getStringExtra(BrowserNavigation.PREPARED_TICKET))
            assertEquals(neighborFile.nameWithoutExtension, neighborTicket)
            assertEquals(
                neighborKey,
                runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    AppBrowserNavigationStore(context).read(neighborTicket).verificationKey
                },
            )
        } finally {
            SourceVerificationHelp.cancelVerificationAttempt(key)
            SourceVerificationHelp.cancelVerificationAttempt(neighborKey)
            releaseMain.countDown()
            listOfNotNull(preparedFile, neighborFile).forEach { file ->
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    AppBrowserNavigationStore(context).abandon(file.nameWithoutExtension)
                }
            }
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun awaitPreparedFile(
        directory: java.io.File,
        existingNames: Set<String>,
        verificationKey: String,
    ): java.io.File {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            directory
                .listFiles()
                ?.firstOrNull { it.name.endsWith(".json") && it.name !in existingNames }
                ?.let { file ->
                    val request =
                        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                AppBrowserNavigationStore(context).read(file.nameWithoutExtension)
                            }
                                .getOrNull()
                        }
                    if (request?.verificationKey == verificationKey) return file
                }
            Thread.sleep(10)
        }
        error("Browser request for verification attempt was not prepared")
    }

    @Test
    fun optInBlocksIncidentalRuleUiButKeepsHttpAndInteractiveDetailWorking() = runBlocking {
        val preferences = context.defaultSharedPreferences
        val saved = preferences.all[PreferKey.blockSourceNavigation] as? Boolean
        val savedHelp = LocalConfig.all["bookSourceHelpVersion"] as? Int
        val source = BookSource(bookSourceUrl = "https://navigation.invalid/${UUID.randomUUID()}")
        val starts = CopyOnWriteArrayList<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    starts += intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        val server =
            object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response =
                    newFixedLengthResponse(
                        "<article><h2>Navigation fixture</h2><a href='/book'>Book</a></article>"
                    )
            }
        var scenario: ActivityScenario<BookSourceActivity>? = null
        try {
            preferences.edit().remove(PreferKey.blockSourceNavigation).commit()
            assertFalse(AppConfig.blockSourceNavigation)
            LocalConfig.edit().putInt("bookSourceHelpVersion", 1).commit()
            scenario = ActivityScenario.launch(BookSourceActivity::class.java)
            source.loginUrl =
                "@js:function login() { java.openUrl('https://navigation.invalid/nested'); source.put('navigationLogin', 'ok'); }"
            appDb.bookSourceDao.insert(source)
            instrumentation.addMonitor(monitor)
            server.start()
            val pageUrl = "http://127.0.0.1:${server.listeningPort}/search"
            val open = "java.openUrl('https://navigation.invalid/login');"
            source.searchUrl = "@js:$open'$pageUrl'"
            source.ruleSearch =
                SearchRule(bookList = "article", name = "h2@text", bookUrl = "a@href")
            source.ruleExplore =
                ExploreRule(bookList = "article", name = "h2@text", bookUrl = "a@href")

            // The real search entry marks its operation even when the caller does not.
            assertEquals(1, WebBook.searchBookAwait(source, "fixture").size)
            assertEquals(1, starts.size) // Default off preserves existing source behavior.
            starts.clear()
            AppConfig.blockSourceNavigation = true
            assertEquals(
                "Navigation fixture",
                WebBook.searchBookAwait(source, "fixture").single().name,
            )
            assertTrue(starts.isEmpty())

            withContext(SuppressSourceNavigation) {
                val rule =
                    AnalyzeRule(source = source).setCoroutineContext(currentCoroutineContext())
                val result =
                    rule.evalJS(
                        """
                        java.openUrl('https://navigation.invalid/login');
                        source.openUrl('https://navigation.invalid/login');
                        java.startBrowser('https://navigation.invalid/login', 'Login');
                        java.showBrowser('https://navigation.invalid/login');
                        java.openVideoPlayer('https://navigation.invalid/video.mp4', 'Video', false);
                        var blocked = false;
                        try { java.startBrowserAwait('https://navigation.invalid/login', 'Login'); }
                        catch (e) { blocked = true; }
                        blocked;
                        """
                            .trimIndent()
                    )
                assertEquals(true, result)
                assertTrue(starts.isEmpty())
                scenario!!.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }

                // Rule WebViews call both bridges on a separate Java thread; storage remains
                // usable.
                rule.setContent("<p>Background</p>", pageUrl)
                source.header =
                    "@js:java.openUrl('https://navigation.invalid/header'); '{\"X-Navigation\":\"ok\"}'"
                for (blocked in listOf(true, false)) {
                    AppConfig.blockSourceNavigation = blocked
                    Log.i("SourceNavigationTest", "webjs start blocked=$blocked")
                    val response =
                        try {
                            rule.getString(
                                """
                                @webjs:
                                                        console.info('navigation webjs: started');
                                                        java.openUrl('https://navigation.invalid/java');
                                                        source.openUrl('https://navigation.invalid/source');
                                                        console.info('navigation webjs: before login');
                                                        source.login();
                                                        console.info('navigation webjs: after login');
                                                        source.put('navigationTest', 'stored');
                                                        console.info('navigation webjs: stored');
                                                        source.get('navigationTest') + ':' + document.querySelector('p').textContent + ':' + source.get('navigationLogin');
                                """
                                    .trimIndent()
                            )
                        } catch (error: Throwable) {
                            File(
                                    context.getExternalFilesDir("ui-regression"),
                                    "source-navigation-timeout-threads.txt",
                                )
                                .writeText(
                                    "blocked=$blocked; starts=${starts.size}\n" +
                                        Thread.getAllStackTraces().entries.joinToString("\n\n") {
                                            (thread, stack) ->
                                            "${thread.name}: ${thread.state}\n${stack.joinToString("\n")}"
                                        }
                                )
                            throw error
                        }
                    Log.i(
                        "SourceNavigationTest",
                        "webjs completed blocked=$blocked response=$response",
                    )
                    assertEquals("stored:Background:ok", response)
                    awaitIntentCount(starts, if (blocked) 0 else 4)
                    assertEquals(if (blocked) 0 else 4, starts.size)
                    assertStartedRequests(starts, source)
                    starts.clear()
                }
                source.header = null
                AppConfig.blockSourceNavigation = true
            }

            // A normal detail request still runs its required login after a blocked search.
            source.ruleBookInfo = BookInfoRule(init = "@js:${open}result", name = "h2@text")
            val book = Book(bookUrl = pageUrl, origin = source.bookSourceUrl)
            assertEquals("Navigation fixture", WebBook.getBookInfoAwait(source, book).name)
            assertEquals(1, starts.size)
            starts.clear()
            assertEquals(1, WebBook.exploreBookAwait(source, "@js:$open'$pageUrl'").size)
            assertEquals(1, starts.size)
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario?.close()
            server.stop()
            appDb.bookSourceDao.delete(source)
            preferences
                .edit()
                .apply {
                    if (saved == null) remove(PreferKey.blockSourceNavigation)
                    else putBoolean(PreferKey.blockSourceNavigation, saved)
                }
                .commit()
            LocalConfig.edit()
                .apply {
                    if (savedHelp == null) remove("bookSourceHelpVersion")
                    else putInt("bookSourceHelpVersion", savedHelp)
                }
                .commit()
        }
    }

    private suspend fun assertStartedRequests(intents: List<Intent>, source: BookSource) {
        for (intent in intents) {
            val ticket = intent.getStringExtra(BrowserNavigation.PREPARED_TICKET)
            if (ticket == null) {
                assertEquals(SourceType.book, intent.getIntExtra("sourceType", -1))
                continue
            }
            assertEquals(setOf(BrowserNavigation.PREPARED_TICKET), intent.extras!!.keySet())
            val request =
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppBrowserNavigationStore(context).read(ticket)
                }
            try {
                assertEquals(source.getKey(), request.sourceOrigin)
                assertEquals(source.getTag(), request.sourceName)
                assertNull(request.verificationKey)
            } finally {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppBrowserNavigationStore(context).abandon(ticket)
                }
            }
        }
    }

    private fun awaitIntentCount(intents: List<Intent>, expected: Int) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (intents.size < expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, intents.size)
    }
}
