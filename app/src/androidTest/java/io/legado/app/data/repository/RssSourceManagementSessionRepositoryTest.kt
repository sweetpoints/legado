package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RssSourceManagementSessionRepositoryTest {
    private lateinit var directory: File
    private lateinit var repository: FileRssSourceManagementSessionRepository
    private val id = UUID.randomUUID().toString()
    @Before fun setup() {
        directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "rss-management-state-${UUID.randomUUID()}")
        repository = FileRssSourceManagementSessionRepository(directory)
    }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun unboundedSearchSelectionAndPreparedImportRestoreExactlyFromDisk() = runBlocking {
        val checkpoint = RssSourceManagementCheckpoint(9, "Q".repeat(2000000), 3, 12, (0..999).map { "id-$it" },
            "ImportUrl", "U".repeat(2000000), 7, 15, listOf("target"),
            RssSourceManagementPrepared("ImportUrl", UUID.randomUUID().toString(), input = "I".repeat(2000000)))
        repository.write(id, checkpoint)
        assertEquals(checkpoint, FileRssSourceManagementSessionRepository(directory).read(id))
    }
    @Test fun oldRevisionAndReleasedLateWritesCannotOverwriteOrRecreateOwnedSession() = runBlocking {
        val value = RssSourceManagementCheckpoint(80, query = "Latest", selected = listOf("fixed id"))
        repository.write(id, value); repository.write(id, value.copy(revision = 79, query = "Old")); assertEquals(value, repository.read(id))
        File(directory, "$id.json.bak").writeText(File(directory, "$id.json").readText())
        File(directory, "$id.json.new").writeText("Incomplete")
        repository.release(id); repository.write(id, value.copy(revision = 100))
        assertNull(repository.read(id)); assertTrue(File(directory, "$id.released").exists())
        assertTrue(listOf(".json", ".json.bak", ".json.new").none { File(directory, id + it).exists() })
    }
    @Test fun failedInitialWriteCanRetrySameSessionAndTraversalCannotReleaseFiles() = runBlocking {
        directory.writeText("Blocked parent"); val value = RssSourceManagementCheckpoint(1, query = "Exact")
        assertTrue(runCatching { repository.write(id, value) }.isFailure)
        assertTrue(directory.delete()); repository.write(id, value); assertEquals(value, repository.read(id))
        assertTrue(runCatching { repository.read("../escape") }.isFailure)
        assertTrue(runCatching { repository.release("../escape") }.isFailure)
    }
    @Test fun returningReceiptAndLargeFeedbackRestoreExactlyWithoutPreferenceMutation() = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val checkpoint = RssSourceManagementCheckpoint(revision = 12,
            pending = RssSourceManagementPrepared("ImportInput", UUID.randomUUID().toString(), input = "I".repeat(2000000), returningNonce = nonce),
            feedback = RssSourceManagementShareFeedback("https://owned.invalid", "Summary", true, "P".repeat(100000)), returnedNonce = nonce)
        repository.write(id, checkpoint)
        assertEquals(checkpoint, FileRssSourceManagementSessionRepository(directory).read(id))
    }

}
