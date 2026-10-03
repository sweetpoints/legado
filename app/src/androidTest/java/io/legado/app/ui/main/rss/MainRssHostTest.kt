package io.legado.app.ui.main.rss

import android.content.SharedPreferences
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.commitNow
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.mainRssId
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.bookshelf.settings.BookshelfInputResult
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.utils.defaultSharedPreferences
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class MainRssHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences
    private val beforePreferences = preferences.all
    private val beforeLocal = LocalConfig.all
    private val group = "main-rss-${UUID.randomUUID()}"
    private val source =
        RssSource(sourceUrl = "https://$group.invalid", sourceName = group, sourceGroup = group)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var bookshelfFolderFixture: BookGroup? = null

    @Before
    fun before() {
        preferences
            .edit()
            .putBoolean(PreferKey.showRss, true)
            .putInt(PreferKey.bookGroupStyle, 0)
            .putBoolean(PreferKey.autoRefresh, false)
            .putBoolean(PreferKey.autoCheckNewBackup, false)
            .putBoolean("autoUpdateVariant", false)
            .putString(PreferKey.defaultHomePage, "rss")
            .commit()
        LocalConfig.edit()
            .putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "")
            .commit()
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(source) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitHostMigration()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("main-rss-search").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("main-rss-search").performTextReplacement("group:$group")
        awaitRows()
    }

    @After
    fun after() {
        scenario?.close()
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(source.sourceUrl) }
        bookshelfFolderFixture?.let { folder ->
            runBlocking(Dispatchers.IO) { appDb.bookGroupDao.delete(folder) }
        }
        preferences
            .edit()
            .clear()
            .apply { beforePreferences.forEach { (key, value) -> restore(key, value) } }
            .commit()
        LocalConfig.edit()
            .clear()
            .apply { beforeLocal.forEach { (key, value) -> restore(key, value) } }
            .commit()
    }

    private fun SharedPreferences.Editor.restore(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun awaitRows() {
        compose.waitUntil(timeoutMillis = 20_000) {
            var ready = false
            scenario!!.onActivity {
                ready =
                    it.mainRssModel.state.value.let { state ->
                        state.query == "group:$group" &&
                            state.rows.singleOrNull()?.sourceUrl == source.sourceUrl
                    }
            }
            ready
        }
    }

    private fun awaitHostMigration() {
        compose.waitUntil(timeoutMillis = 20_000) {
            var ready = false
            scenario!!.onActivity { ready = it.hostMigration.value.ready }
            ready
        }
    }

    @Test
    fun composeMainHostKeepsExactGroupQuerySelectionAcrossRecreate() {
        compose.onNodeWithTag("main-rss-card-${mainRssId(source.sourceUrl)}").assertIsDisplayed()
        var legacySessionId = ""
        scenario!!.onActivity { activity ->
            val legacy = RssFragment()
            activity.supportFragmentManager.commitNow { add(legacy, "legacy-rss-restore") }
            legacy.homeModel.bind()
            legacySessionId = legacy.homeModel.sessionId
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            var loaded = false
            scenario!!.onActivity { activity ->
                val legacy =
                    activity.supportFragmentManager.findFragmentByTag("legacy-rss-restore")
                        as? RssFragment
                loaded = legacy?.homeModel?.state?.value?.loaded == true
            }
            loaded
        }
        scenario!!.onActivity { activity ->
            val legacy =
                activity.supportFragmentManager.findFragmentByTag("legacy-rss-restore")
                    as RssFragment
            legacy.homeModel.visible(true)
            legacy.homeModel.query("group:$group", 2, 7)
        }
        scenario!!.recreate()
        awaitHostMigration()
        awaitRows()
        compose.onNodeWithTag("main-rss-search").assertTextContains("group:$group")
        scenario!!.onActivity { activity ->
            assertEquals(2, activity.mainRssModel.state.value.queryStart)
            assertEquals(7, activity.mainRssModel.state.value.queryEnd)
            assertEquals(legacySessionId, activity.mainRssModel.sessionId)
            assertTrue(activity.hostMigration.value.ready)
            assertFalse(activity.supportFragmentManager.fragments.any { it is RssFragment })
        }
    }

    @Test
    fun switchingRealMainPagerHidesConfirmationAndReturnsToPrivateDraftWithoutDeleting() {
        val id = mainRssId(source.sourceUrl)
        compose.onNodeWithTag("main-rss-card-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("main-rss-delete-$id").performClick()
        compose.onNodeWithTag("main-rss-delete-confirm").assertIsDisplayed()
        scenario!!.onActivity { it.viewModel.selectDestination(MainDestination.My) }
        compose.onNodeWithTag("main-rss-delete-confirm").assertDoesNotExist()
        scenario!!.onActivity { it.viewModel.selectDestination(MainDestination.Rss) }
        awaitRows()
        compose.onNodeWithTag("main-rss-delete-confirm").assertIsDisplayed()
        compose.onNodeWithTag("main-rss-delete-cancel").performClick()
        assertNotNull(runBlocking(Dispatchers.IO) { appDb.rssSourceDao.getByKey(source.sourceUrl) })
    }

    @Test
    fun restoredLegacyFolderOwnerHandsOffCurrentFolderAndBackNavigation() {
        scenario?.close()
        val groupId = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(100L)
        val folder = BookGroup(groupId, "Legacy folder ${UUID.randomUUID()}")
        bookshelfFolderFixture = folder
        runBlocking(Dispatchers.IO) { appDb.bookGroupDao.insert(folder) }
        preferences
            .edit()
            .putInt(PreferKey.bookGroupStyle, 1)
            .putString(PreferKey.defaultHomePage, MainDestination.Bookshelf.key)
            .commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitHostMigration()
        scenario!!.onActivity { activity ->
            val legacy =
                BookshelfFragment2().apply {
                    arguments =
                        Bundle().apply {
                            putInt("position", 0)
                            putLong("folder.group", groupId)
                            putInt("folder.scrollRequest", 17)
                            putInt("folder.nextScroll", 23)
                        }
                }
            activity.supportFragmentManager.commitNow { add(legacy, "legacy-bookshelf-folder") }
        }
        scenario!!.recreate()
        awaitHostMigration()
        compose.waitUntil(timeoutMillis = 20_000) {
            var restored = false
            scenario!!.onActivity {
                restored = it.bookshelfFolderModel.state.value.groupId == groupId
            }
            restored
        }
        scenario!!.onActivity { activity ->
            assertEquals(groupId, activity.bookshelfFolderModel.state.value.groupId)
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(BookGroup.IdRoot, activity.bookshelfFolderModel.state.value.groupId)
            assertFalse(activity.supportFragmentManager.fragments.any { it is BookshelfFragment2 })
        }
    }

    @Test
    fun busyLegacyTransferDoesNotBlockComposeHostAndRunsOnlyOnce() {
        val requestCount = AtomicInteger()
        val releaseResponse = CountDownLatch(1)
        val server =
            object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response {
                    requestCount.incrementAndGet()
                    releaseResponse.await(10, TimeUnit.SECONDS)
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "[]")
                }
            }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            scenario!!.onActivity { activity ->
                activity.viewModel.selectDestination(MainDestination.Bookshelf)
                val legacy = BookshelfFragment2()
                activity.supportFragmentManager.commitNow {
                    add(legacy, "legacy-busy-transfer")
                }
                legacy.submitShelfInput(
                    1,
                    BookshelfInputResult(
                        "http://127.0.0.1:${server.listeningPort}/books.json",
                        BookGroup.IdRoot,
                    ),
                )
            }
            compose.waitUntil(timeoutMillis = 20_000) { requestCount.get() == 1 }
            scenario!!.recreate()
            awaitHostMigration()
            compose.onNodeWithText("正在导入书单").assertIsDisplayed()
            scenario!!.onActivity { activity ->
                assertTrue(activity.hostMigration.value.ready)
                assertEquals(1, requestCount.get())
                assertTrue(
                    activity.supportFragmentManager.fragments.any {
                        it.tag == "legacy-busy-transfer"
                    }
                )
            }

            releaseResponse.countDown()
            compose.waitUntil(timeoutMillis = 20_000) {
                var removed = false
                scenario!!.onActivity { activity ->
                    removed =
                        activity.supportFragmentManager.fragments.none {
                            it.tag == "legacy-busy-transfer"
                        }
                }
                removed
            }
            assertEquals(1, requestCount.get())
        } finally {
            releaseResponse.countDown()
            server.stop()
        }
    }
}
