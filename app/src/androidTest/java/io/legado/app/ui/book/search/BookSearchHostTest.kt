package io.legado.app.ui.book.search

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.repository.FileBookSearchDraftRepository
import io.legado.app.model.webBook.BookSearchDraft
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookSearchHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun actualComposeHostRestoresLargeUnsubmittedQueryAndFinishReleasesOnlyItsSession() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = FileBookSearchDraftRepository(context)
            val session = UUID.randomUUID().toString()
            val neighbor = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "book-search-drafts")
            try {
                repository.open(session)
                repository.open(neighbor)
                repository.write(session, BookSearchDraft(revision = 1, navigationSeed = true))
                ActivityScenario.launch<SearchActivity>(
                        BookSearchNavigation.intent(context, session)
                    )
                    .use { scenario ->
                        compose.waitUntil(timeoutMillis = 10000) {
                            var ready = false
                            scenario.onActivity { ready = it.model.state.value.ready }
                            ready
                        }
                        val query = "synthetic-draft".repeat(10000)
                        compose.onNodeWithTag("search-query").performTextReplacement(query)
                        compose.waitUntil(timeoutMillis = 10000) {
                            var edited = false
                            scenario.onActivity {
                                edited = it.model.state.value.draft.query == query
                            }
                            edited
                        }
                        scenario.recreate()
                        compose.onNodeWithTag("search-query").assertTextEquals(query)
                        scenario.onActivity {
                            assertEquals(session, it.model.session)
                            assertFalse(it.model.state.value.searching)
                            assertFalse(it.intent.hasExtra("key"))
                            assertFalse(it.intent.hasExtra("searchScope"))
                            assertEquals(
                                session,
                                it.intent.getStringExtra(BookSearchNavigation.PREPARED_TICKET),
                            )
                            it.finish()
                        }
                        withTimeout(10000) {
                            while (
                                withContext(Dispatchers.IO) {
                                    File(directory, "$session.json").exists()
                                }
                            ) delay(10)
                        }
                        assertTrue(File(directory, "$neighbor.json").exists())
                        assertTrue(File(directory, "$session.json.closed").exists())
                    }
            } finally {
                repository.release(session)
                repository.release(neighbor)
                withContext(Dispatchers.IO) {
                    cleanup(directory, session)
                    cleanup(directory, neighbor)
                }
            }
        }

    @Test
    fun legacyEmptyEntryRemovesCompleteExtrasBeforeSavedStateDefaults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var session: String? = null
        var acceptedInput: String? = null
        try {
            val intent =
                android.content
                    .Intent(context, SearchActivity::class.java)
                    .putExtra("key", "")
                    .putExtra("searchScope", "synthetic-scope".repeat(10000))
            ActivityScenario.launch<SearchActivity>(intent).use { scenario ->
                compose.waitUntil(timeoutMillis = 10000) {
                    var ready = false
                    scenario.onActivity {
                        session = it.model.session
                        acceptedInput = it.model.state.value.draft.acceptedNavigationTicket
                        ready = it.model.state.value.draft.acceptedNavigationTicket != null
                    }
                    ready
                }
                scenario.onActivity {
                    assertFalse(it.intent.hasExtra("key"))
                    assertFalse(it.intent.hasExtra("searchScope"))
                    assertEquals("synthetic-scope".repeat(10000), it.model.state.value.draft.scope)
                    assertFalse(it.model.state.value.searching)
                }
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(session, it.model.session)
                    assertEquals("synthetic-scope".repeat(10000), it.model.state.value.draft.scope)
                    it.finish()
                }
            }
        } finally {
            acceptedInput?.let { id ->
                withTimeout(10000) {
                    while (
                        withContext(Dispatchers.IO) {
                            File(context.filesDir, "book-search-drafts/$id.json").exists()
                        }
                    ) delay(10)
                }
                withContext(Dispatchers.IO) {
                    cleanup(File(context.filesDir, "book-search-drafts"), id)
                }
            }
            session?.let { id ->
                FileBookSearchDraftRepository(context).release(id)
                withContext(Dispatchers.IO) {
                    cleanup(File(context.filesDir, "book-search-drafts"), id)
                }
            }
        }
    }

    private fun cleanup(directory: File, session: String) {
        listOf("json", "json.bak", "json.new", "json.closed", "json.closed.bak", "json.closed.new")
            .forEach { suffix -> File(directory, "$session.$suffix").delete() }
    }
}
