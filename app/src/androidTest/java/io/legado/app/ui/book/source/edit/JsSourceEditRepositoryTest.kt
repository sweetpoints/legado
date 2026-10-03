package io.legado.app.ui.book.source.edit

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceEditRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = FileJsSourceEditRepository(context)
    private val sessions = mutableListOf<String>()
    private val transfers = mutableListOf<String>()

    @After
    fun cleanup() {
        sessions.forEach { sessionId ->
            AtomicFile(File(context.filesDir, "js-source-edit-drafts/$sessionId.json")).delete()
        }
        transfers.forEach { path -> File(path).delete() }
    }

    @Test
    fun atomicBackupRestoresCompleteExecutableDraft() = runBlocking {
        val sessionId = session()
        val original = JsSourceDraft("js".repeat(500_000), "source", JsSourceEditStage.EDITOR_OPEN)
        repository.write(sessionId, original)
        withContext(Dispatchers.IO) {
            val target = AtomicFile(File(context.filesDir, "js-source-edit-drafts/$sessionId.json"))
            target.startWrite().use { it.write("incomplete".toByteArray()) }
        }
        assertEquals(original, FileJsSourceEditRepository(context).read(sessionId))
    }

    @Test
    fun closeTombstoneRejectsOlderAndNewerLateWriters() = runBlocking {
        val sessionId = session()
        val original = JsSourceDraft("script", "source", JsSourceEditStage.READY, revision = 10)
        repository.write(sessionId, original)
        val closed = original.copy(text = "", finished = true, revision = 11)
        repository.write(sessionId, closed)
        repository.write(sessionId, original)
        repository.write(sessionId, original.copy(text = "late", revision = 12))
        assertEquals(closed, repository.read(sessionId))
    }

    @Test
    fun resultTransferRoundTripsLargeTextAndReleasesOnlyRequestedFile() = runBlocking {
        val script = "const large = '🦉';\n".repeat(25_000)
        val ownedPath = repository.transfer(script).also { transfers += it }
        val neighborPath = repository.transfer("neighbor").also { transfers += it }
        assertEquals(script, repository.editorText(ownedPath))
        repository.release(ownedPath)
        assertFalse(File(ownedPath).exists())
        assertTrue(File(neighborPath).exists())
        assertEquals("neighbor", repository.editorText(neighborPath))
    }

    @Test
    fun traversalSessionAndUnrelatedTransferAreRejected() = runBlocking {
        assertTrue(runCatching { repository.read("../outside") }.isFailure)
        assertTrue(
            runCatching { repository.editorText(File(context.filesDir, "other.txt").absolutePath) }
                .isFailure
        )
    }

    private fun session(): String {
        return UUID.randomUUID().toString().also { sessions += it }
    }
}
