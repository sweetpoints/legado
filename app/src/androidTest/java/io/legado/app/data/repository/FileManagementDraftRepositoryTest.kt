package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class FileManagementDraftRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var directory: File

    @Before
    fun before() {
        directory = File(context.cacheDir, "file-manager-${UUID.randomUUID()}")
    }

    @After
    fun after() {
        directory.deleteRecursively()
    }

    private fun repo() = AtomicFileManagementDraftRepository(context, directory)

    @Test
    fun largeDirectoryQueryAndNativeUriRestoreAcrossInstancesWithoutTruncation() = runBlocking {
        val ticket = UUID.randomUUID().toString()
        val large = "large".repeat(200000)
        val value =
            FileManagementDraft(large, large, ManagedFileOpen("request", large), revision = 10)
        repo().write(ticket, value)
        repo().write(ticket, value.copy(query = "old", revision = 9))
        assertEquals(value, repo().read(ticket))
    }

    @Test
    fun releaseRemovesPrivateBodyAndBackupAndPermanentlyFencesLateWriteAcrossInstances() =
        runBlocking {
            val ticket = UUID.randomUUID().toString()
            repo().write(ticket, FileManagementDraft(query = "private"))
            File(directory, "$ticket.json.bak").writeText("private")
            File(directory, "$ticket.json.new").writeText("private")
            repo().release(ticket)
            assertNull(repo().read(ticket))
            assertTrue(
                runCatching { repo().write(ticket, FileManagementDraft(revision = 10)) }.isFailure
            )
            listOf(".json", ".json.bak", ".json.new").forEach {
                assertFalse(File(directory, ticket + it).exists())
            }
        }

    @Test
    fun backupOnlyCloseFenceCannotBeBypassedAndReleaseCleansAnyOldPrivateBody() = runBlocking {
        val ticket = UUID.randomUUID().toString()
        repo().write(ticket, FileManagementDraft(query = "private"))
        File(directory, "$ticket.closed.bak").writeText("closed")
        assertNull(repo().read(ticket))
        assertTrue(
            runCatching { repo().write(ticket, FileManagementDraft(revision = 10)) }.isFailure
        )
        repo().release(ticket)
        assertFalse(File(directory, "$ticket.json").exists())
    }

    @Test
    fun concurrentLateFlushAndCloseNeverResurrectBodyAndInvalidTicketNeverEscapesDirectory() =
        runBlocking {
            val ticket = UUID.randomUUID().toString()
            val flush =
                async(Dispatchers.IO) {
                    runCatching {
                        repo().write(ticket, FileManagementDraft(query = "private".repeat(100000)))
                    }
                }
            repo().release(ticket)
            flush.await()
            assertNull(repo().read(ticket))
            assertFalse(File(directory, "$ticket.json").exists())
            assertTrue(runCatching { repo().write("../outside", FileManagementDraft()) }.isFailure)
        }
}
