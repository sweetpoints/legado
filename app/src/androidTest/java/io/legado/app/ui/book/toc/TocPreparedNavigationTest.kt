package io.legado.app.ui.book.toc

import android.content.Context
import android.os.Parcel
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.FileTocHostSessionRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TocPreparedNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<TocActivity>? = null
    private val name = "PreparedToc-${UUID.randomUUID()}"
    private val book =
        Book(
            bookUrl = "fixture://$name/" + "segment/".repeat(90000),
            name = name,
            author = "Author",
            totalChapterNum = 1,
        )
    private val chapter =
        BookChapter(bookUrl = book.bookUrl, url = "chapter", index = 0, title = "First")

    @After
    fun cleanup() {
        scenario?.close()
        runBlocking(Dispatchers.IO) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
        }
    }

    @Test
    fun largePreparedIdentityCrossesOnlyAsUuidAndRestoresWithoutLegacyIntent() {
        runBlocking(Dispatchers.IO) {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(chapter)
        }
        val ticket = runBlocking { TocNavigation.prepare(context, book.bookUrl) }
        val intent = PreparedTocActivityResult().createIntent(context, ticket)
        assertEquals(setOf(TocNavigation.PREPARED_SESSION), intent.extras!!.keySet())
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            assertTrue(parcel.dataSize() < 4096)
        } finally {
            parcel.recycle()
        }
        val active = ActivityScenario.launch<TocActivity>(intent).also { scenario = it }
        waitForLoaded(active)
        active.onActivity {
            assertEquals(book.bookUrl, it.sessionModel.state.value.bookUrl)
            assertFalse(it.intent.hasExtra("bookUrl"))
            assertFalse(it.intent.hasExtra(TocNavigation.PREPARED_SESSION))
            it.sessionModel.query("First")
            it.sessionModel.tab(2)
        }
        // An old caller must not abandon the now-owned host session after Android handoff.
        runBlocking { TocNavigation.abandon(context, ticket) }
        assertNotNull(runBlocking { repository().read(ticket) })
        active.recreate()
        waitForLoaded(active)
        active.onActivity {
            assertEquals(book.bookUrl, it.sessionModel.state.value.bookUrl)
            assertEquals("First", it.sessionModel.state.value.query)
            assertEquals(2, it.sessionModel.state.value.tab)
        }
    }

    @Test
    fun cancellingAnUnusedPreparedIdentityReleasesOnlyItsPrivateSession() {
        val first = runBlocking { TocNavigation.prepare(context, "first") }
        val second = runBlocking { TocNavigation.prepare(context, "second") }
        try {
            runBlocking { TocNavigation.abandon(context, first) }
            assertNull(runBlocking { repository().read(first) })
            assertEquals("second", runBlocking { repository().read(second) }!!.bookUrl)
            assertTrue(runCatching { TocNavigation.intent(context, "../escape") }.isFailure)
        } finally {
            runBlocking { TocNavigation.abandon(context, second) }
        }
    }

    private fun repository() =
        FileTocHostSessionRepository(File(context.filesDir, "toc-host-state"))

    private fun waitForLoaded(active: ActivityScenario<TocActivity>) {
        compose.waitUntil(timeoutMillis = 10000) {
            var loaded = false
            active.onActivity {
                loaded = it.sessionModel.state.value.ready && it.chapterModel.state.value.loaded
            }
            loaded
        }
    }
}
