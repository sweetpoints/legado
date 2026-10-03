package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailChildCallbackRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun completedSourceCallbackIsClaimedOnceAcrossRepositoryRecreation() = runBlocking {
        val directory = File(context.cacheDir, "book-detail-callback-${UUID.randomUUID()}")
        val ticket = UUID.randomUUID().toString()
        val owner =
            BookDetailChildOwner(
                UUID.randomUUID().toString(),
                BookDetailChildKind.Source,
                "book",
                "source",
            )
        fun repository() = FileBookDetailChildRepository(context, directory)
        try {
            repository().owner(ticket, owner)
            assertFalse(repository().claimCallback(ticket, owner.token))
            repository().result(ticket, BookDetailChildResult(owner))
            repository().complete(ticket, owner.token)
            assertTrue(repository().claimCallback(ticket, owner.token))
            assertFalse(repository().claimCallback(ticket, owner.token))
            assertEquals(listOf(owner.token), repository().read(ticket).deliveredCallbacks)
        } finally {
            repository().release(ticket)
            directory.deleteRecursively()
        }
    }

    @Test
    fun ledgerWrittenBeforeCallbackReceiptsCanBeClaimedAndRestored() = runBlocking {
        val directory = File(context.cacheDir, "book-detail-callback-${UUID.randomUUID()}")
        val ticket = UUID.randomUUID().toString()
        val owner =
            BookDetailChildOwner(
                UUID.randomUUID().toString(),
                BookDetailChildKind.Source,
                "book",
                "source",
            )
        val repository = FileBookDetailChildRepository(context, directory)
        try {
            repository.owner(ticket, owner)
            repository.result(ticket, BookDetailChildResult(owner))
            repository.complete(ticket, owner.token)
            val file = File(directory, "$ticket.json")
            val oldDocument = JsonParser.parseString(file.readText()).asJsonObject
            oldDocument.remove("deliveredCallbacks")
            file.writeText(oldDocument.toString())
            assertTrue(repository.read(ticket).deliveredCallbacks.isEmpty())
            assertTrue(repository.claimCallback(ticket, owner.token))
            assertFalse(
                FileBookDetailChildRepository(context, directory).claimCallback(ticket, owner.token)
            )
        } finally {
            repository.release(ticket)
            directory.deleteRecursively()
        }
    }

    @Test
    fun pausedOwnerRollbackPermitsResumeButReleaseFencesLateClaimAndRollback() = runBlocking {
        val directory = File(context.cacheDir, "book-detail-callback-${UUID.randomUUID()}")
        val ticket = UUID.randomUUID().toString()
        val owner =
            BookDetailChildOwner(
                UUID.randomUUID().toString(),
                BookDetailChildKind.Source,
                "book",
                "source",
            )
        val repository = FileBookDetailChildRepository(context, directory)
        try {
            repository.owner(ticket, owner)
            repository.result(ticket, BookDetailChildResult(owner))
            repository.complete(ticket, owner.token)
            assertTrue(repository.claimCallback(ticket, owner.token))
            repository.rollbackCallback(ticket, owner.token)
            assertTrue(
                FileBookDetailChildRepository(context, directory).claimCallback(ticket, owner.token)
            )
            repository.release(ticket)
            assertFalse(repository.claimCallback(ticket, owner.token))
            repository.rollbackCallback(ticket, owner.token)
            assertTrue(
                listOf("json", "json.bak", "json.new").none {
                    File(directory, "$ticket.$it").exists()
                }
            )
        } finally {
            repository.release(ticket)
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedClaimWritePreservesCompletedSourceReceiptForExplicitRetry() = runBlocking {
        val directory = File(context.cacheDir, "book-detail-callback-${UUID.randomUUID()}")
        val ticket = UUID.randomUUID().toString()
        val owner =
            BookDetailChildOwner(
                UUID.randomUUID().toString(),
                BookDetailChildKind.Source,
                "book",
                "source",
            )
        val repository = FileBookDetailChildRepository(context, directory)
        try {
            repository.owner(ticket, owner)
            repository.result(ticket, BookDetailChildResult(owner))
            repository.complete(ticket, owner.token)
            val failing =
                FileBookDetailChildRepository(context, directory) { value ->
                    if (owner.token in value.deliveredCallbacks)
                        error("callback receipt write failed")
                }
            assertTrue(runCatching { failing.claimCallback(ticket, owner.token) }.isFailure)
            assertTrue(repository.read(ticket).deliveredCallbacks.isEmpty())
            assertEquals(listOf(owner.token), repository.read(ticket).completed)
            assertTrue(repository.claimCallback(ticket, owner.token))
        } finally {
            repository.release(ticket)
            directory.deleteRecursively()
        }
    }
}
