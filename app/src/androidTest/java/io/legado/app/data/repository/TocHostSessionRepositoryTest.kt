package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class TocHostSessionRepositoryTest {
    private val directory =
        File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "toc-host-fixture-${UUID.randomUUID()}",
        )

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun fullLargeQueryAndUrlRestoreFromActualOwnedAtomicFile() = runBlocking {
        val session = UUID.randomUUID().toString()
        val value = TocHostSession("URL".repeat(100000), "Query".repeat(100000), 7)
        FileTocHostSessionRepository(directory).write(session, value)
        assertEquals(value, FileTocHostSessionRepository(directory).read(session))
    }

    @Test
    fun lateStaleRevisionCannotOverwriteCurrentSessionAndUuidBoundaryRejectsTraversal() =
        runBlocking {
            val session = UUID.randomUUID().toString()
            val repo = FileTocHostSessionRepository(directory)
            val value = TocHostSession("book", "latest", 9)
            repo.write(session, value)
            repo.write(session, value.copy(query = "old", revision = 1))
            assertEquals(value, repo.read(session))
            assertTrue(runCatching { repo.write("../escape", value) }.isFailure)
        }

    @Test
    fun releaseFencePreventsLateWriteRecreatingFullQuery() = runBlocking {
        val session = UUID.randomUUID().toString()
        val repo = FileTocHostSessionRepository(directory)
        val value = TocHostSession("book", "Query", 1)
        repo.write(session, value)
        repo.release(session)
        repo.release(session)
        repo.write(session, value.copy(revision = 999))
        assertNull(repo.read(session))
        assertFalse(File(directory, "$session.json").exists())
    }

    @Test
    fun failedInitialWriteCanRetryWithSameOwnedIdAfterDirectoryBecomesWritable() = runBlocking {
        directory.parentFile!!.mkdirs()
        directory.writeText("block directory")
        val session = UUID.randomUUID().toString()
        val repo = FileTocHostSessionRepository(directory)
        val value = TocHostSession("book", "Draft", 1)
        assertTrue(runCatching { repo.write(session, value) }.isFailure)
        assertTrue(directory.delete())
        repo.write(session, value)
        assertEquals(value, repo.read(session))
    }

    @Test
    fun sameRevisionConflictsAreRejectedAndIdenticalWritesAreAcknowledged() = runBlocking {
        val session = UUID.randomUUID().toString()
        val repo = FileTocHostSessionRepository(directory)
        val value = TocHostSession("book", "original", 5)
        assertTrue(repo.write(session, value))
        assertTrue(repo.write(session, value))
        assertFalse(repo.write(session, value.copy(query = "conflicting")))
        assertEquals(value, repo.read(session))
    }

    @Test
    fun restoredOwnerSurvivesLateWriteAndCleanupFromAnotherRepositoryInstance() = runBlocking {
        val session = UUID.randomUUID().toString()
        val first = FileTocHostSessionRepository(directory)
        val old = first.claim(session, "book", "old-owner")
        assertTrue(first.write(session, old.copy(query = "saved", revision = old.revision + 1)))
        val restored = FileTocHostSessionRepository(directory)
        val accepted = restored.claim(session, "book", "restored-owner")
        assertEquals("saved", accepted.query)
        assertFalse(first.write(session, old.copy(query = "late", revision = 999)))
        first.release(session, "old-owner")
        assertEquals(accepted, restored.read(session))
        restored.release(session, "restored-owner")
        assertFalse(first.write(session, old.copy(revision = 1000)))
        assertNull(restored.read(session))
    }
}
