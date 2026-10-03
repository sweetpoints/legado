package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.model.remote.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RemoteLibraryReadingRoomTest {
    @Test fun actualOriginFilenameLookupAndFreshBookSnapshotPreserveRoomMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = DefaultRemoteLibraryReadingRepository(AppRemoteLibraryReadingStore(context, database))
            val book = Book(bookUrl = "synthetic-read", name = "Original", originName = "download.txt", durChapterIndex = 12, durChapterPos = 34, group = 8)
            withContext(Dispatchers.IO) { database.bookDao.insert(book) }
            assertEquals(RemoteLibraryReadTarget.Open(book.bookUrl), repository.prepare(RemoteLibraryEntry("remote", "download.txt", "https://example.invalid/download.txt", 1, 2, "txt", true)))
            val snapshot = repository.readBook(book.bookUrl)!!
            withContext(Dispatchers.IO) { database.bookDao.insert(book.copy(name = "Latest", durChapterIndex = 20)) }
            assertEquals("Original", snapshot.name); assertEquals(12, snapshot.durChapterIndex)
            val latest = repository.readBook(book.bookUrl)!!; assertEquals("Latest", latest.name); assertEquals(20, latest.durChapterIndex); assertEquals(34, latest.durChapterPos); assertEquals(8L, latest.group)
            assertEquals(RemoteLibraryReadTarget.None, repository.prepare(RemoteLibraryEntry("missing", "missing.txt", "missing", 1, 2, "txt", true)))
        } finally { database.close() }
    }
}
