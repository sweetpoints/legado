package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RssReaderLaunchRepositoryTest {
    private lateinit var directory: File
    private lateinit var repository: FileRssReaderLaunchRepository
    @Before fun before() {
        directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "reader-launch-${UUID.randomUUID()}")
        repository = FileRssReaderLaunchRepository(directory)
    }
    @After fun after() { directory.deleteRecursively() }
    @Test fun largeCompleteRequestCrossesOnlyUuidAndOwnedReleaseDeletesAtomicSidecars() = runBlocking {
        val request = RssReaderRequest("O".repeat(2000000), "T".repeat(2000000), "link", "sort", "open", "H".repeat(2000000))
        val ticket = repository.stage(request); assertEquals(ticket, UUID.fromString(ticket).toString()); assertEquals(36, ticket.length)
        assertEquals(request, FileRssReaderLaunchRepository(directory).read(ticket))
        File(directory, "$ticket.json.bak").writeText(File(directory, "$ticket.json").readText())
        File(directory, "$ticket.json.new").writeText("Incomplete")
        repository.release(ticket); repository.release(ticket); assertNull(repository.read(ticket))
        assertTrue(listOf(".json", ".json.bak", ".json.new").none { File(directory, ticket + it).exists() })
    }
    @Test fun failedCreationCanRetryWithoutMutatingUnrelatedParentAndMalformedTicketCannotReleaseFiles() = runBlocking {
        directory.writeText("Blocked parent"); assertTrue(runCatching { repository.stage(RssReaderRequest("origin")) }.isFailure)
        assertEquals("Blocked parent", directory.readText()); assertTrue(directory.delete())
        val ticket = repository.stage(RssReaderRequest("origin")); assertTrue(runCatching { repository.release("../other") }.isFailure)
        assertEquals(RssReaderRequest("origin"), repository.read(ticket))
    }
    @Test fun cancellationAfterIoWriteBeforeReturnReleasesOnlyUndeliveredTicket() = runBlocking {
        val neighbor = repository.stage(RssReaderRequest("neighbor"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocked = FileRssReaderLaunchRepository(directory) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val job = launch(Dispatchers.IO) { blocked.stage(RssReaderRequest("canceled", startHtml = "Body")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancel(); release.countDown(); job.join()
            assertTrue(job.isCancelled); assertEquals(listOf("$neighbor.json"), directory.listFiles()!!.map { it.name })
            assertEquals(RssReaderRequest("neighbor"), repository.read(neighbor))
        } finally { release.countDown(); job.cancelAndJoin() }
    }
}
