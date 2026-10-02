package io.legado.app.ui.main.bookshelf.style1.books

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.RoomBookshelfPageRepository
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BookshelfPageRepositoryTest {
    @Test fun realRoomQueryReturnsFreshIndependentBookSnapshots() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val book = Book(bookUrl = "compose-page-${UUID.randomUUID()}", name = "Compose page ${UUID.randomUUID()}", author = "Fixture")
        try {
            appDb.bookDao.insert(book)
            val repository = RoomBookshelfPageRepository(context)
            val snapshot = repository.books(BookGroup.IdAll).first().first { it.bookUrl == book.bookUrl }
            snapshot.name = "UI mutation"
            assertEquals(book.name, appDb.bookDao.getBook(book.bookUrl)?.name)
            book.durChapterTitle = "Updated chapter"
            appDb.bookDao.update(book)
            val next = repository.books(BookGroup.IdAll).first().first { it.bookUrl == book.bookUrl }
            assertEquals("Updated chapter", next.durChapterTitle)
            assertEquals(book.name, next.name)
        } finally { appDb.bookDao.delete(book) }
    }
    @Test fun preferenceFlowRetainsLegacyModeAndOtherBooleanKeys() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.defaultSharedPreferences
        val keys = listOf(PreferKey.bookshelfLayout, PreferKey.bookshelfMargin, PreferKey.showBooknameLayout,
            PreferKey.showUnread, PreferKey.showBookshelfReadProgress, PreferKey.bookshelfReadProgressMode,
            PreferKey.showLastUpdateTime, PreferKey.showBookshelfFastScroller, PreferKey.themeMode)
        val previous = keys.associateWith { preferences.all[it] }
        try {
            preferences.edit().apply { keys.forEach { remove(it) } }.putBoolean(PreferKey.showBookshelfReadProgress, false)
                .putString(PreferKey.showUnread, "false").putInt(PreferKey.bookshelfLayout, 4)
                .putInt(PreferKey.bookshelfMargin, 20).putInt(PreferKey.showBooknameLayout, 2)
                .putBoolean(PreferKey.showLastUpdateTime, true).putBoolean(PreferKey.showBookshelfFastScroller, true)
                .putString(PreferKey.themeMode, "3").commit()
            val repository = RoomBookshelfPageRepository(context)
            val settings = repository.settings().first()
            assertEquals(4, settings.layout); assertEquals(20, settings.marginPx); assertEquals(2, settings.gridTitle)
            assertEquals(0, settings.readProgressMode); assertFalse(settings.showUnread)
            assertTrue(settings.showLatestUpdate); assertTrue(settings.fastScroller); assertTrue(settings.eInk)
            preferences.edit().putInt(PreferKey.bookshelfReadProgressMode, 2).commit()
            assertEquals(2, repository.settings().first().readProgressMode)
        } finally {
            preferences.edit().apply { previous.forEach { (key, value) ->
                when (value) { null -> remove(key); is Int -> putInt(key, value); is Boolean -> putBoolean(key, value); is String -> putString(key, value) }
            } }.commit()
        }
    }
}
