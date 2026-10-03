package io.legado.app.data.association

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.commitBookshelfImport
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationBookshelfAcceptanceTest {
    @Test
    fun failedAcceptedObserverRollsBackActualRoomInsert() = runBlocking {
        val book =
            Book(bookUrl = "acceptance-${UUID.randomUUID()}", name = "Book ${UUID.randomUUID()}")
        try {
            withContext(Dispatchers.IO) {
                assertTrue(
                    runCatching {
                        commitBookshelfImport(book, book.name, book.author) { _, _, _ ->
                            throw IOException("private receipt unavailable")
                        }
                    }
                        .isFailure
                )
                assertFalse(appDb.bookDao.has(book.bookUrl))
            }
        } finally {
            withContext(Dispatchers.IO) { appDb.bookDao.delete(book) }
        }
    }

    @Test
    fun cancelDuringAcceptedObserverCompletesRealRoomAndReceiptWithoutDeliveringNativeUi() =
        runBlocking {
            val book =
                Book(
                    bookUrl = "acceptance-${UUID.randomUUID()}",
                    name = "Book ${UUID.randomUUID()}",
                )
            val entered = CompletableDeferred<Unit>()
            val returnGate = CompletableDeferred<Unit>()
            val recorded = CompletableDeferred<String>()
            var delivered = false
            val delivery =
                launch(Dispatchers.Main) {
                    withContext(Dispatchers.IO) {
                        commitBookshelfImport(book, book.name, book.author) { name, author, url ->
                            assertEquals(book.name, name)
                            assertEquals(book.author, author)
                            entered.complete(Unit)
                            returnGate.await()
                            recorded.complete(url)
                        }
                    }
                    delivered = true
                }
            try {
                withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
                delivery.cancel()
                returnGate.complete(Unit)
                delivery.join()
                assertFalse(delivered)
                assertEquals(book.bookUrl, recorded.await())
                withContext(Dispatchers.IO) {
                    assertEquals(book, appDb.bookDao.getBook(book.bookUrl))
                }
            } finally {
                returnGate.complete(Unit)
                delivery.cancelAndJoin()
                withContext(Dispatchers.IO) { appDb.bookDao.delete(book) }
            }
        }
}
