package io.legado.app.ui.book.explore

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.repository.ExploreResultsCategory
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreCategoriesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val originalCategories = preferences.all[PreferKey.showExploreCategories]
    private val originalCronet = preferences.all[PreferKey.cronet]
    private val slowStarted = CountDownLatch(1)
    private val releaseSlow = CountDownLatch(1)
    private val slowFinished = CountDownLatch(1)
    private val server =
        object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/category/1") {
                    slowStarted.countDown()
                    releaseSlow.await(20, TimeUnit.SECONDS)
                }
                val category = session.uri.substringAfterLast('/')
                val page = session.parameters["page"]?.firstOrNull() ?: "1"
                val ids =
                    when {
                        category == "2" && page == "3" -> (50..69).toList()
                        category == "2" && page == "2" -> listOf(51) + (30..48)
                        else -> (1..20).toList()
                    }
                val html =
                    ids.joinToString("") {
                        val bookPath =
                            if (category == "2") "/book/$category/$it"
                            else "/book/$category/$page/$it"
                        "<a href='$bookPath'>Category $category page $page book $it</a>"
                    }
                if (category == "1") slowFinished.countDown()
                return newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }
        }
    private lateinit var source: BookSource
    private var scenario: ActivityScenario<ExploreShowActivity>? = null

    private fun categoryUrl(index: Int) =
        "http://127.0.0.1:${server.listeningPort}/category/$index?page={{page}}"

    @Before
    fun setUp() {
        preferences
            .edit()
            .remove(PreferKey.showExploreCategories)
            .putBoolean(PreferKey.cronet, false)
            .commit()
        server.start()
        source =
            BookSource(
                bookSourceUrl = "http://127.0.0.1:${server.listeningPort}/${UUID.randomUUID()}",
                bookSourceName = "Explore categories fixture",
                exploreUrl = (0 until 22).joinToString("&&") { "Category $it::${categoryUrl(it)}" },
                ruleExplore = ExploreRule(bookList = "tag.a", name = "text", bookUrl = "href"),
            )
        appDb.bookSourceDao.insert(source)
        launch()
        awaitActivity { it.resultsModel.state.value.rows.size == 20 }
    }

    @After
    fun tearDown() {
        releaseSlow.countDown()
        scenario?.close()
        server.stop()
        if (::source.isInitialized) {
            appDb.bookSourceDao.delete(source.bookSourceUrl)
            appDb.openHelper.writableDatabase.execSQL(
                "DELETE FROM searchBooks WHERE origin = ?",
                arrayOf(source.bookSourceUrl),
            )
        }
        preferences
            .edit()
            .apply {
                if (originalCategories == null) remove(PreferKey.showExploreCategories)
                else putBoolean(PreferKey.showExploreCategories, originalCategories as Boolean)
                if (originalCronet == null) remove(PreferKey.cronet)
                else putBoolean(PreferKey.cronet, originalCronet as Boolean)
            }
            .commit()
    }

    @Test
    fun backupRestoresToggleAndLegacyBackupResetsIt() {
        scenario!!.close()
        scenario = null
        val directory = File(context.cacheDir, "explore-backup-${UUID.randomUUID()}")
        val oldTitlePreference = preferences.all[PreferKey.showReadTitleChapterNameOnly]
        try {
            writePreferenceSnapshot(context, directory.path, "config") {
                putBoolean(PreferKey.showExploreCategories, true)
            }
            AppConfig.showExploreCategories = false
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertTrue(AppConfig.showExploreCategories)
            writePreferenceSnapshot(context, directory.path, "config") {}
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertFalse(AppConfig.showExploreCategories)
        } finally {
            preferences
                .edit()
                .apply {
                    if (oldTitlePreference == null) remove(PreferKey.showReadTitleChapterNameOnly)
                    else
                        putBoolean(
                            PreferKey.showReadTitleChapterNameOnly,
                            oldTitlePreference as Boolean,
                        )
                }
                .commit()
            directory.deleteRecursively()
        }
    }

    @Test
    fun globalToggleRendersBalancedRowsAndSurvivesReopening() {
        assertFalse(AppConfig.showExploreCategories)
        compose.onNodeWithTag("explore-category-row-0").assertDoesNotExist()
        toggleCategories()
        awaitActivity { it.resultsModel.state.value.checkpoint?.categories?.size == 22 }
        assertEquals(
            listOf(8, 7, 7),
            splitExploreCategoryRows((0 until 22).toList()).map { it.size },
        )
        (0..2).forEach { compose.onNodeWithTag("explore-category-row-$it").assertIsDisplayed() }
        compose.onNodeWithTag("explore-category-0").assertIsSelected()
        screenshot("explore-categories")
        scenario!!.close()
        launch()
        awaitActivity { it.resultsModel.state.value.checkpoint?.categories?.size == 22 }
        assertTrue(AppConfig.showExploreCategories)
        compose.onNodeWithTag("explore-category-row-0").assertIsDisplayed()
        toggleCategories()
        awaitActivity { !it.resultsModel.state.value.showCategories }
        compose.onNodeWithTag("explore-category-row-0").assertDoesNotExist()
    }

    @Test
    fun prependingOverlappingPageRetainsVisibleBook() {
        scenario!!.onActivity {
            it.resultsModel.category(ExploreResultsCategory("Category 2", categoryUrl(2)))
        }
        awaitActivity {
            it.resultsModel.state.value.rows.firstOrNull()?.name?.startsWith("Category 2") == true
        }
        compose.onNodeWithTag("explore-results-page").performClick()
        compose.onNodeWithTag("explore-results-page-input").performTextReplacement("3")
        compose.onNodeWithTag("explore-results-confirm-page").performClick()
        awaitActivity {
            it.resultsModel.state.value.checkpoint?.displayedPage == 3 &&
                it.resultsModel.state.value.rows.size == 20
        }
        var anchorKey = ""
        scenario!!.onActivity {
            anchorKey =
                it.resultsModel.state.value.rows.first { row -> row.bookUrl.endsWith("/50") }.key
        }
        compose
            .onNodeWithTag("explore-results-list")
            .performScrollToNode(hasTestTag("explore-book-$anchorKey"))
        compose.waitForIdle()
        val top =
            compose.onNodeWithTag("explore-book-$anchorKey").fetchSemanticsNode().boundsInRoot.top
        scenario!!.onActivity { activity ->
            activity.resultsModel.scroll(anchorKey, 0, 0)
        }
        awaitActivity { it.resultsModel.state.value.checkpoint?.scrollKey == anchorKey }
        scenario!!.onActivity { it.resultsModel.previous() }
        awaitActivity {
            it.resultsModel.state.value.rows.size == 39 &&
                it.resultsModel.state.value.scrollRequest == 0L
        }
        compose.onNodeWithTag("explore-book-$anchorKey").assertIsDisplayed()
        val restoredTop =
            compose.onNodeWithTag("explore-book-$anchorKey").fetchSemanticsNode().boundsInRoot.top
        assertEquals(top, restoredTop, 2f)
        scenario!!.onActivity { assertEquals(4, it.resultsModel.state.value.checkpoint!!.nextPage) }
    }

    @Test
    fun categorySwitchRejectsLateResponseAndRetainsPageAfterRecreation() {
        toggleCategories()
        awaitActivity { it.resultsModel.state.value.checkpoint?.categories?.size == 22 }
        compose.onNodeWithTag("explore-category-1").performClick()
        assertTrue("Slow category request started", slowStarted.await(15, TimeUnit.SECONDS))
        compose.onNodeWithTag("explore-category-8").performClick()
        awaitActivity {
            it.resultsModel.state.value.rows.firstOrNull()?.name?.startsWith("Category 8") == true
        }
        releaseSlow.countDown()
        assertTrue(slowFinished.await(15, TimeUnit.SECONDS))
        scenario!!.onActivity { it.resultsModel.next() }
        awaitActivity { it.resultsModel.state.value.checkpoint?.displayedPage == 2 }
        scenario!!.onActivity { activity ->
            assertEquals(40, activity.resultsModel.state.value.rows.size)
            assertTrue(
                activity.resultsModel.state.value.rows.all { it.name.startsWith("Category 8") }
            )
        }
        scenario!!.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
        scenario!!.recreate()
        awaitActivity { it.resultsModel.state.value.checkpoint?.categories?.size == 22 }
        scenario!!.onActivity { activity ->
            assertEquals(
                "Category 8",
                activity.resultsModel.state.value.checkpoint!!.selectedCategory.title,
            )
            assertEquals(2, activity.resultsModel.state.value.checkpoint!!.displayedPage)
            assertEquals(40, activity.resultsModel.state.value.rows.size)
        }
        compose.onNodeWithTag("explore-category-8").assertIsSelected()
        compose.onNodeWithTag("explore-category-0").assertIsNotSelected()
        screenshot("explore-category-restored")
    }

    @Test
    fun preparedLargeInputRecreatesFromOnlyUuid() {
        scenario!!.close()
        val paddedUrl = categoryUrl(0) + "&padding=" + "x".repeat(100_000)
        val session = runBlocking {
            io.legado.app.data.repository
                .AppExploreResultsSessionRepository()
                .prepare(source.bookSourceUrl, "Category 0", paddedUrl)
        }
        scenario =
            ActivityScenario.launch(
                Intent(context, ExploreShowActivity::class.java)
                    .putExtra(ExploreShowActivity.PREPARED_SESSION, session)
            )
        awaitActivity { it.resultsModel.state.value.loaded }
        scenario!!.recreate()
        awaitActivity { it.resultsModel.state.value.loaded }
        scenario!!.onActivity { activity ->
            assertEquals(
                paddedUrl,
                activity.resultsModel.state.value.checkpoint!!.request.exploreUrl,
            )
            assertEquals(
                setOf(ExploreShowActivity.PREPARED_SESSION),
                activity.intent.extras!!.keySet(),
            )
        }
    }

    private fun launch() {
        scenario =
            ActivityScenario.launch(
                Intent(context, ExploreShowActivity::class.java).apply {
                    putExtra("sourceUrl", source.bookSourceUrl)
                    putExtra("exploreName", "Category 0")
                    putExtra("exploreUrl", categoryUrl(0))
                }
            )
    }

    private fun toggleCategories() {
        compose.onNodeWithTag("explore-results-menu").performClick()
        compose.onNodeWithTag("explore-results-show-categories").performClick()
    }

    private fun awaitActivity(condition: (ExploreShowActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            var satisfied = false
            scenario!!.onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        var state = ""
        scenario!!.onActivity { state = it.resultsModel.state.value.toString().take(2000) }
        throw AssertionError("Timed out: $state")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
