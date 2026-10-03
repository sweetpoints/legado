package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AppAudioSkipCreditsStoreTest {
    private lateinit var db: AppDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    private class Preferences : AudioSkipCreditsPreferences {
        var values = 31 to 41
        override fun read() = values
        override fun write(opening: Int, closing: Int) { values = opening to closing }
    }
    @Test fun restoringFromRoomAndSavingAudioFieldsPreserveLatestCoverProgressAndUnrelatedConfig() = runBlocking {
        val original = Book(bookUrl = "https://book", origin = "https://source", name = "Book", author = "Author", customCoverUrl = "cover")
        original.config.apply { useGlobalAudioSkip = true; openCredits = 5; closeCredits = 6; playMode = 2 }
        withContext(Dispatchers.IO) { db.bookDao.insert(original) }
        val prefs = Preferences(); val store = AppAudioSkipCreditsStore(original.bookUrl, database = db, preferences = prefs,
            save = { db.bookDao.updatePreservingCustomCoverUrl(it) })
        val repo = DefaultAudioSkipCreditsRepository(store, gate = AudioSkipCreditsWriteGate())
        val restored = repo.load(); assertEquals(AudioSkipCreditsDraft(true, 5, 6, 31, 41), restored)
        withContext(Dispatchers.IO) {
            db.bookDao.insert(original.copy(customCoverUrl = "new-cover", durChapterIndex = 9, durChapterPos = 12,
                readConfig = original.config.copy(playMode = 3)))
        }
        repo.write(restored.scope(false).opening(18), 1, false, true)
        val saved = withContext(Dispatchers.IO) { db.bookDao.getBook(original.bookUrl)!! }
        assertEquals("new-cover", saved.customCoverUrl); assertEquals(9, saved.durChapterIndex); assertEquals(12, saved.durChapterPos)
        assertEquals(3, saved.config.playMode); assertFalse(saved.config.useGlobalAudioSkip)
        assertEquals(18, saved.config.openCredits); assertEquals(41, saved.config.closeCredits); assertEquals(31 to 41, prefs.values)
    }
    @Test fun currentBookSeedWinsOnFirstOpenAndProcessRestoreUsesPersistedRoomValues() = runBlocking {
        val book = Book(bookUrl = "https://seed", name = "Book", author = "Author")
        book.config.apply { useGlobalAudioSkip = false; openCredits = 8; closeCredits = 9 }
        withContext(Dispatchers.IO) { db.bookDao.insert(book) }
        val live = book.copy(readConfig = book.config.copy(openCredits = 50))
        val prefs = Preferences()
        val current = DefaultAudioSkipCreditsRepository(AppAudioSkipCreditsStore(book.bookUrl, live, db, prefs,
            save = { db.bookDao.updatePreservingCustomCoverUrl(it) }), gate = AudioSkipCreditsWriteGate())
        assertEquals(50, current.load().bookOpen)
        current.write(current.load().closing(22), 1, false, true)
        val restored = DefaultAudioSkipCreditsRepository(AppAudioSkipCreditsStore(book.bookUrl, database = db, preferences = prefs), gate = AudioSkipCreditsWriteGate())
        assertEquals(50, restored.load().bookOpen); assertEquals(22, restored.load().bookClose)
    }
}
