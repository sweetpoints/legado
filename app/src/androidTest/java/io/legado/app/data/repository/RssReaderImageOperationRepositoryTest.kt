package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RssReaderImageOperationRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var directory: File
    private lateinit var sessions: FileRssReaderImageSessionRepository
    private val id = UUID.randomUUID().toString()

    @Before
    fun setup() {
        directory = File(context.cacheDir, "rss-image-operation-${UUID.randomUUID()}")
        sessions = FileRssReaderImageSessionRepository(directory)
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun hugeImageAndDirectoryRestoreExactlyWithoutNativeExtras() = runBlocking {
        val value =
            RssReaderImageSession(
                "owner-hash",
                "data:image/png;base64," + "A".repeat(2000000),
                8,
                "content://" + "directory".repeat(20000),
                "fixed.jpg",
                RssReaderImagePhase.Save,
            )
        sessions.write(id, value)
        assertEquals(value, FileRssReaderImageSessionRepository(directory).read(id))
    }

    @Test
    fun olderRevisionCannotReplaceCompletedCopyAndReleaseFencesAllSidecars() = runBlocking {
        val complete =
            RssReaderImageSession(
                "owner",
                "image",
                9,
                "target",
                "fixed.jpg",
                RssReaderImagePhase.Complete,
            )
        sessions.write(id, complete)
        sessions.write(id, complete.copy(revision = 8, phase = RssReaderImagePhase.Save))
        assertEquals(complete, sessions.read(id))
        File(directory, "$id.json.bak").writeText(File(directory, "$id.json").readText())
        File(directory, "$id.json.new").writeText("Incomplete")
        sessions.release(id)
        sessions.write(id, complete.copy(revision = 100))
        assertNull(sessions.read(id))
        assertTrue(File(directory, "$id.released").exists())
        assertTrue(
            listOf(".json", ".json.bak", ".json.new").none { File(directory, id + it).exists() }
        )
    }

    @Test
    fun failedInitialWriteCanRetrySameTicketWithoutLosingImage() = runBlocking {
        directory.writeText("Blocked parent")
        val value = RssReaderImageSession("owner", "exact image", 1)
        assertTrue(runCatching { sessions.write(id, value) }.isFailure)
        assertTrue(directory.delete())
        sessions.write(id, value)
        assertEquals(value, sessions.read(id))
        assertTrue(runCatching { sessions.read("../escape") }.isFailure)
    }

    @Test
    fun fixedFilenameRetryOverwritesOneTargetWithExactBytes() = runBlocking {
        assertTrue(directory.mkdirs())
        val repository = AppRssReaderImageRepository(context)
        val uri = Uri.fromFile(directory).toString()
        fun encoded(bytes: ByteArray) =
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        repository.save(encoded(byteArrayOf(1, 2, 3)), uri, "fixed.jpg")
        repository.save(encoded(byteArrayOf(9)), uri, "fixed.jpg")
        assertEquals(listOf("fixed.jpg"), directory.listFiles()!!.map { it.name })
        assertArrayEquals(byteArrayOf(9), File(directory, "fixed.jpg").readBytes())
    }

    @Test
    fun invalidFixedFilenameFailsBeforeDownloadOrDestinationMutation() = runBlocking {
        assertTrue(directory.mkdirs())
        var downloaded = false
        val repository =
            AppRssReaderImageRepository(context) {
                downloaded = true
                byteArrayOf(1)
            }
        val uri = Uri.fromFile(directory).toString()
        assertTrue(
            runCatching { repository.save("https://fixture.invalid/image", uri, "../outside.jpg") }
                .isFailure
        )
        assertFalse(downloaded)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
