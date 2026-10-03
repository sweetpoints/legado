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
    fun privateIdentityRejectsTraversalBeforeAnyFileWrite() = runBlocking {
        val repository = FileCodeEditorSessionRepository(context, directory)
        assertTrue(runCatching { repository.write("../other", CodeEditorSession("raw")) }.isFailure)
        assertFalse(directory.exists())
    }
}
