package io.legado.app.ui.rss.article

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.repository.rssReadRecordKey
import io.legado.app.ui.about.AboutActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class RssReadRecordRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val origin = "restore-${UUID.randomUUID()}"
    private val otherOrigin = "other-${UUID.randomUUID()}"
    @After fun cleanup() = runBlocking(Dispatchers.IO) { appDb.rssReadRecordDao.deleteRecordsByOrigin(origin); appDb.rssReadRecordDao.deleteRecordsByOrigin(otherOrigin) }
    private fun await(scenario: ActivityScenario<AboutActivity>) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity -> ready = (activity.supportFragmentManager.findFragmentByTag("history-test") as? ReadRecordDialog)?.model?.state?.value?.loading == false }
            if (ready) return; SystemClock.sleep(25)
        }
        throw AssertionError("RSS history did not restore")
    }
    @Test fun realFragmentRecreationKeepsSourceFilterAndLazyListScrollUntilRoomDataLoads() {
        val records = (1..40).map { RssReadRecord("https://$origin/$it", "Article $it", (41 - it).toLong(), origin = origin) }
        runBlocking(Dispatchers.IO) { appDb.rssReadRecordDao.insertRecord(*records.toTypedArray(), RssReadRecord("https://$otherOrigin", "Excluded", 100, origin = otherOrigin)) }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> ReadRecordDialog(origin).showNow(activity.supportFragmentManager, "history-test") }
            await(scenario); val tag = "rss-history-read-${rssReadRecordKey(records.last().record)}"
            compose.onNodeWithTag("rss-history-list").performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).assertIsDisplayed()
            scenario.recreate(); await(scenario); compose.onNodeWithTag(tag).assertIsDisplayed(); compose.onNodeWithText("Excluded").assertDoesNotExist()
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("history-test") as ReadRecordDialog
                assertEquals(origin, dialog.requireArguments().getString("origin")); assertEquals(40, dialog.model.state.value.items.size)
                assertTrue(dialog.model.state.value.items.all { it.origin == origin }); assertFalse(activity.isFinishing)
            }
        }
    }
    @Test fun restoredClearConfirmationRecountsNewRowsAndCancelLeavesActualDatabaseUntouched() {
        runBlocking(Dispatchers.IO) { appDb.rssReadRecordDao.insertRecord(RssReadRecord("https://$origin/one", "One", 1, origin = origin)) }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> ReadRecordDialog(origin).showNow(activity.supportFragmentManager, "history-test") }; await(scenario)
            compose.onNodeWithTag("rss-history-clear").performClick(); compose.onNodeWithTag("rss-history-clear-count").assertTextContains("1")
            runBlocking(Dispatchers.IO) { appDb.rssReadRecordDao.insertRecord(RssReadRecord("https://$origin/two", "Two", 2, origin = origin)) }
            scenario.recreate(); await(scenario)
            compose.waitUntil(5000) {
                var fresh = false; scenario.onActivity { activity -> fresh = (activity.supportFragmentManager.findFragmentByTag("history-test") as ReadRecordDialog).model.state.value.clearCount == 2 }; fresh
            }
            compose.onNodeWithTag("rss-history-clear-count").assertTextContains("2")
            compose.onNodeWithTag("rss-history-clear-cancel").performClick()
            runBlocking(Dispatchers.IO) { assertEquals(2, appDb.rssReadRecordDao.countRecordsByOrigin(origin)) }
        }
    }
    @Test fun emptySourceArgumentSurvivesRealRecreationWithoutBecomingGlobalFilter() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> ReadRecordDialog("").showNow(activity.supportFragmentManager, "history-test") }; await(scenario)
            scenario.recreate(); await(scenario)
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("history-test") as ReadRecordDialog
                assertTrue(dialog.requireArguments().containsKey("origin")); assertEquals("", dialog.requireArguments().getString("origin"))
                assertTrue(dialog.model.state.value.items.all { it.origin == "" })
            }
        }
    }
}
