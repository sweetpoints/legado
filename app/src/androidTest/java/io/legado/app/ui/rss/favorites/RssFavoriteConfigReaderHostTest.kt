package io.legado.app.ui.rss.favorites

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.ui.rss.read.ReadRssActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real reader toolbar -> public dialog -> Flutter fields -> native callbacks -> actual Room. */
class RssFavoriteConfigReaderHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val driver = FlutterUiTestDriver(instrumentation)
    private val origin = "https://favorite-config-${UUID.randomUUID()}.invalid"
    private val link = "$origin/article"
    private val ids = mutableSetOf<String>()
    private lateinit var scenario: ActivityScenario<ReadRssActivity>
    @Before fun setup() {
        driver.enableAccessibility()
        runBlocking(Dispatchers.IO) {
            appDb.rssSourceDao.insert(RssSource(sourceUrl = origin, sourceName = "Favorite fixture"))
            appDb.rssArticleDao.insert(RssArticle(origin = origin, link = link, title = "Original title",
                group = "Original group", sort = "Original sort", description = "<p>Fixture</p>",
                variable = "{\"owned\":true}"))
        }
        scenario = ActivityScenario.launch(Intent(context, ReadRssActivity::class.java)
            .putExtra("origin", origin).putExtra("title", "Original title").putExtra("link", link)
            .putExtra("sort", "Original sort"))
        driver.await("Reader native document loaded") {
            var loaded = false
            scenario.onActivity { loaded = it.readerModel.state.value.loaded }
            loaded
        }
    }
    @After fun cleanup() {
        scenario.close(); driver.close()
        runBlocking(Dispatchers.IO) {
            appDb.rssStarDao.delete(origin, link)
            appDb.rssArticleDao.delete(origin)
            appDb.rssReadRecordDao.deleteRecordsByOrigin(origin)
            appDb.rssSourceDao.delete(origin)
        }
        ids.forEach { File(context.filesDir, "rss-favorite-config/$it.json").delete() }
    }
    private fun open() {
        compose.onNodeWithTag("rss-reader-favorite").performClick()
        // The toolbar's Room mutation is asynchronous. Its result opens the dialog from
        // RssReaderRoute.LaunchedEffect on the Compose test clock; plain Android polling
        // does not advance that clock. Wait for the same actual Flutter field while giving
        // the existing Compose effect its frames, with the original single 30s budget.
        val deadline = SystemClock.uptimeMillis() + 30_000
        try {
            compose.waitUntil(timeoutMillis = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) {
                val field = driver.node("favorite-config-title")
                val shown = field != null
                field?.recycle()
                shown
            }
        } catch (failure: Exception) {
            throw AssertionError("Reader favorite Flutter field did not appear; ${readerDiagnostic()}", failure)
        }
        scenario.onActivity { activity ->
            val dialog = activity.supportFragmentManager.fragments.filterIsInstance<RssFavoritesDialog>().single()
            ids += dialog.requireArguments().getString("requestId")!!
            assertTrue(dialog.childFragmentManager.fragments.any { it is RssFavoriteConfigFlutterFragment })
        }
        assertNotNull(runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, link) })
    }
    private fun readerDiagnostic(): String {
        var diagnostic = ""
        scenario.onActivity { activity ->
            val state = activity.readerModel.state.value
            val dialog = activity.supportFragmentManager.fragments.filterIsInstance<RssFavoritesDialog>().firstOrNull()
            val config = dialog?.model?.state?.value
            diagnostic = "loaded=${state.loaded},busy=${state.busy},article=${state.article}," +
                "favorite=${state.favorite},pending=${state.pending?.action?.name}," +
                "documentReady=${state.document > 0},error=${state.error != null}," +
                "resumed=${activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED}," +
                "stateSaved=${activity.supportFragmentManager.isStateSaved}," +
                "finishing=${activity.isFinishing},destroyed=${activity.isDestroyed}," +
                "dialogAdded=${dialog?.isAdded == true},configLoaded=${config?.loaded}," +
                "configBusy=${config?.busy},configFinished=${config?.finished},configError=${config?.error != null}," +
                "flutterChild=${dialog?.childFragmentManager?.findFragmentByTag("rss-favorite-config-flutter") != null}"
        }
        val rowPresent = runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, link) != null }
        return "$diagnostic,rowPresent=$rowPresent"
    }
    private fun closed() = driver.await("Favorite dialog closed after native receipt") {
        var closed = false
        scenario.onActivity { activity ->
            closed = activity.supportFragmentManager.fragments.none { it is RssFavoritesDialog }
        }
        closed
    }
    @Test fun readerSaveUsesFlutterFieldsAndNativeCallbackWithoutLosingMetadata() {
        open()
        driver.setText("favorite-config-title", "Edited title")
        driver.setText("favorite-config-group", "Edited group")
        closeSoftKeyboard()
        driver.click("favorite-config-confirm"); closed()
        driver.await("Native reader and Room accepted favorite metadata") {
            var accepted = false
            scenario.onActivity { accepted = it.readerModel.snapshot()?.article?.title == "Edited title" }
            val star = runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, link) }
            accepted && star?.title == "Edited title" && star.group == "Edited group"
        }
        val star = runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, link) }!!
        assertEquals("Original sort", star.sort)
        assertEquals("{\"owned\":true}", star.variable)
        scenario.recreate()
        driver.await("Consumed dialog is not replayed after reader recreation") {
            var loaded = false
            scenario.onActivity {
                loaded = it.readerModel.state.value.loaded &&
                    it.supportFragmentManager.fragments.none { f -> f is RssFavoritesDialog }
            }
            loaded
        }
    }
    @Test fun cancellingDraftRetainsTheFavoriteCreatedBeforeTheDialog() {
        open(); driver.setText("favorite-config-title", "Cancelled title")
        closeSoftKeyboard(); driver.click("favorite-config-cancel"); closed()
        val star = runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, link) }!!
        assertEquals("Original title", star.title); assertEquals("Original group", star.group)
    }
    @Test fun deleteIsDeliveredThroughReaderAndRemovesOnlyTheOwnedFavorite() {
        open(); closeSoftKeyboard(); driver.click("favorite-config-delete"); closed()
        driver.await("Reader callback removed owned favorite") {
            runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, link) } == null
        }
        assertNotNull(runBlocking(Dispatchers.IO) { appDb.rssArticleDao.getByLink(origin, link) })
    }
}
