package io.legado.app.ui.book.manage

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BookshelfManagementHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun actualActivityRestoresLargePrivateQueryAndSelectionWithoutWritingBookAndNativeBackClosesOwnedSession() = runBlocking {
        val token = UUID.randomUUID().toString(); val book = Book(bookUrl = "shelf-host-$token-" + "long".repeat(2000), name = "Shelf $token", author = "Synthetic")
        var session: String? = null
        withContext(Dispatchers.IO) { appDb.bookDao.insert(book) }
        try {
            ActivityScenario.launch<BookshelfManageActivity>(Intent(context, BookshelfManageActivity::class.java)).use { scenario ->
                compose.waitUntil(timeoutMillis = 10000) { var ready = false; scenario.onActivity { ready = !it.model.state.value.loading }; ready }
                scenario.onActivity { session = it.model.session; it.model.query(token) }
                compose.waitUntil(timeoutMillis = 10000) { var ready = false; scenario.onActivity { ready = it.model.state.value.snapshot?.books?.singleOrNull()?.id == book.bookUrl }; ready }
                compose.onNodeWithTag("shelf-manage-selected-${book.bookUrl}").performClick()
                scenario.onActivity { assertEquals(listOf(book.bookUrl), it.model.state.value.visibleSelection) }
                scenario.recreate()
                compose.waitUntil(timeoutMillis = 10000) { var ready = false; scenario.onActivity { ready = !it.model.state.value.loading }; ready }
                compose.onNodeWithTag("shelf-manage-search").assertTextContains(token)
                scenario.onActivity { assertEquals(session, it.model.session); assertEquals(listOf(book.bookUrl), it.model.state.value.visibleSelection); it.onBackPressedDispatcher.onBackPressed() }
            }
            withTimeout(10000) { while (withContext(Dispatchers.IO) { File(context.filesDir, "bookshelf-management-drafts/$session.json").exists() }) delay(10) }
            assertEquals(book.name, withContext(Dispatchers.IO) { appDb.bookDao.getBook(book.bookUrl)!!.name })
        } finally {
            withContext(Dispatchers.IO) {
                appDb.bookDao.delete(book)
                session?.let { id -> listOf("json", "json.bak", "json.new", "json.closed", "json.closed.bak", "json.closed.new").forEach { suffix -> File(context.filesDir, "bookshelf-management-drafts/$id.$suffix").delete() } }
            }
        }
    }
}
