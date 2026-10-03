package io.legado.app.data.repository

import android.net.Uri
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class HandleFileChoicesRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "handle-choices-${UUID.randomUUID()}")
    @After fun after() { directory.deleteRecursively() }
    @Test fun oneShotLargePayloadAndMetadataRestoreFromRealDiskWithSeparateSmallCheckpoint() = runBlocking {
        val repo = FileHandleFileChoicesSessionRepository(directory); val id = UUID.randomUUID().toString()
        val bytes = ByteArray(2000000) { (it % 123).toByte() }
        val input = HandleFileInput(3, "Large title".repeat(50000), listOf("js"), listOf(HandleFileChoice("Other", 24)), "rule.json", "application/json", "Original nonce")
        repo.stage(id, HandleFileSeed(input.copy(otherActions = emptyList()), bytes, "[{\"title\":\"Other\",\"value\":24}]")); assertEquals(input, FileHandleFileChoicesSessionRepository(directory).input(id))
        assertArrayEquals(bytes, repo.bytes(id)); repo.write(id, HandleFileCheckpoint(12, "Manual", "D".repeat(2000000), 3, 8,
            HandleFilePending(11, UUID.randomUUID().toString(), true), result = "content://exact"))
        assertEquals(2000000, repo.read(id)!!.draft.length); assertEquals(8, repo.read(id)!!.end)
        repo.stage(id, HandleFileSeed(input.copy(title = "Wrong replacement"), "Wrong")); assertEquals(input, repo.input(id)); assertArrayEquals(bytes, repo.bytes(id))
    }
    @Test fun privateReleaseRemovesOnlyOwnerAndFencesLateWritesAndPayloadStage() = runBlocking {
        val repo = FileHandleFileChoicesSessionRepository(directory); val id = UUID.randomUUID().toString(); val neighbor = UUID.randomUUID().toString()
        repo.stage(id, HandleFileSeed(HandleFileInput(), "Own")); repo.stage(neighbor, HandleFileSeed(HandleFileInput(), "Neighbor"))
        repo.write(id, HandleFileCheckpoint(20, draft = "New")); repo.write(id, HandleFileCheckpoint(19, draft = "Old")); assertEquals("New", repo.read(id)!!.draft)
        repo.release(id); repo.write(id, HandleFileCheckpoint(21)); assertNull(repo.input(id)); assertNull(repo.read(id)); assertFalse(File(directory, id).exists())
        try { repo.stage(id, HandleFileSeed(HandleFileInput(), "Late")); fail("Released stage must fail") } catch (_: IllegalStateException) {}
        assertEquals("Neighbor", repo.bytes(neighbor).decodeToString())
    }
    @Test fun failedInitialStageRetriesSameUuidAndRejectsEscapeAndMissingPayload() = runBlocking {
        directory.parentFile!!.mkdirs(); directory.writeText("Blocked")
        val repo = FileHandleFileChoicesSessionRepository(directory); val id = UUID.randomUUID().toString()
        try { repo.stage(id, HandleFileSeed(HandleFileInput(), "Exact")); fail("Must fail") } catch (_: IllegalStateException) {}
        directory.delete(); repo.stage(id, HandleFileSeed(HandleFileInput(), "Exact")); assertEquals("Exact", repo.bytes(id).decodeToString())
        try { repo.release("../escape"); fail("Must reject") } catch (_: IllegalArgumentException) {}
        val missing = UUID.randomUUID().toString(); repo.stage(missing, HandleFileSeed(HandleFileInput()))
        try { repo.bytes(missing); fail("Must fail") } catch (error: HandleFileIssueException) { assertEquals(HandleFileIssue.PayloadMissing, error.issue) }
    }
    @Test fun actualManualValidationKeepsHttpPrefixCaseAndTypedIssuesAndExternalBoundary() = runBlocking {
        directory.mkdirs(); val file = File(directory, " image ").apply { writeText("Actual") }
        val repo = AppHandleFileChoicesRepository(context, external = { it.canonicalPath.startsWith(directory.canonicalPath + File.separator) || it.canonicalPath == directory.canonicalPath })
        assertEquals(Uri.fromFile(directory).toString(), repo.manual(directory.path, false))
        assertEquals(Uri.fromFile(file).toString(), repo.manual(file.path, true)); assertEquals("HTTP://example/image", repo.manual("HTTP://example/image", true))
        suspend fun issue(text: String, image: Boolean, expected: HandleFileIssue) {
            try { repo.manual(text, image); fail("Must reject") } catch (error: HandleFileIssueException) { assertEquals(expected, error.issue) }
        }
        issue(" ", false, HandleFileIssue.EmptyDirectory); issue("", true, HandleFileIssue.EmptyImage)
        issue(file.path, false, HandleFileIssue.InvalidDirectory); issue(directory.path, true, HandleFileIssue.InvalidImage)
    }
    @Test fun actualFileSaveAndUploadStayOffMainAndPayloadHasExactOriginalContent() = runBlocking {
        directory.mkdirs(); var uploadThread = true; var uploaded: ByteArray? = null
        val repo = AppHandleFileChoicesRepository(context, uploadFile = { name, data, type ->
            uploadThread = Looper.myLooper() == Looper.getMainLooper(); assertEquals("rule.json", name); assertEquals("application/json", type)
            uploaded = data as ByteArray; "https://exact/result"
        })
        val bytes = "{\"exact\":true}".toByteArray()
        val uri = withContext(Dispatchers.Main) { repo.save(Uri.fromFile(directory).toString(), "rule.json", bytes) }
        assertEquals(Uri.fromFile(File(directory, "rule.json")).toString(), uri); assertArrayEquals(bytes, File(directory, "rule.json").readBytes())
        assertEquals("https://exact/result", withContext(Dispatchers.Main) { repo.upload("rule.json", bytes, "application/json") })
        assertFalse(uploadThread); assertArrayEquals(bytes, uploaded)
    }
}
