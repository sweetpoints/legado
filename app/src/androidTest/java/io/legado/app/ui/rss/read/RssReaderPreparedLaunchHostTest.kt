package io.legado.app.ui.rss.read

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import io.legado.app.model.rss.rssReaderImageOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class RssReaderPreparedLaunchHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val origin = "https://prepared-reader-${UUID.randomUUID()}.invalid"
    private val launches = FileRssReaderLaunchRepository()
    @Before fun before() { runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(RssSource(sourceUrl = origin, sourceName = "Owned", enableJs = true)) } }
    @After fun after() { runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(origin) } }
    private fun await(scenario: ActivityScenario<ReadRssActivity>, request: RssReaderRequest) {
        compose.waitUntil(timeoutMillis = 20_000) { var loaded = false; scenario.onActivity { loaded = it.readerModel.snapshot()?.request == request && it.readerModel.state.value.loaded }; loaded }
    }
    @Test fun publicSmallTicketCarriesLargeHtmlAndTitleAndSurvivesRecreateWithoutLaunchFile() {
        val request = RssReaderRequest(origin, "T".repeat(1100000), startHtml = "<body>" + "H".repeat(2000000) + "</body>")
        val ticket = runBlocking(Dispatchers.IO) { launches.stage(request) }
        try {
            ActivityScenario.launch<ReadRssActivity>(Intent(context, ReadRssActivity::class.java)).use { scenario ->
                scenario.onActivity { ReadRssActivity.startPrepared(it, ticket) }; await(scenario, request)
                scenario.onActivity {
                    assertEquals(setOf(ReadRssActivity.PREPARED_REQUEST), it.intent.extras!!.keySet())
                    assertEquals(ticket, it.intent.getStringExtra(ReadRssActivity.PREPARED_REQUEST))
                    val field = RssReaderViewModel::class.java.getDeclaredField("saved").apply { isAccessible = true }
                    val saved = field.get(it.readerModel) as SavedStateHandle
                    assertTrue(saved.keys().all { key -> saved.get<Any?>(key).toString().length < 100 })
                }
                assertNull(runBlocking(Dispatchers.IO) { launches.read(ticket) })
                scenario.recreate(); await(scenario, request)
                scenario.onActivity { assertEquals(request, it.readerModel.snapshot()!!.request) }
            }
        } finally { runBlocking(Dispatchers.IO) { launches.release(ticket) } }
    }
    @Test fun repeatedPublicTicketAndPauseResumePreserveTheAcceptedRequestAndBrowserDocument() {
        val request = RssReaderRequest(origin, "Exact title", startHtml = "<body id='owned'>Owned body</body>")
        val ticket = runBlocking(Dispatchers.IO) { launches.stage(request) }
        try {
            ActivityScenario.launch<ReadRssActivity>(Intent(context, ReadRssActivity::class.java).putExtra(ReadRssActivity.PREPARED_REQUEST, ticket)).use { scenario ->
                await(scenario, request)
                scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED); await(scenario, request)
                scenario.onActivity { ReadRssActivity.startPrepared(it, ticket) }; await(scenario, request)
                assertNull(runBlocking(Dispatchers.IO) { launches.read(ticket) })
                scenario.recreate(); await(scenario, request)
                scenario.onActivity { assertEquals("Exact title", it.readerModel.state.value.title) }
            }
        } finally { runBlocking(Dispatchers.IO) { launches.release(ticket) } }
    }
    @Test fun latestPreparedRequestOwnsImageStateAcrossRecreation() {
        val first = RssReaderRequest(origin, "First", startHtml = "<body>First body</body>")
        val second = first.copy(title = "Second", startHtml = "<body>Second body</body>")
        val firstTicket = runBlocking(Dispatchers.IO) { launches.stage(first) }
        val secondTicket = runBlocking(Dispatchers.IO) { launches.stage(second) }
        try {
            ActivityScenario.launch<ReadRssActivity>(Intent(context, ReadRssActivity::class.java).putExtra(ReadRssActivity.PREPARED_REQUEST, firstTicket)).use { scenario ->
                await(scenario, first)
                scenario.onActivity { ReadRssActivity.startPrepared(it, secondTicket) }; await(scenario, second)
                compose.waitUntil(timeoutMillis = 10_000) {
                    var current = false
                    scenario.onActivity {
                        val field = ReadRssActivity::class.java.getDeclaredField("imageOwnerHash").apply { isAccessible = true }
                        current = field.get(it) == rssReaderImageOwner(second)
                    }; current
                }
                scenario.recreate(); await(scenario, second)
                scenario.onActivity { assertEquals(second, it.readerModel.snapshot()!!.request) }
            }
        } finally { runBlocking(Dispatchers.IO) { launches.release(firstTicket); launches.release(secondTicket) } }
    }
}
