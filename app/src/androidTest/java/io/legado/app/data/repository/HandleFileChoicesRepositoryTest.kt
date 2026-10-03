package io.legado.app.data.repository

import android.net.Uri
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.utils.compress.ZipUtils
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandleFileChoicesRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "handle-choices-${UUID.randomUUID()}")

    @After
    fun after() {
        directory.deleteRecursively()
    }

    @Test
    fun oneShotLargePayloadAndMetadataRestoreFromRealDiskWithSeparateSmallCheckpoint() =
        runBlocking {
            val repo = FileHandleFileChoicesSessionRepository(directory)
            val id = UUID.randomUUID().toString()
            val bytes =
                ByteArray(2000000) {
                    (it % 123).toByte()
                }
            val input =
                HandleFileInput(
                    3,
                    "Large title".repeat(50000),
                    listOf("js"),
                    listOf(HandleFileChoice("Other", 24)),
                    "rule.json",
                    "application/json",
                    "Original nonce",
                )
            repo.stage(
                id,
                HandleFileSeed(
                    input.copy(otherActions = emptyList()),
                    bytes,
                    "[{\"title\":\"Other\",\"value\":24}]",
                ),
            )
            assertEquals(input, FileHandleFileChoicesSessionRepository(directory).input(id))
            assertArrayEquals(bytes, repo.bytes(id))
            repo.write(
                id,
                HandleFileCheckpoint(
                    12,
                    "Manual",
                    "D".repeat(2000000),
                    3,
                    8,
                    HandleFilePending(11, UUID.randomUUID().toString(), true),
                    result = "content://exact",
                ),
            )
            assertEquals(2000000, repo.read(id)!!.draft.length)
            assertEquals(8, repo.read(id)!!.end)
            repo.stage(id, HandleFileSeed(input.copy(title = "Wrong replacement"), "Wrong"))
            assertEquals(input, repo.input(id))
            assertArrayEquals(bytes, repo.bytes(id))
        }

    @Test
    fun privateReleaseRemovesOnlyOwnerAndFencesLateWritesAndPayloadStage() = runBlocking {
        val repo = FileHandleFileChoicesSessionRepository(directory)
        val id = UUID.randomUUID().toString()
        val neighbor = UUID.randomUUID().toString()
        repo.stage(id, HandleFileSeed(HandleFileInput(), "Own"))
        repo.stage(neighbor, HandleFileSeed(HandleFileInput(), "Neighbor"))
        repo.write(id, HandleFileCheckpoint(20, draft = "New"))
        repo.write(id, HandleFileCheckpoint(19, draft = "Old"))
        assertEquals("New", repo.read(id)!!.draft)
        repo.release(id)
        repo.write(id, HandleFileCheckpoint(21))
        assertNull(repo.input(id))
        assertNull(repo.read(id))
        assertFalse(File(directory, id).exists())
        try {
            repo.stage(id, HandleFileSeed(HandleFileInput(), "Late"))
            fail("Released stage must fail")
        } catch (_: IllegalStateException) {}

        assertEquals("Neighbor", repo.bytes(neighbor).decodeToString())
    }

    @Test
    fun equalRevisionCannotReplaceDurableCheckpointAcrossRepositoryInstances() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val firstRepository = FileHandleFileChoicesSessionRepository(directory)
        val restoredRepository = FileHandleFileChoicesSessionRepository(directory)
        firstRepository.stage(sessionId, HandleFileSeed(HandleFileInput(), "Payload"))
        val acceptedCheckpoint = HandleFileCheckpoint(4, "Result", result = "https://accepted")
        firstRepository.write(sessionId, acceptedCheckpoint)
        restoredRepository.write(sessionId, HandleFileCheckpoint(4, "Native", draft = "Stale"))
        assertEquals(acceptedCheckpoint, restoredRepository.read(sessionId))
        val nextCheckpoint = acceptedCheckpoint.copy(revision = 5, finished = true)
        restoredRepository.write(sessionId, nextCheckpoint)
        assertEquals(nextCheckpoint, firstRepository.read(sessionId))
    }

    @Test
    fun failedInitialStageRetriesSameUuidAndRejectsEscapeAndMissingPayload() = runBlocking {
        directory.parentFile!!.mkdirs()
        directory.writeText("Blocked")
        val repo = FileHandleFileChoicesSessionRepository(directory)
        val id = UUID.randomUUID().toString()
        try {
            repo.stage(id, HandleFileSeed(HandleFileInput(), "Exact"))
            fail("Must fail")
        } catch (_: IllegalStateException) {}

        directory.delete()
        repo.stage(id, HandleFileSeed(HandleFileInput(), "Exact"))
        assertEquals("Exact", repo.bytes(id).decodeToString())
        try {
            repo.release("../escape")
            fail("Must reject")
        } catch (_: IllegalArgumentException) {}

        val missing = UUID.randomUUID().toString()
        repo.stage(missing, HandleFileSeed(HandleFileInput()))
        try {
            repo.bytes(missing)
            fail("Must fail")
        } catch (error: HandleFileIssueException) {
            assertEquals(HandleFileIssue.PayloadMissing, error.issue)
        }
    }

    @Test
    fun actualManualValidationKeepsHttpPrefixCaseAndTypedIssuesAndExternalBoundary() = runBlocking {
        directory.mkdirs()
        val file =
            File(directory, " image ").apply {
                writeText("Actual")
            }
        val repo =
            AppHandleFileChoicesRepository(
                context,
                external = {
                    it.canonicalPath.startsWith(directory.canonicalPath + File.separator) ||
                        it.canonicalPath == directory.canonicalPath
                },
            )
        assertEquals(Uri.fromFile(directory).toString(), repo.manual(directory.path, false))
        assertEquals(Uri.fromFile(file).toString(), repo.manual(file.path, true))
        assertEquals("HTTP://example/image", repo.manual("HTTP://example/image", true))
        suspend fun issue(text: String, image: Boolean, expected: HandleFileIssue) {

            try {
                repo.manual(text, image)
                fail("Must reject")
            } catch (error: HandleFileIssueException) {
                assertEquals(expected, error.issue)
            }
        }
        issue(" ", false, HandleFileIssue.EmptyDirectory)
        issue("", true, HandleFileIssue.EmptyImage)
        issue(file.path, false, HandleFileIssue.InvalidDirectory)
        issue(directory.path, true, HandleFileIssue.InvalidImage)
    }

    @Test
    fun actualFileSaveAndUploadStayOffMainAndPayloadHasExactOriginalContent() = runBlocking {
        directory.mkdirs()
        var uploadThread = true
        var uploaded: ByteArray? = null
        val repo =
            AppHandleFileChoicesRepository(
                context,
                uploadFile = { name, data, type ->
                    uploadThread = Looper.myLooper() == Looper.getMainLooper()
                    assertEquals("rule.json", name)
                    assertEquals("application/json", type)
                    uploaded = data as ByteArray
                    "https://exact/result"
                },
            )
        val bytes = "{\"exact\":true}".toByteArray()
        val uri =
            withContext(Dispatchers.Main) {
                repo.save(Uri.fromFile(directory).toString(), "rule.json", bytes)
            }
        assertEquals(Uri.fromFile(File(directory, "rule.json")).toString(), uri)
        assertArrayEquals(bytes, File(directory, "rule.json").readBytes())
        assertEquals(
            "https://exact/result",
            withContext(Dispatchers.Main) {
                repo.upload("rule.json", bytes, "application/json")
            },
        )
        assertFalse(uploadThread)
        assertArrayEquals(bytes, uploaded)
    }

    @Test
    fun uploadAcceptedReceiptIsOnIoAndDurableBeforeCancelledMainReturn() = runBlocking {
        acceptedReturnCancellation(upload = true)
    }

    @Test
    fun localSaveAcceptedReceiptIsOnIoAndDurableBeforeCancelledMainReturn() = runBlocking {
        acceptedReturnCancellation(upload = false)
    }

    private suspend fun acceptedReturnCancellation(upload: Boolean) {

        directory.mkdirs()
        val disk = FileHandleFileChoicesSessionRepository(File(directory, "sessions"))
        val id = UUID.randomUUID().toString()
        disk.stage(id, HandleFileSeed(HandleFileInput(3), "Exact"))
        val accepted = CompletableDeferred<Unit>()
        val finishReceipt = CompletableDeferred<Unit>()
        var delivered = false
        val repo =
            AppHandleFileChoicesRepository(
                context,
                uploadFile = { _, _, _ ->
                    "https://accepted"
                },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {

            val job = scope.launch {
                val receipt: suspend (String) -> Unit = { uri ->
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    accepted.complete(Unit)
                    finishReceipt.await()
                    disk.write(id, HandleFileCheckpoint(4, "Result", result = uri))
                }
                if (upload)
                    repo.uploadRecorded(
                        "rule.json",
                        "Exact".toByteArray(),
                        "application/json",
                        receipt,
                    )
                else
                    repo.saveRecorded(
                        Uri.fromFile(directory).toString(),
                        "rule.json",
                        "Exact".toByteArray(),
                        receipt,
                    )
                delivered = true
            }
            accepted.await()
            job.cancel()
            finishReceipt.complete(Unit)
            job.join()
            assertFalse(delivered)
            val persisted = disk.read(id)!!
            assertEquals("Result", persisted.phase)
            assertEquals(
                if (upload) "https://accepted"
                else Uri.fromFile(File(directory, "rule.json")).toString(),
                persisted.result,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fileOriginRestoresBasenameAndUsesDisposableFileForOriginalZipBranch() = runBlocking {
        directory.mkdirs()
        val original = File(directory, "source-basename.json").apply { writeText("Exact payload") }
        val sessionDirectory = File(directory, "sessions")
        val sessions = FileHandleFileChoicesSessionRepository(sessionDirectory)
        val sessionId = UUID.randomUUID().toString()
        sessions.stage(
            sessionId,
            HandleFileSeed(
                HandleFileInput(
                    3,
                    fileName = "display-name.json",
                    contentType = "application/json",
                ),
                original,
            ),
        )
        val input = FileHandleFileChoicesSessionRepository(sessionDirectory).input(sessionId)!!
        assertEquals(original.name, input.sourceFileName)
        var uploadedCopy: File? = null
        val archive = File(directory, "compressed.zip")
        val repository =
            AppHandleFileChoicesRepository(
                context,
                uploadFile = { name, payload, contentType ->
                    assertEquals("display-name.json", name)
                    assertEquals("application/json", contentType)
                    val source = payload as File
                    uploadedCopy = source
                    assertEquals(original.name, source.name)
                    assertEquals("Exact payload", source.readText())
                    assertTrue(ZipUtils.zipFile(source, archive))
                    source.delete() // DirectLinkUpload may delete the File that it was given.
                    "https://accepted-file"
                },
            )
        val result =
            repository.uploadFileRecorded(
                input.fileName!!,
                input.sourceFileName!!,
                sessions.bytes(sessionId),
                input.contentType!!,
            ) { uri ->
                sessions.write(sessionId, HandleFileCheckpoint(3, "Result", result = uri))
            }
        assertEquals("https://accepted-file", result)
        ZipInputStream(archive.inputStream()).use { zip ->
            assertEquals(original.name, zip.nextEntry.name)
            assertEquals("Exact payload", zip.readBytes().decodeToString())
        }
        assertTrue(original.exists())
        assertEquals("Exact payload", sessions.bytes(sessionId).decodeToString())
        assertFalse(uploadedCopy!!.parentFile!!.exists())
        assertEquals(result, sessions.read(sessionId)!!.result)
    }

    @Test
    fun cancelledFileUploadRemovesOnlyDisposableCopyAndNeverPublishesReceipt() = runBlocking {
        val neighbor = File(context.cacheDir, "handle-file-upload/neighbor-${UUID.randomUUID()}")
        neighbor.mkdirs()
        File(neighbor, "keep.json").writeText("Keep")
        val enteredUpload = CompletableDeferred<Unit>()
        var disposableCopy: File? = null
        var receipts = 0
        val repository =
            AppHandleFileChoicesRepository(
                context,
                uploadFile = { _, payload, _ ->
                    disposableCopy = payload as File
                    enteredUpload.complete(Unit)
                    awaitCancellation()
                },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val job = scope.launch {
                repository.uploadFileRecorded(
                    "display.json",
                    "source.json",
                    "Exact".toByteArray(),
                    "application/json",
                ) {
                    receipts += 1
                }
            }
            enteredUpload.await()
            job.cancelAndJoin()
            assertEquals(0, receipts)
            assertFalse(disposableCopy!!.parentFile!!.exists())
            assertEquals("Keep", File(neighbor, "keep.json").readText())
        } finally {
            scope.cancel()
            neighbor.deleteRecursively()
        }
    }
}
