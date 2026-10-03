package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MangaReaderSessionRepositoryTest {
    private lateinit var directory: File
    private lateinit var repository: FileMangaReaderSessionRepository

    @Before
    fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "manga-session-test-${UUID.randomUUID()}")
        repository = FileMangaReaderSessionRepository(directory)
    }

    @After
    fun after() {
        directory.deleteRecursively()
    }

    @Test
    fun completeLargeNativeImageAndLaunchPayloadRemainPrivateAndRestoreExactly() = runBlocking {
        val id = UUID.randomUUID().toString()
        val large = "data:image/png;base64," + "abcd".repeat(600_000)
        val request =
            MangaNativeRequest(
                ticket = UUID.randomUUID().toString(),
                kind = MangaNativeKind.ImageDirectory,
                imageUrl = large,
                title = large,
                phase = MangaNativePhase.Claimed,
            )
        val value =
            MangaReaderSession(
                revision = 2,
                launch =
                    MangaReaderLaunch(bookUrl = large, inBookshelf = false, chapterChanged = true),
                nativeRequests = listOf(request),
            )
        withContext(Dispatchers.Main) { repository.write(id, value) }
        assertEquals(value, repository.read(id))
        assertTrue(File(directory, "$id.json").length() > large.length)
    }

    @Test
    fun staleAndSameRevisionCannotEraseAcceptedReceiptAndBackupTombstoneRejectsLateWriter() =
        runBlocking {
            val id = UUID.randomUUID().toString()
            val request =
                MangaNativeRequest(
                    "ticket",
                    MangaNativeKind.Catalog,
                    phase = MangaNativePhase.Claimed,
                )
            val accepted =
                MangaReaderSession(3, MangaReaderLaunch("book"), nativeRequests = listOf(request))
            repository.write(id, accepted)
            repository.write(id, accepted.copy(revision = 2, nativeRequests = emptyList()))
            repository.write(id, accepted.copy(nativeRequests = emptyList()))
            assertEquals(accepted, repository.read(id))
            repository.release(id)
            assertTrue(
                File(directory, "$id.released").renameTo(File(directory, "$id.released.bak"))
            )
            repository.write(id, accepted.copy(revision = 4))
            assertNull(repository.read(id))
            assertFalse(File(directory, "$id.json").exists())
        }

    @Test
    fun atomicBackupRestoresOwnPayloadAndReleasePreservesNeighbor() = runBlocking {
        val id = UUID.randomUUID().toString()
        val neighbor = UUID.randomUUID().toString()
        val value = MangaReaderSession(1, MangaReaderLaunch("first"))
        val other = MangaReaderSession(1, MangaReaderLaunch("second"))
        repository.write(id, value)
        repository.write(neighbor, other)
        val file = File(directory, "$id.json")
        file.copyTo(File(directory, "$id.json.bak"))
        file.writeText("incomplete")
        assertEquals(value, repository.read(id))
        repository.release(id)
        assertNull(repository.read(id))
        assertEquals(other, repository.read(neighbor))
        assertTrue(File(directory, "$neighbor.json").isFile)
    }
}
