package io.legado.app.ui.book.import.local

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportAtomicSessionTest {
    @Test
    fun claimedLargeNavigationRestoresPrivatelyAndClosedSessionRejectsLateWriters() = runBlocking {
        val directory =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                UUID.randomUUID().toString(),
            )
        try {
            val ticket = UUID.randomUUID().toString()
            val first = FileLocalImportSessions(directory)
            val pending =
                LocalImportNative(
                    UUID.randomUUID().toString(),
                    LocalImportNativeKind.Read,
                    book = Book(bookUrl = "https://local/" + "large/".repeat(100000)),
                    claimed = true,
                )
            val accepted =
                LocalImportCheckpoint(
                    root = "content://large/" + "path/".repeat(100000),
                    pending = pending,
                )
            assertTrue(first.write(ticket, accepted))
            assertFalse(
                FileLocalImportSessions(directory).write(ticket, accepted.copy(root = "late"))
            )
            val body = File(directory, "$ticket.json")
            assertTrue(body.renameTo(File(directory, "$ticket.json.bak")))
            assertEquals(
                accepted,
                LocalImportSession(ticket, FileLocalImportSessions(directory), false).load(),
            )
            first.close(ticket)
            assertTrue(runCatching { first.write(ticket, accepted.copy(revision = 1)) }.isFailure)
            assertEquals(null, first.read(ticket))
        } finally {
            directory.deleteRecursively()
        }
    }
}
