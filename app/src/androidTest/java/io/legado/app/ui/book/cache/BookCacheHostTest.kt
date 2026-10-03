package io.legado.app.ui.book.cache

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class BookCacheHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun actualGroupIntentAndRoomUpdatesSurviveRecreationAndBackDoesNotAlterBook() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "cache-host-${UUID.randomUUID()}"
        val group =
            runBlocking(Dispatchers.IO) {
                (0..62).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null }
            }
        runBlocking(Dispatchers.IO) {
            appDb.bookGroupDao.insert(BookGroup(group, "Cache fixture"))
            appDb.bookDao.insert(
                Book(
                    bookUrl = key,
                    name = "Original cache fixture",
                    group = group,
                    totalChapterNum = 20,
                    durChapterIndex = 5,
                )
            )
        }
        try {
            ActivityScenario.launch<CacheActivity>(
                    Intent(context, CacheActivity::class.java).putExtra("groupId", group)
                )
                .use { scenario ->
                    compose.waitUntil {
                        var ready = false
                        scenario.onActivity {
                            ready =
                                !it.viewModel.state.value.loading &&
                                    it.viewModel.state.value.rows.any { row -> row.book.key == key }
                        }
                        ready
                    }
                    compose.onNodeWithText("Original cache fixture").assertExists()
                    runBlocking(Dispatchers.IO) {
                        val row = appDb.bookDao.getBook(key)!!
                        row.name = "Updated cache fixture"
                        appDb.bookDao.insert(row)
                    }
                    compose.waitUntil {
                        var updated = false
                        scenario.onActivity {
                            updated =
                                it.viewModel.state.value.rows.any { row ->
                                    row.book.name == "Updated cache fixture"
                                }
                        }
                        updated
                    }
                    scenario.recreate()
                    compose.onNodeWithText("Updated cache fixture").assertExists()
                    scenario.onActivity { assertEquals(group, it.viewModel.state.value.group) }
                    compose.onNodeWithTag("book-cache-back").performClick()
                }
            runBlocking(Dispatchers.IO) {
                val row = appDb.bookDao.getBook(key)!!
                assertEquals(5, row.durChapterIndex)
                assertEquals(20, row.totalChapterNum)
            }
        } finally {
            runBlocking(Dispatchers.IO) {
                appDb.bookDao.getBook(key)?.let { appDb.bookDao.delete(it) }
                appDb.bookGroupDao.getByID(group)?.let { appDb.bookGroupDao.delete(it) }
            }
        }
    }
}
