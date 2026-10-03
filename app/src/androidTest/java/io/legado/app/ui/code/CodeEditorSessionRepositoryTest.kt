package io.legado.app.ui.code

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import io.legado.app.help.CacheManager
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

class CodeEditorSessionRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "code-session-test-${UUID.randomUUID()}")

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun realAtomicLargeRawCodeAndReceiptSurviveFreshRepositoryAndBackupRecovery() = runBlocking {
        val id = UUID.randomUUID().toString()
        val repository = FileCodeEditorSessionRepository(context, directory)
        val raw = "😀\r\n@js:java.log('raw');\r\n".repeat(30_000)
        val session =
            repository
                .loadLaunch(
                    CodeEditorLaunch(
                        text = raw,
                        cursorPosition = raw.length,
                        showDebugSource = true,
                        showLoginSource = true,
                        useTextFile = true,
                    )
                )
                .copy(
                    revision = 4,
                    returnReceipt = CodeEditorReturnReceipt(UUID.randomUUID().toString(), 1, true),
                )
        assertTrue(repository.write(id, session))
        val second = FileCodeEditorSessionRepository(context, directory)
        assertEquals(session, second.read(id))
        assertTrue(second.write(id, session.copy()))
        assertFalse(second.write(id, session.edited("different payload", CodeEditorSelection())))
        withContext(Dispatchers.IO) {
            AtomicFile(File(directory, "$id.json")).startWrite().use {
                it.write("partial".toByteArray())
            }
        }
        assertEquals(session, second.read(id))
        val closed = session.closed().copy(revision = 5)
        assertTrue(second.write(id, closed))
        assertFalse(repository.write(id, session.copy(revision = 99)))
        assertEquals(closed, repository.read(id))
        assertTrue(File(directory, "$id.json").exists())
    }

    @Test
    fun legacyCacheThenFileThenInlinePriorityAndReadOnlyFlagsRemainExact() = runBlocking {
        val cacheKey = "code-editor-test-${UUID.randomUUID()}"
        val repository = FileCodeEditorSessionRepository(context, directory)
        val textFile = withContext(Dispatchers.IO) { CodeTextTransfer.write(context, "file\r\n") }
        CacheManager.putMemory(cacheKey, "cache\r\n")
        try {
            val cached =
                repository.loadLaunch(
                    CodeEditorLaunch(cacheKey = cacheKey, textFile = textFile, text = "inline")
                )
            assertEquals("cache\r\n", cached.text)
            assertFalse(cached.writable)
            val fromFile =
                repository.loadLaunch(CodeEditorLaunch(textFile = textFile, text = "inline"))
            assertEquals("file\r\n", fromFile.text)
            assertTrue(fromFile.writable)
            val inline =
                repository.loadLaunch(
                    CodeEditorLaunch(
                        text = "inline",
                        readOnly = true,
                        returnUnchangedText = true,
                        checkJavaScriptSyntax = true,
                    )
                )
            assertFalse(inline.writable)
            assertTrue(inline.returnUnchangedText)
            assertTrue(inline.checkJavaScriptSyntax)
        } finally {
            CacheManager.deleteMemory(cacheKey)
            withContext(Dispatchers.IO) { CodeTextTransfer.delete(context, textFile) }
        }
    }

    @Test
    fun realOutputRetriesSameOwnedFileAndSurvivesPrivateCloseForCallerConsumption() = runBlocking {
        val repository = FileCodeEditorSessionRepository(context, directory)
        val id = UUID.randomUUID().toString()
        val receiptId = UUID.randomUUID().toString()
        val path = repository.returnFile(id, receiptId)
        val raw = "😀\r\n@js:1+1".repeat(10_000)
        val receipt = CodeEditorReturnReceipt(receiptId, 3, true, "loginSource", path)
        val session = CodeEditorSession(raw, useTextFile = true, returnReceipt = receipt)
        try {
            assertTrue(repository.write(id, session))
            val first = repository.prepareOutput(id, session)
            val second =
                FileCodeEditorSessionRepository(context, directory).prepareOutput(id, session)
            assertEquals(first, second)
            assertEquals(path, first.textFile)
            assertEquals(null, first.text)
            assertEquals("loginSource", first.action)
            assertEquals(raw, withContext(Dispatchers.IO) { CodeTextTransfer.read(context, path) })
            assertTrue(repository.write(id, session.closed().copy(revision = 1)))
            assertTrue(
                runCatching { repository.prepareOutput(id, session) }.exceptionOrNull()
                    is CodeEditorSessionConflict
            )
            assertEquals(raw, withContext(Dispatchers.IO) { CodeTextTransfer.read(context, path) })
        } finally {
            repository.releaseOutput(path)
        }
        assertFalse(File(path).exists())
    }

    @Test
    fun realInlineAndCursorOnlyReturnKeepPublicPayloadContract() = runBlocking {
        val repository = FileCodeEditorSessionRepository(context, directory)
        val id = UUID.randomUUID().toString()
        val raw = "\r\n😀"
        val inline =
            CodeEditorSession(
                raw,
                returnReceipt = CodeEditorReturnReceipt(UUID.randomUUID().toString(), 2, true),
            )
        assertTrue(repository.write(id, inline))
        assertEquals(CodeEditorResultPayload(2, text = raw), repository.prepareOutput(id, inline))
        val cursorOnly =
            inline.copy(
                revision = 1,
                returnReceipt = CodeEditorReturnReceipt(UUID.randomUUID().toString(), 1, false),
            )
        assertTrue(repository.write(id, cursorOnly))
        assertEquals(CodeEditorResultPayload(1), repository.prepareOutput(id, cursorOnly))
    }

    @Test
    fun privateIdentityRejectsTraversalBeforeAnyFileWrite() = runBlocking {
        val repository = FileCodeEditorSessionRepository(context, directory)
        assertTrue(runCatching { repository.write("../other", CodeEditorSession("raw")) }.isFailure)
        assertFalse(directory.exists())
    }
}
