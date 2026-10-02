package io.legado.app.ui.association

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.ui.about.AboutActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AddToBookshelfDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualPendingNetworkSurvivesRotationAndCancellationFinishesWithoutSavingOrNavigating() {
        val id = UUID.randomUUID().toString(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val requests = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet(); entered.countDown(); release.await(15, TimeUnit.SECONDS)
                return newFixedLengthResponse("<h1>Cancelled book $id</h1>")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = BookSource("http://127.0.0.1:${server.listeningPort}", "Link cancel $id", enabled = true,
            ruleBookInfo = BookInfoRule(name = "h1@text"))
        val url = "${source.bookSourceUrl}/book"
        runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity { AddToBookshelfDialog(url, true).show(it.supportFragmentManager, "add-book-link") }
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                compose.onNodeWithTag("add-book-link-progress").assertExists()
                scenario.recreate()
                compose.waitUntil { compose.onAllNodesWithTag("add-book-link-cancel").fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { assertFalse(it.isFinishing) }
                compose.onNodeWithTag("add-book-link-cancel").assertIsEnabled().performClick()
                compose.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
                release.countDown(); assertEquals(1, requests.get())
                assertNull(runBlocking(Dispatchers.IO) { appDb.searchBookDao.getSearchBook(url) })
                assertNull(runBlocking(Dispatchers.IO) { appDb.bookDao.getBook(url) })
            }
        } finally {
            release.countDown(); server.stop()
            runBlocking(Dispatchers.IO) {
                appDb.searchBookDao.getSearchBook(url)?.let { appDb.searchBookDao.delete(it) }
                appDb.bookSourceDao.delete(source)
            }
        }
    }
}
