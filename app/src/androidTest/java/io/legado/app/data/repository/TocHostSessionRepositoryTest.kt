package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class TocHostSessionRepositoryTest {
    private val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "toc-host-fixture-${UUID.randomUUID()}")
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun fullLargeQueryAndUrlRestoreFromActualOwnedAtomicFile() = runBlocking {
        val session = UUID.randomUUID().toString(); val value = TocHostSession("URL".repeat(100000), "Query".repeat(100000), 7)
        FileTocHostSessionRepository(directory).write(session, value)
        assertEquals(value, FileTocHostSessionRepository(directory).read(session))
    }
    @Test fun lateStaleRevisionCannotOverwriteCurrentSessionAndUuidBoundaryRejectsTraversal() = runBlocking {
        val session = UUID.randomUUID().toString(); val repo = FileTocHostSessionRepository(directory); val value = TocHostSession("book", "latest", 9)
        repo.write(session, value); repo.write(session, value.copy(query = "old", revision = 1)); assertEquals(value, repo.read(session))
        assertTrue(runCatching { repo.write("../escape", value) }.isFailure)
    }
    @Test fun releaseFencePreventsLateWriteRecreatingFullQuery() = runBlocking {
        val session = UUID.randomUUID().toString(); val repo = FileTocHostSessionRepository(directory); val value = TocHostSession("book", "Query", 1)
        repo.write(session, value); repo.release(session); repo.release(session); repo.write(session, value.copy(revision = 999))
        assertNull(repo.read(session)); assertFalse(File(directory, "$session.json").exists())
    }
    @Test fun failedInitialWriteCanRetryWithSameOwnedIdAfterDirectoryBecomesWritable() = runBlocking {
        directory.parentFile!!.mkdirs(); directory.writeText("block directory")
        val session = UUID.randomUUID().toString(); val repo = FileTocHostSessionRepository(directory); val value = TocHostSession("book", "Draft", 1)
        assertTrue(runCatching { repo.write(session, value) }.isFailure); assertTrue(directory.delete())
        repo.write(session, value); assertEquals(value, repo.read(session))
    }
}
