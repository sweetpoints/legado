package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ReplaceManagementSessionRepositoryTest {
    private val directory =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "replace-management-state-${UUID.randomUUID()}",
        )
    private val repository = FileReplaceManagementSessionRepository(directory)
    private val id = UUID.randomUUID().toString()

    @After
    fun after() {
        directory.deleteRecursively()
    }

    @Test
    fun realDiskRestoresLargeQueriesSelectionsAndModalDraftsWithExactCursor() = runBlocking {
        val value =
            ReplaceManagementCheckpoint(
                10,
                "Q".repeat(2000000),
                3,
                7,
                (1L..10000L).toList(),
                "AddGroup",
                "D".repeat(1000000),
                2,
                4,
                listOf(2, 5, 7),
            )
        repository.write(id, value)
        assertEquals(value, FileReplaceManagementSessionRepository(directory).read(id))
    }

    @Test
    fun lowerRevisionCannotOverwriteLaterDraftAndReleaseFencesLateAtomicWrites() = runBlocking {
        val newer = ReplaceManagementCheckpoint(20, "Latest")
        repository.write(id, newer)
        repository.write(id, newer.copy(revision = 19, query = "Old"))
        assertEquals(newer, repository.read(id))
        withContext(Dispatchers.IO) {
            File(directory, "$id.json.bak").writeText("backup")
            File(directory, "$id.json.new").writeText("temp")
        }
        repository.release(id)
        repository.write(id, newer.copy(revision = 21))
        assertNull(repository.read(id))
        assertTrue(
            listOf("$id.json", "$id.json.bak", "$id.json.new").none { File(directory, it).exists() }
        )
    }

    @Test
    fun invalidUuidCannotEscapeOwnedDirectoryAndInitialWriteFailureCanRetry() = runBlocking {
        directory.parentFile!!.mkdirs()
        directory.writeText("Blocked")
        try {
            repository.write(id, ReplaceManagementCheckpoint(1))
            fail("Must fail")
        } catch (_: IllegalStateException) {}
        directory.delete()
        repository.write(id, ReplaceManagementCheckpoint(1, "Recovered"))
        assertEquals("Recovered", repository.read(id)!!.query)
        try {
            repository.release("../escape")
            fail("Must reject")
        } catch (_: IllegalArgumentException) {}
        assertNotNull(repository.read(id))
    }
}
