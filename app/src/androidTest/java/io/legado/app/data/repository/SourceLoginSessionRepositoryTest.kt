package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class SourceLoginSessionRepositoryTest {
    private val directory =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "login-entry-${UUID.randomUUID()}",
        )
    private val repository = FileSourceLoginSessionRepository(directory)
    private val id = UUID.randomUUID().toString()

    @After
    fun after() {
        directory.deleteRecursively()
    }

    @Test
    fun actualDiskRestoresLargeSourceRequestExactlyThroughANewOwner() = runBlocking {
        val request =
            SourceLoginRequest(
                type = "rssSource",
                key = "K".repeat(2000000),
                bookUrl = "B".repeat(1000000),
            )
        repository.write(id, request)
        assertEquals(request, FileSourceLoginSessionRepository(directory).read(id))
        assertTrue(File(directory, "$id.json").length() > 2900000)
    }

    @Test
    fun releaseFencesLateWritesAndDeletesAtomicBackupAndTempFilesOnlyForOwnedUuid() = runBlocking {
        val other = UUID.randomUUID().toString()
        val request = SourceLoginRequest(type = "httpTts", key = "1")
        repository.write(id, request)
        repository.write(other, request)
        withContext(Dispatchers.IO) {
            File(directory, "$id.json.bak").writeText("backup")
            File(directory, "$id.json.new").writeText("temporary")
        }
        repository.release(id)
        repository.write(id, request)
        assertNull(repository.read(id))
        assertEquals(request, repository.read(other))
        assertTrue(
            listOf("$id.json", "$id.json.bak", "$id.json.new").none { File(directory, it).exists() }
        )
    }

    @Test
    fun failedInitialDirectoryCanRetryAndInvalidIdCannotReadAnotherRequest() = runBlocking {
        directory.parentFile!!.mkdirs()
        directory.writeText("Blocked")
        val request = SourceLoginRequest(type = "rssSource", key = "Exact")
        try {
            repository.write(id, request)
            fail("Must fail")
        } catch (_: IllegalStateException) {}
        directory.delete()
        repository.write(id, request)
        assertEquals(request, repository.read(id))
        try {
            repository.read("../escape")
            fail("Invalid id")
        } catch (_: IllegalArgumentException) {}
        assertEquals(request, repository.read(id))
    }
}
