package io.legado.app.ui.rss.read

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class ReadRssActivityComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val origin = "https://rss-reader-${UUID.randomUUID()}.invalid"
    private lateinit var scenario: ActivityScenario<ReadRssActivity>
    @Before fun setup() {
        runBlocking(Dispatchers.IO) {
            appDb.rssSourceDao.insert(RssSource(sourceUrl = origin, sourceName = "Owned reader", enableJs = true, loginUrl = "https://fixture.invalid/login"))
            appDb.rssArticleDao.insert(RssArticle(origin = origin, sort = "Owned sort", link = "$origin/article", title = "Exact article",
                description = "<p id='owned'>Owned body</p>", variable = "{\"owned\":\"exact\"}"))
        }
        scenario = ActivityScenario.launch(Intent(context, ReadRssActivity::class.java).putExtra("origin", origin)
            .putExtra("title", "Exact title").putExtra("link", "$origin/article").putExtra("sort", "Owned sort"))
        waitLoaded()
    }
    private fun waitLoaded() { compose.waitUntil(10000) { var ready = false; scenario.onActivity { ready = it.readerModel.state.value.loaded }; ready } }
    @After fun cleanup() {
        scenario.close()
        runBlocking(Dispatchers.IO) { appDb.rssStarDao.delete(origin, "$origin/article"); appDb.rssArticleDao.delete(origin); appDb.rssReadRecordDao.deleteRecordsByOrigin(origin); appDb.rssSourceDao.delete(origin) }
    }
    private fun browser(view: View): WebView? = if (view is WebView) view else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { browser(group.getChildAt(it)) } }
    @Test fun directComposeHostRendersCachedHtmlWithFullMetadataAndSurvivesRecreation() {
        compose.onNodeWithTag("rss-reader-browser").assertIsDisplayed()
        scenario.onActivity { assertEquals("{\"owned\":\"exact\"}", it.readerModel.snapshot()!!.article!!.variable); assertNotNull(browser(it.window.decorView)) }
        scenario.recreate(); waitLoaded()
        scenario.onActivity { assertEquals("Owned sort", it.readerModel.snapshot()!!.article!!.sort); assertTrue(it.supportFragmentManager.fragments.isEmpty()) }
        compose.onNodeWithTag("rss-reader-favorite").assertExists()
    }
    @Test fun favoritePersistsBeforeConfigurationDialogAndRepeatedCallbacksPreserveMetadata() {
        compose.onNodeWithTag("rss-reader-favorite").performClick()
        compose.waitUntil(10000) { var shown = false; scenario.onActivity { shown = it.supportFragmentManager.fragments.any { fragment -> fragment is RssFavoritesDialog } }; shown }
        val favorite = runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, "$origin/article") }
        assertNotNull(favorite); assertEquals("{\"owned\":\"exact\"}", favorite!!.variable)
        scenario.onActivity { it.updateFavorite("Edited", "Group") }
        compose.waitUntil(10000) { var edited = false; scenario.onActivity { edited = it.readerModel.snapshot()?.article?.title == "Edited" }; edited }
        assertEquals("Group", runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, "$origin/article") }!!.group)
    }
    @Test fun pausedNativeReceiptIsDeliveredOnceAfterResume() {
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity { it.readerModel.action(RssReaderAction.ReadRecords); assertNotNull(it.readerModel.state.value.pending) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10000) { var shown = false; scenario.onActivity { shown = it.supportFragmentManager.fragments.any { fragment -> fragment is ReadRecordDialog } }; shown }
        scenario.onActivity { assertNull(it.readerModel.state.value.pending); assertEquals(1, it.supportFragmentManager.fragments.count { fragment -> fragment is ReadRecordDialog }) }
    }
    @Test fun customVideoHidesChromeWithoutDetachingBrowserAndBackRestoresIt() {
        lateinit var web: WebView; var hidden = false
        scenario.onActivity {
            web = browser(it.window.decorView)!!
            it.CustomWebChromeClient().onShowCustomView(FrameLayout(it), WebChromeClient.CustomViewCallback { hidden = true })
        }
        compose.onNodeWithTag("rss-reader-fullscreen").assertIsDisplayed()
        scenario.onActivity { assertSame(web, browser(it.window.decorView)); it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("rss-reader-fullscreen").assertDoesNotExist(); assertTrue(hidden)
        scenario.onActivity { assertSame(web, browser(it.window.decorView)) }
    }
    @Test fun latePickerNonceCannotConsumeCurrentPickerReceipt() {
        scenario.onActivity { activity ->
            val field = ReadRssActivity::class.java.getDeclaredField("pickerNonce").apply { isAccessible = true }
            field.set(activity, "new-picker")
            activity.imagePickerResult("old-picker", "content://old-directory")
            assertEquals("new-picker", field.get(activity))
            activity.imagePickerResult("new-picker", null)
            assertNull(field.get(activity))
        }
    }
    @Test fun missingLegacyPickerValueUsesRestoredSmallReceipt() {
        scenario.onActivity { activity ->
            val field = ReadRssActivity::class.java.getDeclaredField("pickerNonce").apply { isAccessible = true }
            field.set(activity, "restored-picker")
            activity.imagePickerResult(null, null)
            assertNull(field.get(activity))
            activity.imagePickerResult("old-picker", null)
            assertNull(field.get(activity))
        }
    }
    @Test fun largeInlineHtmlMovesIntoPrivateSessionAndRestoresExactRequest() {
        val html = "<body>" + "H".repeat(1500000) + "</body>"
        scenario.onActivity { ReadRssActivity.start(it, true, origin, startHtml = html) }
        compose.waitUntil(timeoutMillis = 10000) { var loaded = false; scenario.onActivity { loaded = it.readerModel.snapshot()?.request?.startHtml == html }; loaded }
        scenario.recreate(); waitLoaded()
        scenario.onActivity { assertEquals(html, it.readerModel.snapshot()!!.request.startHtml) }
    }
    @Test fun closeReleasesBrowserLeaseAndRemovesActivityCallbacks() {
        compose.onNodeWithTag("rss-reader-browser").assertIsDisplayed()
        lateinit var web: WebView
        scenario.onActivity { web = browser(it.window.decorView)!! }
        scenario.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { assertNull(web.parent); assertNull(web.webChromeClient) }
    }
}
