package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RssReaderSessionRepositoryTest {
    private lateinit var directory: File
    private lateinit var repository: FileRssReaderSessionRepository
    private val id = UUID.randomUUID().toString()
    @Before fun setup() {
        directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "rss-reader-fixture-${UUID.randomUUID()}")
        repository = FileRssReaderSessionRepository(directory)
    }
    @After fun close() { directory.deleteRecursively() }
    @Test fun largeHtmlUrlsAndTitlesRestoreExactlyFromPrivateDisk() = runBlocking {
        val value = RssReaderSession(RssReaderRequest(origin = "https://" + "o".repeat(200000), title = "t".repeat(200000),
            openUrl = "https://" + "u".repeat(200000), startHtml = "<body>" + "h".repeat(2000000) + "</body>"),
            revision = 7, currentUrl = "https://" + "c".repeat(200000), currentTitle = "Current title")
        repository.write(id, value)
        assertEquals(value, FileRssReaderSessionRepository(directory).read(id))
    }
    @Test fun staleWritesCannotReplaceNewerOwnerCheckpoint() = runBlocking {
        val value = RssReaderSession(RssReaderRequest("source"), 9, "Current")
        repository.write(id, value); repository.write(id, value.copy(revision = 8, currentUrl = "Stale"))
        assertEquals(value, repository.read(id))
    }
    @Test fun releaseDeletesAtomicSidecarsAndPreventsLateRecreation() = runBlocking {
        val value = RssReaderSession(RssReaderRequest("source", startHtml = "Large"), 1)
        repository.write(id, value)
        val backup = File(directory, "$id.json.bak"); backup.writeText(File(directory, "$id.json").readText())
        repository.release(id); repository.write(id, value.copy(revision = 999))
        assertNull(repository.read(id)); assertFalse(File(directory, "$id.json").exists()); assertFalse(backup.exists()); assertFalse(File(directory, "$id.json.new").exists())
        assertTrue(File(directory, "$id.released").exists())
    }
    @Test fun initialWriteFailureCanRetrySameSessionAndTraversalIsRejected() = runBlocking {
        directory.writeText("Blocked parent")
        val value = RssReaderSession(RssReaderRequest("source"), 1)
        assertTrue(runCatching { repository.write(id, value) }.isFailure)
        assertTrue(directory.delete()); repository.write(id, value)
        assertEquals(value, repository.read(id))
        assertTrue(runCatching { repository.read("../escape") }.isFailure)
        assertTrue(runCatching { repository.release("../escape") }.isFailure)
    }
}
