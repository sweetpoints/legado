package io.legado.app.ui.rss.article

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.widget.dialog.PhotoDialog
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RssSortActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private lateinit var server: ServerSocket
    private lateinit var source: RssSource
    private lateinit var article: RssArticle
    @Volatile private var photoGate: CountDownLatch? = null
    @Volatile private var photoRequested = false
    @Volatile private var photoResponse = false
    private lateinit var scenario: ActivityScenario<RssSortActivity>

    @Before
    fun setup() {
        server = ServerSocket(0)
        val base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true, name = "rss-compose-fixture") {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    var line = request
                    while (line != null && line.isNotEmpty()) line = reader.readLine()
                    if (request?.contains(" /article ") == true) {
                        photoRequested = true
                        photoGate?.await(5, TimeUnit.SECONDS)
                    }
                    val bytes = "<rss><channel></channel></rss>".toByteArray()
                    socket
                        .getOutputStream()
                        .write(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                    if (request?.contains(" /article ") == true) photoResponse = true
                }
            }
        }
        source =
            RssSource(
                sourceUrl = base,
                sourceName = "Owned RSS",
                searchUrl = "$base/search",
                preload = false,
            )
        article =
            RssArticle(
                origin = base,
                sort = "First",
                link = "$base/article",
                title = "Owned Article",
                variable = "{\"photo\":\"/owned.png\"}",
                content = "Owned content",
                description = "Owned description",
                type = 1,
            )
        runBlocking(Dispatchers.IO) {
            appDb.rssSourceDao.insert(source)
            appDb.rssArticleDao.insert(article)
        }
        scenario =
            ActivityScenario.launch(
                Intent(context, RssSortActivity::class.java)
                    .putExtra("sourceUrl", base)
                    .putExtra("sortUrl", "{\"First\":\"$base/feed\",\"Second\":\"$base/other\"}")
            )
        compose.waitUntil {
            var loaded = false
            scenario.onActivity { loaded = it.categoryModel.state.value.loaded }
            loaded
        }
    }

    @After
    fun close() {
        photoGate?.countDown()
        scenario.close()
        server.close()
        runBlocking(Dispatchers.IO) {
            appDb.rssArticleDao.delete(source.sourceUrl)
            appDb.rssReadRecordDao.deleteRecordsByOrigin(source.sourceUrl)
            appDb.rssSourceDao.delete(source.sourceUrl)
        }
    }

    @Test
    fun directComposeHostRestoresSelectedCategoryAndContainsNoLegacyPageFragments() {
        compose.onNodeWithTag("rss-category-tab-1").performClick()
        scenario.recreate()
        scenario.onActivity {
            assertEquals(1, it.categoryModel.state.value.selected)
            assertTrue(
                it.supportFragmentManager.fragments.none { fragment ->
                    fragment.javaClass.simpleName == "RssArticlesFragment"
                }
            )
        }
        compose.onNodeWithTag("rss-category-tab-1").assertIsSelected()
    }

    @Test
    fun exactEmptySearchSurvivesRecreationAndBackReturnsToCategories() {
        compose.onNodeWithTag("rss-category-search").performClick()
        compose.onNodeWithTag("rss-category-submit").performClick()
        compose.waitUntil {
            var done = false
            scenario.onActivity {
                done =
                    it.categoryModel.state.value.loaded &&
                        it.categoryModel.state.value.request?.query == ""
            }
            done
        }
        scenario.recreate()
        scenario.onActivity { assertEquals("", it.categoryModel.state.value.request!!.query) }
        compose.onNodeWithTag("rss-category-back").performClick()
        compose.waitUntil {
            var done = false
            scenario.onActivity {
                done =
                    it.categoryModel.state.value.loaded &&
                        it.categoryModel.state.value.request?.query == null
            }
            done
        }
        compose.onNodeWithTag("rss-category-tab-0").assertExists()
    }

    @Test
    fun nativeWebAndVideoEntryPreserveExactNavigationMetadata() {
        val intents = CopyOnWriteArrayList<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    val destination = intent.component?.className.orEmpty()
                    if (
                        destination.endsWith("ReadRssActivity") ||
                            destination.endsWith("VideoPlayerActivity")
                    ) {
                        intents += Intent(intent)
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            scenario.onActivity { ReadRss.readRss(it, article.copy(type = 0), source) }
            compose.waitUntil { intents.size == 1 }
            assertEquals(article.origin, intents[0].getStringExtra("origin"))
            assertEquals(article.title, intents[0].getStringExtra("title"))
            assertEquals(article.link, intents[0].getStringExtra("link"))
            assertEquals(article.sort, intents[0].getStringExtra("sort"))
            scenario.onActivity { ReadRss.readRss(it, article.copy(type = 2), source) }
            compose.waitUntil { intents.size == 2 }
            assertEquals(article.origin, intents[1].getStringExtra("sourceKey"))
            assertEquals(article.link, intents[1].getStringExtra("record"))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun nativePhotoRuleReceivesFullArticleVariableAndRecreationDoesNotReplayDialog() {
        var photoOnMain = false
        scenario.onActivity { host ->
            host.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentPreCreated(
                        fm: FragmentManager,
                        f: Fragment,
                        savedInstanceState: Bundle?,
                    ) {
                        if (f is PhotoDialog)
                            photoOnMain = Looper.myLooper() == Looper.getMainLooper()
                    }
                },
                false,
            )
            ReadRss.readRss(host, article, source.copy(ruleContent = "@js:java.get('photo')"))
        }
        compose.waitUntil {
            var photo = false
            scenario.onActivity { host ->
                photo =
                    host.supportFragmentManager.fragments.filterIsInstance<PhotoDialog>().any {
                        it.arguments?.getString("src") == "${source.sourceUrl}/owned.png"
                    }
            }
            photo
        }
        assertTrue(photoOnMain)
        scenario.recreate()
        scenario.onActivity { host ->
            val dialogs = host.supportFragmentManager.fragments.filterIsInstance<PhotoDialog>()
            assertEquals(1, dialogs.size)
            assertEquals(
                "${source.sourceUrl}/owned.png",
                dialogs.single().arguments!!.getString("src"),
            )
        }
    }

    @Test
    fun photoParsingCompletedWhilePausedWaitsForResumedHost() {
        photoGate = CountDownLatch(1)
        try {
            scenario.onActivity {
                ReadRss.readRss(it, article, source.copy(ruleContent = "@js:java.get('photo')"))
            }
            compose.waitUntil { photoRequested }
            scenario.moveToState(Lifecycle.State.STARTED)
            photoGate!!.countDown()
            compose.waitUntil { photoResponse }
            scenario.onActivity { host ->
                assertTrue(host.supportFragmentManager.fragments.none { it is PhotoDialog })
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil {
                var shown = false
                scenario.onActivity { host ->
                    shown =
                        host.supportFragmentManager.fragments.filterIsInstance<PhotoDialog>().any {
                            it.arguments?.getString("src") == "${source.sourceUrl}/owned.png"
                        }
                }
                shown
            }
        } finally {
            photoGate?.countDown()
        }
    }
}
