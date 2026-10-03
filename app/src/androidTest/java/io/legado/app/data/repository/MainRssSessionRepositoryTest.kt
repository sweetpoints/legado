package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class MainRssSessionRepositoryTest {
    private lateinit var directory: File
    private lateinit var repository: FileMainRssSessionRepository
    private val id = UUID.randomUUID().toString()
    @Before fun before() {
        directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "rss-home-${UUID.randomUUID()}")
        repository = FileMainRssSessionRepository(directory)
    }
    @After fun after() { directory.deleteRecursively() }
    @Test fun completeLargeQueryHtmlAndDeleteConfirmationRecoverExactlyFromPrivateDisk() = runBlocking {
        val checkpoint = MainRssCheckpoint(revision = 9, query = "Q".repeat(2000000), queryStart = 8, queryEnd = 15,
            deletingId = "fixed-id", deletingName = "N".repeat(100000), pending = MainRssPrepared("Open", UUID.randomUUID().toString(), "fixed-id",
                "https://source.invalid", MainRssNavigation(MainRssDestination.ReaderHtml, "https://source.invalid", "Source", "H".repeat(2000000))))
        repository.write(id, checkpoint)
        assertEquals(checkpoint, FileMainRssSessionRepository(directory).read(id))
    }
    @Test fun staleRevisionAndLateWritesAfterReleaseCannotRestoreOrRecreateOwnedCheckpoint() = runBlocking {
        val current = MainRssCheckpoint(revision = 90, query = "Latest")
        repository.write(id, current); repository.write(id, current.copy(revision = 2, query = "Old")); assertEquals(current, repository.read(id))
        File(directory, "$id.json.bak").writeText(File(directory, "$id.json").readText())
        File(directory, "$id.json.new").writeText("Incomplete")
        repository.release(id); repository.write(id, current.copy(revision = 100))
        assertNull(repository.read(id)); assertTrue(File(directory, "$id.released").exists())
        assertTrue(listOf(".json", ".json.bak", ".json.new").none { File(directory, id + it).exists() })
    }
    @Test fun failedInitialWriteRetriesSameIdAndInvalidIdentityCannotTouchAnotherFile() = runBlocking {
        directory.writeText("Blocked parent")
        val value = MainRssCheckpoint(revision = 2, query = "Exact query")
        assertTrue(runCatching { repository.write(id, value) }.isFailure)
        assertTrue(directory.delete()); repository.write(id, value); assertEquals(value, repository.read(id))
        assertTrue(runCatching { repository.read("../other") }.isFailure)
        assertTrue(runCatching { repository.release("../other") }.isFailure)
        assertEquals(value, repository.read(id))
    }
}
