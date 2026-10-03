package io.legado.app.ui.book.info

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.repository.BookDetailEntryRepository
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.FileBookDetailSessionRepository
import io.legado.app.data.repository.RoomBookDetailNetworkStorageRepository
import io.legado.app.data.repository.RoomBookDetailRepository
import io.legado.app.data.repository.RoomBookDetailStorageRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoPreparedEntryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun largeIdentityIsDurableWhileIntentContainsOnlyPreparedTicket() = runBlocking {
        val identity =
            BookDetailIdentity(
                "name".repeat(100_000),
                "author".repeat(100_000),
                "https://book/" + "url".repeat(300_000),
            )
        val ticket = BookInfoNavigation.prepare(context, identity)
        val sessions = sessions(File(context.filesDir, "book-detail-sessions"))
        try {
            val intent = BookInfoNavigation.intent(context, ticket)
            assertEquals(setOf(BookInfoNavigation.PREPARED_TICKET), intent.extras!!.keySet())
            assertEquals(ticket, intent.getStringExtra(BookInfoNavigation.PREPARED_TICKET))
            assertFalse(intent.hasExtra("name"))
            assertFalse(intent.hasExtra("author"))
            assertFalse(intent.hasExtra("bookUrl"))
            assertEquals(identity, sessions.read(ticket)!!.identity)
        } finally {
            BookInfoNavigation.abandon(context, ticket)
            removeClosedMarker(ticket)
        }
    }

    @Test
    fun cancelledActualIoPrepareCleansItsAcceptedWriteAndPreservesNeighbour() = runBlocking {
        val directory = File(context.cacheDir, "book-info-entry-${UUID.randomUUID()}")
        val entered = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val normal = sessions(directory)
        val neighbour =
            BookDetailEntryRepository(normal).prepare(BookDetailIdentity(bookUrl = "neighbour"))
        val blocked =
            sessions(directory) {
                entered.countDown()
                check(finishWrite.await(5, TimeUnit.SECONDS))
            }
        val preparation =
            async(Dispatchers.Default) {
                BookDetailEntryRepository(blocked)
                    .prepare(BookDetailIdentity(bookUrl = "cancelled"))
            }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            preparation.cancel()
            finishWrite.countDown()
            preparation.cancelAndJoin()
            assertEquals("neighbour", normal.read(neighbour)!!.identity.bookUrl)
            val files = directory.listFiles()!!.map { it.name }
            assertEquals(listOf("$neighbour.json"), files.filter { it.endsWith(".json") })
            assertEquals(1, files.count { it.endsWith(".closed") })
            assertFalse(files.any { it.endsWith(".new") || it.endsWith(".bak") })
        } finally {
            finishWrite.countDown()
            preparation.cancelAndJoin()
            normal.release(neighbour)
            directory.deleteRecursively()
        }
    }

    private fun sessions(directory: File, beforeWrite: () -> Unit = {}) =
        FileBookDetailSessionRepository(
            context,
            RoomBookDetailStorageRepository(),
            RoomBookDetailRepository(),
            RoomBookDetailNetworkStorageRepository(),
            directory,
            { beforeWrite() },
        )

    private fun removeClosedMarker(ticket: String) {
        for (suffix in listOf("closed", "closed.bak", "closed.new")) {
            File(context.filesDir, "book-detail-sessions/$ticket.$suffix").delete()
        }
    }
}
