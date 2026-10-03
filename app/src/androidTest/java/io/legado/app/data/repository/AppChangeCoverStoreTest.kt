package io.legado.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.SearchRule
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AppChangeCoverStoreTest {
    @Test
    fun onlyFirstStrictMatchWithCoverIsCachedAndCacheExcludesDisabledSources() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val source =
                BookSource(
                    bookSourceUrl = "https://source.invalid",
                    bookSourceName = "Source",
                    ruleSearch = SearchRule(coverUrl = "cover"),
                    customOrder = 5,
                )
            withContext(Dispatchers.IO) { db.bookSourceDao.insert(source) }
            val target = ChangeCoverTarget("Name", "Author")
            var results = listOf<SearchBook>()
            var calls = 0
            val store =
                AppChangeCoverStore(context, db) { _, key ->
                    assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                    assertEquals(target.name, key)
                    calls++
                    results
                }
            fun book(id: String) =
                SearchBook(
                    bookUrl = id,
                    origin = source.bookSourceUrl,
                    originName = "Source",
                    name = "Name",
                    author = "Author",
                    coverUrl = "cover:$id",
                )
            for (first in
                listOf(
                    book("wrong-name").copy(name = "Other"),
                    book("wrong-author").copy(author = "Other"),
                    book("blank").copy(coverUrl = ""),
                )) {
                results = listOf(first, book("valid-second"))
                assertNull(
                    withContext(Dispatchers.IO) { store.search(target, source.bookSourceUrl) }
                )
                assertTrue(withContext(Dispatchers.IO) { store.cached(target) }.isEmpty())
            }
            results = listOf(book("valid"))
            val selected =
                withContext(Dispatchers.IO) { store.search(target, source.bookSourceUrl) }
            assertEquals("book:valid", selected!!.id)
            assertEquals(
                "cover:valid",
                withContext(Dispatchers.IO) { store.cached(target) }.single().coverUrl,
            )
            withContext(Dispatchers.IO) { db.bookSourceDao.insert(source.copy(enabled = false)) }
            assertTrue(withContext(Dispatchers.IO) { store.cached(target) }.isEmpty())
            assertTrue(withContext(Dispatchers.IO) { store.sources() }.isEmpty())
            withContext(Dispatchers.IO) {
                db.bookSourceDao.insert(source.copy(ruleSearch = SearchRule()))
            }
            assertNull(withContext(Dispatchers.IO) { store.search(target, source.bookSourceUrl) })
            assertEquals(4, calls)
        } finally {
            db.close()
        }
    }

    @Test
    fun diskSessionRoundTripsLargeGridAndOldWriterCannotOverwriteNewerRevisionAcrossInstances() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val session = UUID.randomUUID().toString()
            val file = java.io.File(context.filesDir, "change-cover-sessions/$session.json")
            try {
                val store = AppChangeCoverStore(context)
                val other = AppChangeCoverStore(context)
                val snapshot =
                    ChangeCoverSnapshot(
                        ChangeCoverTarget("Name", "Author"),
                        (1..1000).map {
                            ChangeCoverItem(
                                "book:$it",
                                "source:$it",
                                "Source $it",
                                "https://cover.invalid/$it",
                                it,
                            )
                        },
                        (1..1000).map { "source:$it" },
                        ChangeCoverStatus.Running,
                        20,
                    )
                store.write(session, snapshot)
                other.write(session, snapshot.copy(revision = 19, covers = emptyList()))
                assertEquals(snapshot, other.read(session))
                assertEquals(
                    "https://cover.invalid/999",
                    DefaultChangeCoverRepository(other).selected(session, "book:999"),
                )
                coroutineScope {
                    (21L..30L)
                        .map { revision ->
                            async(Dispatchers.IO) {
                                (if (revision % 2 == 0L) store else other).write(
                                    session,
                                    snapshot.copy(revision = revision),
                                )
                            }
                        }
                        .awaitAll()
                }
                assertEquals(30L, store.read(session)!!.revision)
            } finally {
                file.delete()
                java.io.File(file.path + ".bak").delete()
                java.io.File(file.path + ".new").delete()
            }
        }
}
