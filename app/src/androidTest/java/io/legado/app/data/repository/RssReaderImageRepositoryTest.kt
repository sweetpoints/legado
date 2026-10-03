package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RssReaderImageRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var directory: File
    private lateinit var repository: AppRssReaderImageRepository
    private var previous: String? = null
    @Before fun setup() {
        directory = File(context.cacheDir, "rss-reader-image-${UUID.randomUUID()}").apply { mkdirs() }
        repository = AppRssReaderImageRepository(context)
        previous = runBlocking { repository.directory() }
    }
    @After fun close() { runBlocking { repository.directory(previous) }; directory.deleteRecursively() }
    @Test fun dataUrlBytesAreSavedAsJpgInsideChosenDirectory() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        repository.save("data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), Uri.fromFile(directory).toString())
        val file = directory.listFiles()!!.single()
        assertTrue(file.name.endsWith(".jpg")); assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun networkDownloadAndDirectoryPreferenceUseExactPublicValues() = runBlocking {
        var requested: String? = null
        val bytes = byteArrayOf(9, 8, 7)
        val value = AppRssReaderImageRepository(context) { requested = it; bytes }
        val uri = Uri.fromFile(directory).toString(); value.directory(uri)
        assertEquals(uri, value.directory())
        value.save("https://fixture.invalid/owned.jpg?query=exact", uri)
        assertEquals("https://fixture.invalid/owned.jpg?query=exact", requested)
        assertArrayEquals(bytes, directory.listFiles()!!.single().readBytes())
    }
    @Test fun cancelledNonCooperativeDownloadCannotCreateDestinationFile() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val value = AppRssReaderImageRepository(context) {
            entered.complete(Unit); withContext(NonCancellable) { gate.await() }; byteArrayOf(1)
        }
        val job = async { value.save("https://fixture.invalid/image", Uri.fromFile(directory).toString()) }
        try {
            entered.await(); job.cancel(); gate.complete(Unit); job.join()
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { gate.complete(Unit); job.cancelAndJoin() }
    }
    @Test fun malformedDataUrlFailsWithoutWritingAFile() = runBlocking {
        assertTrue(runCatching { repository.save("broken data URL", Uri.fromFile(directory).toString()) }.isFailure)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
